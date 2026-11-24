package kia.app.adas;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Main-thread lifecycle and listener; synchronous vendor RPCs run on a serial worker. */
final class TeyesAdasClient {
    public interface Listener {
        void onStatus(String phase, String detail);
        void onRunning(boolean running);
        void onFrontEvent(TeyesAdasFrontEvent event, int frames, int warningFlags,
                          int directions, int testFrames);
        default void onMalformedFrontEvent() {}
    }

    public static final String PACKAGE = "com.teyes.adas";
    private static final ComponentName COMPONENT = new ComponentName(PACKAGE, "com.spd.teyes.AdasService");
    private static final Executor RPC = Executors.newSingleThreadExecutor(r -> new Thread(r, "TeyesAdasBinder"));
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reconnect = this::bind;
    private Session current;
    private boolean enabled;

    public TeyesAdasClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (enabled) return;
        enabled = true;
        bind();
    }

    public void retry() {
        enabled = true;
        main.removeCallbacks(reconnect);
        closeSession();
        bind();
    }

    /** Deliver already received frames before opening or closing a diagnostic interval. */
    public void flushPending() {
        Session session = current;
        if (session == null) return;
        synchronized (session) {
            if (!session.ready || !session.scheduled) return;
            main.removeCallbacks(session.deliver);
        }
        session.deliverPending();
    }

    public void stop() {
        enabled = false;
        main.removeCallbacks(reconnect);
        closeSession();
        listener.onStatus("stopped", "Подписка отключена");
    }

    private void bind() {
        if (!enabled || current != null) return;
        Session session = new Session();
        current = session;
        Intent intent = new Intent("com.teyes.adas.service").setComponent(COMPONENT);
        try {
            ResolveInfo info = context.getPackageManager().resolveService(intent, 0);
            if (info == null || info.serviceInfo == null) {
                fail(session, "missing", "Штатный сервис TEYES ADAS не найден или отключён");
                return;
            }
            listener.onStatus("binding", "Подключение к штатному сервису");
            session.boundRequested = true;
            if (!context.bindService(intent, session, Context.BIND_AUTO_CREATE)) {
                fail(session, "error", "Android отклонил подключение к сервису");
                return;
            }
            main.postDelayed(session.timeout, 10_000L);
        } catch (RuntimeException e) {
            fail(session, "error", reason("Не удалось подключиться к TEYES ADAS", e));
        }
    }

    private void fail(Session session, String phase, String detail) {
        if (current != session) return;
        closeSession();
        listener.onStatus(phase, detail);
        if (enabled) main.postDelayed(reconnect, phase.equals("missing") ? 60_000L : 10_000L);
    }

    private void closeSession() {
        Session session = current;
        if (session == null) return;
        synchronized (session) {
            session.closed = true;
        }
        flushPending();
        current = null;
        main.removeCallbacks(session.timeout);
        main.removeCallbacks(session.poll);
        main.removeCallbacks(session.deliver);
        if (session.boundRequested) {
            try {
                context.unbindService(session);
            } catch (IllegalArgumentException ignored) {
                // Android may have rejected the bind before creating its registration.
            }
        }
        // The CC4 indexes callbacks by package and enqueues changes in order. Serialize
        // unregister with any new registration, including across activity/service recreation.
        RPC.execute(() -> {
            if (session.registered != null) {
                try {
                    session.registered.unregister();
                } catch (RemoteException | RuntimeException ignored) {
                    // Service death also drops its callback registry.
                }
            }
        });
    }

    private static String reason(String message, Exception e) {
        String detail = e.getMessage();
        return message + " (" + (detail == null ? e.getClass().getSimpleName() : detail) + ")";
    }

    private final class Session implements ServiceConnection {
        private final Runnable timeout = () -> fail(this, "error", "Истекло время подключения к TEYES ADAS");
        private final Runnable poll = this::pollRunning;
        private final Runnable deliver = this::deliverPending;
        private final Callback callback = new Callback();
        private volatile boolean closed;
        private boolean boundRequested;
        // Only accessed on the serial RPC worker.
        private TeyesAdasBinder registered;
        // Protected by this session's lock. At most one delivery can be queued.
        private boolean ready;
        private boolean scheduled;
        private TeyesAdasFrontEvent latest;
        private int frames;
        private int testFrames;
        private int warningFlags;
        private int directions;
        private boolean malformed;

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (current != this) return;
            RPC.execute(() -> {
                if (closed) return;
                try {
                    TeyesAdasBinder remote = new TeyesAdasBinder(service, context.getPackageName());
                    remote.register(callback);
                    registered = remote;
                    main.post(this::connected);
                } catch (RuntimeException | RemoteException e) {
                    main.post(() -> fail(this, "error", reason("Сервис отклонил подписку", e)));
                }
            });
        }

        private void connected() {
            if (current != this) return;
            main.removeCallbacks(timeout);
            listener.onStatus("connected", "Подписка на события TEYES ADAS зарегистрирована");
            synchronized (this) {
                ready = true;
                scheduleDelivery();
            }
            pollRunning();
        }

        private void pollRunning() {
            if (current != this) return;
            RPC.execute(() -> {
                if (closed) return;
                try {
                    boolean running = registered.running();
                    main.post(() -> {
                        if (current != this) return;
                        listener.onRunning(running);
                        main.postDelayed(poll, 5_000L);
                    });
                } catch (RemoteException | RuntimeException e) {
                    main.post(() -> fail(this, "error", reason("Не удалось прочитать состояние ADAS", e)));
                }
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            fail(this, "error", "Соединение с TEYES ADAS потеряно");
        }

        @Override public void onBindingDied(ComponentName name) {
            fail(this, "error", "Android завершил соединение с TEYES ADAS");
        }

        @Override public void onNullBinding(ComponentName name) {
            fail(this, "error", "Сервис TEYES ADAS вернул пустое соединение");
        }

        private synchronized void record(TeyesAdasFrontEvent event) {
            if (closed) return;
            if (event == null) {
                malformed = true;
            } else {
                latest = event;
                frames++;
                if (event.testEvent) testFrames++;
                warningFlags |= event.warningType;
                directions |= TeyesAdasFrontEvent.directionFromState(event.warningType, event.laneState);
            }
            scheduleDelivery();
        }

        private void scheduleDelivery() {
            if (ready && !scheduled && (latest != null || malformed)) {
                scheduled = true;
                main.postDelayed(deliver, 100L);
            }
        }

        private void deliverPending() {
            if (current != this) return;
            TeyesAdasFrontEvent event;
            int count;
            int testCount;
            int flags;
            int sides;
            boolean hadMalformed;
            synchronized (this) {
                event = latest;
                count = frames;
                testCount = testFrames;
                flags = warningFlags;
                sides = directions;
                hadMalformed = malformed;
                scheduled = false;
                latest = null;
                frames = 0;
                testFrames = 0;
                warningFlags = 0;
                directions = 0;
                malformed = false;
            }
            if (event != null) {
                listener.onFrontEvent(event, count, flags, sides, testCount);
            }
            if (hadMalformed) listener.onMalformedFrontEvent();
        }

        private final class Callback extends Binder {
            Callback() { attachInterface(null, TeyesAdasBinder.CALLBACK_DESCRIPTOR); }

            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code < 1 || code > 8) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(TeyesAdasBinder.CALLBACK_DESCRIPTOR);
                if (code == 1 && !closed) {
                    try {
                        TeyesAdasFrontEvent event = TeyesAdasBinder.readFrontEvent(data);
                        if (event != null) record(event);
                    } catch (IllegalArgumentException e) {
                        record(null);
                    }
                }
                // 2 is the rear camera; 3–8 are informational. isAdasRunning() gives
                // the recognizer status independently of cached or demo callback data.
                if (reply != null) reply.writeNoException();
                return true;
            }
        }
    }
}
