package kia.app.sunroof;

import android.content.Context;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import kia.app.core.AppLog;

/**
 * Optional, read-only observer for the native TEYES ACC interface.
 *
 * <p>The retained framework sends common command 5 before its sleep timer; arg1 is
 * willPowerOff (1 = ACC off). Command 8, getPowerState(), Android screen state and USB
 * connection state cannot substitute for that event. Native classes are optional and
 * are never packaged or instantiated here. Access can be unavailable on other firmware.
 */
public final class TeyesSunroofObserver {
    public interface Listener {
        void onAcc(boolean known, boolean on, long receivedElapsed, String source);
        void onHardwareAction(int keyCode, int action);
        default void onSleep(boolean asleep) {
        }
    }

    interface ServiceLocator {
        IBinder findService() throws ReflectiveOperationException;
    }

    static final String CALLBACK_DESCRIPTOR = "com.spd.system.aidl.ISpdServiceCallback";
    private static final String BASELINE_SOURCE = "teyes:DeviceStatusInfo.accStatus";
    private static final String CALLBACK_SOURCE = "teyes:cmd5";
    private static final long POLL_MS = 1_000L;
    private static final long ERROR_LOG_INTERVAL_MS = 30_000L;

    private final Context app;
    private final Listener listener;
    private final ServiceLocator serviceLocator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "kia-sunroof-teyes-observer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong signalOrder = new AtomicLong();
    private volatile boolean running;
    private volatile long generation;
    private volatile String status = "TEYES ACC: наблюдение выключено";
    private final AtomicLong callbackToken = new AtomicLong();

    // Main-thread state. In-flight work always rechecks its generation before delivery.
    private Runnable pendingPoll;
    private long deliveredOrder;
    private boolean stateDelivered;
    private boolean lastKnown;
    private long lastKnownToken;
    private long lastErrorLogElapsed = -ERROR_LOG_INTERVAL_MS;

    // Worker-thread state. Registration/reads/unregistration never run on the UI thread.
    private long bindingGeneration;
    private long bindingToken;
    private long nextCallbackToken;
    private TeyesSunroofBinder service;
    private CallbackBinder callback;
    private IBinder.DeathRecipient deathRecipient;

    public TeyesSunroofObserver(Context context, Listener listener) {
        this(context, listener, TeyesSunroofBinder::findService);
    }

    TeyesSunroofObserver(Context context, Listener listener, ServiceLocator serviceLocator) {
        if (context == null || listener == null || serviceLocator == null) {
            throw new IllegalArgumentException("context/listener/serviceLocator");
        }
        Context application = context.getApplicationContext();
        this.app = application == null ? context : application;
        this.listener = listener;
        this.serviceLocator = serviceLocator;
    }

    public void start() {
        onMain(() -> {
            if (running) return;
            running = true;
            long current = ++generation;
            stateDelivered = false;
            deliveredOrder = 0L;
            publishUnknown(current, "TEYES ACC: подключение", "teyes:pending");
            poll(current);
        });
    }

    public void stop() {
        onMain(() -> {
            if (!running) return;
            long stopped = generation;
            running = false;
            generation++;
            if (pendingPoll != null) main.removeCallbacks(pendingPoll);
            pendingPoll = null;
            status = "TEYES ACC: наблюдение выключено";
            worker.execute(() -> clearBinding(stopped));
        });
    }

    public String statusSummary() {
        return status;
    }

    private boolean active(long current) {
        return running && generation == current;
    }

    private void poll(long current) {
        if (!active(current)) return;
        worker.execute(() -> {
            if (!active(current)) return;
            // Reserve the observation before native calls: an ACC callback received while a
            // read is in flight must supersede both its value and its eventual failure.
            long observationOrder = signalOrder.incrementAndGet();
            try {
                try {
                    ensureBinding(current);
                } catch (ReflectiveOperationException | RemoteException | RuntimeException | LinkageError error) {
                    clearBinding(current);
                    reportUnavailable(current, error, observationOrder);
                    return;
                }
                long observedToken = callbackToken.get();
                if (!active(current) || observedToken == 0L) return;
                int acc = service.readAcc();
                if (acc == TeyesSunroofSignals.UNKNOWN) {
                    publishUnknown(current, "TEYES ACC: неизвестное значение", "teyes:invalid", observationOrder);
                } else {
                    publishAcc(current, acc, SystemClock.elapsedRealtime(), BASELINE_SOURCE, observationOrder, observedToken);
                }
            } catch (RemoteException | RuntimeException | LinkageError error) {
                // An unavailable aggregate must not remove a successfully registered
                // callback: the command-5 ACC channel is independent of baseline reads.
                // Only a dead Binder or an explicit disconnect invalidates that binding.
                if (isDeadBinder(error)) clearBinding(current);
                reportUnavailable(current, error, observationOrder);
            } finally {
                main.post(() -> {
                    if (!active(current)) return;
                    pendingPoll = () -> poll(current);
                    main.postDelayed(pendingPoll, POLL_MS);
                });
            }
        });
    }

    private static boolean isDeadBinder(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof DeadObjectException) return true;
        }
        return false;
    }

    private void ensureBinding(long current) throws ReflectiveOperationException, RemoteException {
        if (service != null && bindingGeneration == current && callbackToken.get() == bindingToken
                && service.asBinder().isBinderAlive()) return;
        if (service != null) clearBinding(bindingGeneration);
        TeyesSunroofBinder nativeService = new TeyesSunroofBinder(serviceLocator.findService());
        long token = ++nextCallbackToken;
        CallbackBinder binder = new CallbackBinder(current, token);
        IBinder.DeathRecipient death = () -> invalidateBinding(current, token,
                "TEYES ACC: служба отключена", "teyes:binder-died");
        service = nativeService;
        callback = binder;
        deathRecipient = death;
        bindingGeneration = current;
        bindingToken = token;
        callbackToken.set(token);
        if (!active(current)) {
            clearBinding(current);
            return;
        }
        nativeService.asBinder().linkToDeath(death, 0);
        nativeService.register(app.getPackageName(), binder);
    }

    private void clearBinding(long current) {
        if (service == null || bindingGeneration != current) return;
        TeyesSunroofBinder oldService = service;
        IBinder.DeathRecipient oldDeath = deathRecipient;
        service = null;
        callback = null;
        deathRecipient = null;
        bindingGeneration = 0L;
        bindingToken = 0L;
        callbackToken.set(0L);
        if (oldDeath != null) {
            try {
                oldService.asBinder().unlinkToDeath(oldDeath, 0);
            } catch (RuntimeException ignored) {
                // Death can race cleanup.
            }
        }
        try {
            oldService.unregister(app.getPackageName());
        } catch (RemoteException | RuntimeException | LinkageError ignored) {
            // Native reconnect/stop is allowed to race unregistration.
        }
    }

    private void invalidateBinding(long current, long token, String summary, String source) {
        invalidateBinding(current, token, summary, source, signalOrder.incrementAndGet());
    }

    private void invalidateBinding(long current, long token, String summary, String source, long disconnectedOrder) {
        if (!active(current) || !callbackToken.compareAndSet(token, 0L)) return;
        // Fence both newly arriving and already queued callbacks before worker cleanup.
        publishDisconnected(current, token, summary, source, disconnectedOrder);
        worker.execute(() -> {
            if (bindingGeneration == current && bindingToken == token) clearBinding(current);
        });
    }

    private void publishDisconnected(long current, long token, String summary, String source, long order) {
        long receivedElapsed = SystemClock.elapsedRealtime();
        main.post(() -> {
            if (!active(current)) return;
            // A callback may finish between reserving the disconnect order and its CAS.
            // It cannot keep an invalidated binding known, but a new binding's accepted
            // baseline/callback must not be replaced by this old disconnect either.
            if (callbackToken.get() != 0L && (!lastKnown || lastKnownToken != token)) return;
            deliveredOrder = Math.max(deliveredOrder, order);
            status = summary;
            if (stateDelivered && !lastKnown) return;
            stateDelivered = true;
            lastKnown = false;
            listener.onAcc(false, false, receivedElapsed, source);
        });
    }

    private void handleNativeCallback(long current, long token, String name, Object[] args) {
        if (!active(current) || callbackToken.get() != token) return;
        if ("sendCommonToSouce".equals(name) && threeIntegers(args)) {
            int acc = TeyesSunroofSignals.callbackAcc((Integer) args[0], (Integer) args[1], (Integer) args[2]);
            if (acc != TeyesSunroofSignals.UNKNOWN) {
                long receivedOrder = signalOrder.incrementAndGet();
                publishAcc(current, acc, SystemClock.elapsedRealtime(), CALLBACK_SOURCE, receivedOrder, token);
            }
            int asleep = TeyesSunroofSignals.callbackSleep((Integer) args[0], (Integer) args[1], (Integer) args[2]);
            if (asleep != TeyesSunroofSignals.UNKNOWN) {
                main.post(() -> {
                    if (active(current) && callbackToken.get() == token) listener.onSleep(asleep == 1);
                });
            }
        } else if ("sendKeyCode".equals(name) && threeIntegers(args)) {
            int keyCode = (Integer) args[0];
            int repeatCount = (Integer) args[1];
            int action = (Integer) args[2];
            if (TeyesSunroofSignals.hardwareAction(keyCode, repeatCount, action)) {
                main.post(() -> {
                    if (active(current) && callbackToken.get() == token) listener.onHardwareAction(keyCode, action);
                });
            }
        } else if ("onServiceConnect".equals(name) && args != null && args.length == 1
                && Boolean.FALSE.equals(args[0])) {
            invalidateBinding(current, token, "TEYES ACC: служба отключена", "teyes:disconnected");
        }
    }

    private static boolean threeIntegers(Object[] values) {
        return values != null && values.length == 3 && values[0] instanceof Integer
                && values[1] instanceof Integer && values[2] instanceof Integer;
    }

    private void publishAcc(long current, int state, long receivedElapsed, String source, long order, long token) {
        main.post(() -> {
            if (!active(current) || callbackToken.get() != token || order <= deliveredOrder) return;
            deliveredOrder = order;
            stateDelivered = true;
            lastKnown = true;
            lastKnownToken = token;
            status = state == TeyesSunroofSignals.ON ? "TEYES ACC: включено" : "TEYES ACC: выключено";
            // Identical valid polls still refresh the sample time; consumers detect transitions.
            listener.onAcc(true, state == TeyesSunroofSignals.ON, receivedElapsed, source);
        });
    }

    private void publishUnknown(long current, String summary, String source) {
        publishUnknown(current, summary, source, signalOrder.incrementAndGet());
    }

    private void publishUnknown(long current, String summary, String source, long order) {
        long receivedElapsed = SystemClock.elapsedRealtime();
        main.post(() -> {
            if (!active(current) || order <= deliveredOrder) return;
            deliveredOrder = order;
            status = summary;
            if (stateDelivered && !lastKnown) return;
            stateDelivered = true;
            lastKnown = false;
            listener.onAcc(false, false, receivedElapsed, source);
        });
    }

    private void reportUnavailable(long current, Throwable error, long observationOrder) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
        String reason = cause.getClass().getSimpleName();
        publishUnknown(current, "TEYES ACC: недоступно (" + reason + ")", "teyes:unavailable", observationOrder);
        main.post(() -> {
            if (!active(current)) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastErrorLogElapsed < ERROR_LOG_INTERVAL_MS) return;
            lastErrorLogElapsed = now;
            AppLog.line(app, "Sunroof TEYES ACC unavailable: " + reason);
        });
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    /** Verified callback wire format; unknown transaction numbers remain unhandled. */
    final class CallbackBinder extends Binder {
        private final long current;
        private final long token;

        CallbackBinder(long current, long token) {
            this.current = current;
            this.token = token;
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code < 1 || code > TeyesSunroofSignals.KEY_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(CALLBACK_DESCRIPTOR);
            if (code == TeyesSunroofSignals.COMMON_TRANSACTION || code == TeyesSunroofSignals.KEY_TRANSACTION) {
                requireBytes(data, 12);
                int first = data.readInt();
                int second = data.readInt();
                int third = data.readInt();
                requireEnd(data);
                handleNativeCallback(current, token,
                        code == TeyesSunroofSignals.COMMON_TRANSACTION ? "sendCommonToSouce" : "sendKeyCode",
                        new Object[]{first, second, third});
                if (code == TeyesSunroofSignals.KEY_TRANSACTION && reply != null) {
                    reply.writeNoException();
                    reply.writeInt(-1);
                }
                return true;
            }
            if (code == 12) {
                data.readHashMap(getClass().getClassLoader());
            } else {
                requireBytes(data, code == 5 || code == 6 || code == 11 ? 8 : 4);
                int first = data.readInt();
                if (code == 5 || code == 6 || code == 11) data.readInt();
                requireEnd(data);
                if (code == 1) handleNativeCallback(current, token, "onServiceConnect", new Object[]{first != 0});
                return true;
            }
            requireEnd(data);
            return true;
        }

        private void requireEnd(Parcel data) {
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected TEYES callback data");
        }

        private void requireBytes(Parcel data, int count) {
            if (data.dataAvail() < count) throw new IllegalArgumentException("Truncated TEYES callback data");
        }
    }
}
