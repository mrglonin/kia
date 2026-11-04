package kia.app.sunroof;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

    static final String CALLBACK_DESCRIPTOR = "com.spd.system.aidl.ISpdServiceCallback";
    private static final String MANAGER_CLASS = "com.spd.system.service.SpdManager";
    private static final String DEVICE_CLASS = "com.spd.system.entity.aidl.DeviceStatusInfo";
    private static final String BASELINE_SOURCE = "teyes:DeviceStatusInfo.accStatus";
    private static final String CALLBACK_SOURCE = "teyes:cmd5";
    private static final long POLL_MS = 1_000L;
    private static final long ERROR_LOG_INTERVAL_MS = 30_000L;

    private final Context app;
    private final Listener listener;
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
    private volatile long callbackToken;

    // Main-thread state. In-flight work always rechecks its generation before delivery.
    private Runnable pendingPoll;
    private long deliveredOrder;
    private boolean stateDelivered;
    private boolean lastKnown;
    private long lastErrorLogElapsed = -ERROR_LOG_INTERVAL_MS;

    // Worker-thread state. Registration/reads/unregistration never run on the UI thread.
    private long bindingGeneration;
    private long nextCallbackToken;
    private Object manager;
    private Method getSourceInfo;
    private Method unregister;
    private IInterface callback;

    public TeyesSunroofObserver(Context context, Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("context/listener");
        Context application = context.getApplicationContext();
        this.app = application == null ? context : application;
        this.listener = listener;
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
                ensureBinding(current);
                if (!active(current)) return;
                Object wrapper = getSourceInfo.invoke(manager, "android");
                if (wrapper == null) throw new IllegalStateException("native service has no device state");
                Object device = wrapper.getClass().getMethod("getSourceInfo").invoke(wrapper);
                // Do not trust a similarly named field in another source or default object.
                if (device == null || !DEVICE_CLASS.equals(device.getClass().getName())) {
                    throw new IllegalStateException("native device state unavailable");
                }
                int acc = TeyesSunroofSignals.aggregateAcc(device.getClass().getField("accStatus").get(device));
                if (acc == TeyesSunroofSignals.UNKNOWN) {
                    publishUnknown(current, "TEYES ACC: неизвестное значение", "teyes:invalid", observationOrder);
                } else {
                    publishAcc(current, acc, SystemClock.elapsedRealtime(), BASELINE_SOURCE, observationOrder);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                clearBinding(current);
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

    private void ensureBinding(long current) throws ReflectiveOperationException {
        if (manager != null && bindingGeneration == current) return;
        if (manager != null) clearBinding(bindingGeneration);
        Class<?> managerType = Class.forName(MANAGER_CLASS);
        Class<?> callbackType = Class.forName(CALLBACK_DESCRIPTOR);
        if (!callbackType.isInterface() || !IInterface.class.isAssignableFrom(callbackType)) {
            throw new IllegalStateException("native callback interface unavailable");
        }
        Object nativeManager = managerType.getMethod("getInstance", Context.class).invoke(null, app);
        if (nativeManager == null) throw new IllegalStateException("native manager unavailable");
        Method read = managerType.getMethod("getSourceInfo", String.class);
        Method remove = managerType.getMethod("unRegisterSpdServiceCallback", String.class);
        Method register = managerType.getMethod("registerSpdServiceCallback", String.class, callbackType);
        long token = ++nextCallbackToken;
        CallbackBinder binder = new CallbackBinder(current, token);
        Object nativeCallback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                new Class<?>[]{callbackType}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("asBinder".equals(name)) return binder;
                    if (method.getDeclaringClass() == Object.class) {
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("toString".equals(name)) return "KiaSunroofTeyesCallback";
                    }
                    handleNativeCallback(current, token, name, args);
                    // Acknowledge an observed key without consuming the panel's normal action.
                    return method.getReturnType() == int.class ? -1 : null;
                });
        binder.attachInterface((IInterface) nativeCallback, CALLBACK_DESCRIPTOR);
        manager = nativeManager;
        getSourceInfo = read;
        unregister = remove;
        callback = (IInterface) nativeCallback;
        bindingGeneration = current;
        callbackToken = token;
        if (!active(current)) {
            clearBinding(current);
            return;
        }
        register.invoke(nativeManager, app.getPackageName(), nativeCallback);
    }

    private void clearBinding(long current) {
        if (manager == null || bindingGeneration != current) return;
        Object oldManager = manager;
        Method remove = unregister;
        manager = null;
        getSourceInfo = null;
        unregister = null;
        callback = null;
        bindingGeneration = 0L;
        callbackToken = 0L;
        if (remove != null) {
            try {
                remove.invoke(oldManager, app.getPackageName());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Native reconnect/stop is allowed to race unregistration.
            }
        }
    }

    private void handleNativeCallback(long current, long token, String name, Object[] args) {
        if (!active(current) || callbackToken != token) return;
        if ("sendCommonToSouce".equals(name) && threeIntegers(args)) {
            int acc = TeyesSunroofSignals.callbackAcc((Integer) args[0], (Integer) args[1], (Integer) args[2]);
            if (acc != TeyesSunroofSignals.UNKNOWN) {
                long receivedOrder = signalOrder.incrementAndGet();
                publishAcc(current, acc, SystemClock.elapsedRealtime(), CALLBACK_SOURCE, receivedOrder);
            }
            int asleep = TeyesSunroofSignals.callbackSleep((Integer) args[0], (Integer) args[1], (Integer) args[2]);
            if (asleep != TeyesSunroofSignals.UNKNOWN) {
                main.post(() -> {
                    if (active(current) && callbackToken == token) listener.onSleep(asleep == 1);
                });
            }
        } else if ("sendKeyCode".equals(name) && threeIntegers(args)) {
            int keyCode = (Integer) args[0];
            int repeatCount = (Integer) args[1];
            int action = (Integer) args[2];
            if (TeyesSunroofSignals.hardwareAction(keyCode, repeatCount, action)) {
                main.post(() -> {
                    if (active(current) && callbackToken == token) listener.onHardwareAction(keyCode, action);
                });
            }
        } else if ("onServiceConnect".equals(name) && args != null && args.length == 1
                && Boolean.FALSE.equals(args[0])) {
            publishUnknown(current, "TEYES ACC: служба отключена", "teyes:disconnected");
            worker.execute(() -> {
                if (callbackToken == token) clearBinding(current);
            });
        }
    }

    private static boolean threeIntegers(Object[] values) {
        return values != null && values.length == 3 && values[0] instanceof Integer
                && values[1] instanceof Integer && values[2] instanceof Integer;
    }

    private void publishAcc(long current, int state, long receivedElapsed, String source, long order) {
        main.post(() -> {
            if (!active(current) || order <= deliveredOrder) return;
            deliveredOrder = order;
            stateDelivered = true;
            lastKnown = true;
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
                int first = data.readInt();
                if (code == 5 || code == 6 || code == 11) data.readInt();
                if (code == 1) handleNativeCallback(current, token, "onServiceConnect", new Object[]{first != 0});
            }
            requireEnd(data);
            return true;
        }

        private void requireEnd(Parcel data) {
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected TEYES callback data");
        }
    }
}
