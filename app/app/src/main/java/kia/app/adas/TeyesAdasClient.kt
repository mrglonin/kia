package kia.app.adas

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Main-thread lifecycle and listener; synchronous vendor RPCs run on a serial worker. */
internal class TeyesAdasClient(context: Context, listener: kia.app.adas.TeyesAdasClient.Listener?) {
    interface Listener {
        fun onStatus(phase: String?, detail: String?)

        fun onRunning(running: Boolean)

        fun onFrontEvent(
            event: TeyesAdasFrontEvent?,
            frames: Int,
            warningFlags: Int,
            directions: Int,
            testFrames: Int,
        )

        fun onMalformedFrontEvent() {}
    }

    private val context: Context?
    private val listener: kia.app.adas.TeyesAdasClient.Listener?
    private val main: Handler = Handler(Looper.getMainLooper())
    private val reconnect: Runnable = Runnable { this.bind() }
    private var current: Session? = null
    private var enabled: Boolean = false

    init {
        this.context = context.getApplicationContext()
        this.listener = listener
    }

    fun start() {
        if (enabled) return
        enabled = true
        bind()
    }

    fun retry() {
        enabled = true
        main.removeCallbacks(reconnect)
        closeSession()
        bind()
    }

    /** Deliver already received frames before opening or closing a diagnostic interval. */
    fun flushPending() {
        val session = current
        if (session == null) return
        synchronized(session) {
            if (!session.ready || !session.scheduled) return
            main.removeCallbacks(session.deliver)
        }
        session.deliverPending()
    }

    fun stop() {
        enabled = false
        main.removeCallbacks(reconnect)
        closeSession()
        listener!!.onStatus("stopped", "Подписка отключена")
    }

    private fun bind() {
        if (!enabled || current != null) return
        val session: Session = Session()
        current = session
        val intent: Intent =
            Intent("com.teyes.adas.service").setComponent(TeyesAdasClient.COMPONENT)
        try {
            val info = context!!.getPackageManager().resolveService(intent, 0)
            if (info == null || info.serviceInfo == null) {
                fail(session, "missing", "Штатный сервис TEYES ADAS не найден или отключён")
                return
            }
            listener!!.onStatus("binding", "Подключение к штатному сервису")
            session.boundRequested = true
            if (!context.bindService(intent, session, Context.BIND_AUTO_CREATE)) {
                fail(session, "error", "Android отклонил подключение к сервису")
                return
            }
            main.postDelayed(session.timeout, 10000L)
        } catch (e: RuntimeException) {
            fail(
                session,
                "error",
                TeyesAdasClient.reason("Не удалось подключиться к TEYES ADAS", e),
            )
        }
    }

    private fun fail(session: Session?, phase: String, detail: String?) {
        if (current != session) return
        closeSession()
        listener!!.onStatus(phase, detail)
        if (enabled) main.postDelayed(reconnect, if (phase.equals("missing")) 60000L else 10000L)
    }

    private fun closeSession() {
        val session = current
        if (session == null) return
        synchronized(session) { session.closed = true }
        flushPending()
        current = null
        main.removeCallbacks(session.timeout)
        main.removeCallbacks(session.poll)
        main.removeCallbacks(session.deliver)
        if (session.boundRequested) {
            try {
                context!!.unbindService(session)
            } catch (ignored: IllegalArgumentException) {
                // Android may have rejected the bind before creating its registration.
            }
        }
        // The CC4 indexes callbacks by package and enqueues changes in order. Serialize
        // unregister with any new registration, including across activity/service recreation.
        TeyesAdasClient.RPC!!.execute({
            if (session.registered != null) {
                try {
                    session.registered!!.unregister()
                } catch (ignored: RemoteException) {
                    // Service death also drops its callback registry.
                } catch (ignored: RuntimeException) {}
            }
        })
    }

    private inner class Session : ServiceConnection {
        val timeout: Runnable = Runnable {
            fail(this, "error", "Истекло время подключения к TEYES ADAS")
        }
        val poll: Runnable = Runnable { this.pollRunning() }
        val deliver: Runnable = Runnable { this.deliverPending() }
        private val callback: Callback = Callback()
        @kotlin.concurrent.Volatile var closed: Boolean = false
        var boundRequested: Boolean = false
        // Only accessed on the serial RPC worker.
        var registered: TeyesAdasBinder? = null
        // Protected by this session's lock. At most one delivery can be queued.
        var ready: Boolean = false
        var scheduled: Boolean = false
        private var latest: TeyesAdasFrontEvent? = null
        private var frames: Int = 0
        private var testFrames: Int = 0
        private var warningFlags: Int = 0
        private var directions: Int = 0
        private var malformed: Boolean = false

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            if (current != this) return
            TeyesAdasClient.RPC!!.execute({
                if (closed) return@execute
                try {
                    val remote: TeyesAdasBinder =
                        TeyesAdasBinder(service, context!!.getPackageName())
                    remote.register(callback)
                    registered = remote
                    main.post({ this.connected() })
                } catch (e: RuntimeException) {
                    main.post({
                        fail(this, "error", TeyesAdasClient.reason("Сервис отклонил подписку", e))
                    })
                } catch (e: RemoteException) {
                    main.post({
                        fail(this, "error", TeyesAdasClient.reason("Сервис отклонил подписку", e))
                    })
                }
            })
        }

        fun connected() {
            if (current != this) return
            main.removeCallbacks(timeout)
            listener!!.onStatus("connected", "Подписка на события TEYES ADAS зарегистрирована")
            synchronized(this) {
                ready = true
                scheduleDelivery()
            }
            pollRunning()
        }

        fun pollRunning() {
            if (current != this) return
            TeyesAdasClient.RPC!!.execute({
                if (closed) return@execute
                try {
                    val running: Boolean = registered!!.running()
                    main.post({
                        if (current != this) return@post
                        listener!!.onRunning(running)
                        main.postDelayed(poll, 5000L)
                    })
                } catch (e: RemoteException) {
                    main.post({
                        fail(
                            this,
                            "error",
                            TeyesAdasClient.reason("Не удалось прочитать состояние ADAS", e),
                        )
                    })
                } catch (e: RuntimeException) {
                    main.post({
                        fail(
                            this,
                            "error",
                            TeyesAdasClient.reason("Не удалось прочитать состояние ADAS", e),
                        )
                    })
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fail(this, "error", "Соединение с TEYES ADAS потеряно")
        }

        override fun onBindingDied(name: ComponentName?) {
            fail(this, "error", "Android завершил соединение с TEYES ADAS")
        }

        override fun onNullBinding(name: ComponentName?) {
            fail(this, "error", "Сервис TEYES ADAS вернул пустое соединение")
        }

        @kotlin.jvm.Synchronized
        fun record(event: TeyesAdasFrontEvent?) {
            if (closed) return
            if (event == null) {
                malformed = true
            } else {
                latest = event
                frames++
                if (event.testEvent) testFrames++
                warningFlags = warningFlags or event.warningType
                directions =
                    directions or
                        TeyesAdasFrontEvent.directionFromState(event.warningType, event.laneState)
            }
            scheduleDelivery()
        }

        fun scheduleDelivery() {
            if (ready && !scheduled && (latest != null || malformed)) {
                scheduled = true
                main.postDelayed(deliver, 100L)
            }
        }

        fun deliverPending() {
            if (current != this) return
            val event: TeyesAdasFrontEvent?
            val count: Int
            val testCount: Int
            val flags: Int
            val sides: Int
            val hadMalformed: Boolean
            synchronized(this) {
                event = latest
                count = frames
                testCount = testFrames
                flags = warningFlags
                sides = directions
                hadMalformed = malformed
                scheduled = false
                latest = null
                frames = 0
                testFrames = 0
                warningFlags = 0
                directions = 0
                malformed = false
            }
            if (event != null) {
                listener!!.onFrontEvent(event, count, flags, sides, testCount)
            }
            if (hadMalformed) listener!!.onMalformedFrontEvent()
        }

        private inner class Callback : Binder() {
            init {
                attachInterface(null, TeyesAdasBinder.CALLBACK_DESCRIPTOR)
            }

            @Throws(RemoteException::class)
            protected override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code < 1 || code > 8) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(TeyesAdasBinder.CALLBACK_DESCRIPTOR)
                if (code == 1 && !closed) {
                    try {
                        val event = TeyesAdasBinder.readFrontEvent(data)
                        if (event != null) record(event)
                    } catch (e: IllegalArgumentException) {
                        record(null)
                    }
                }
                // 2 is the rear camera; 3–8 are informational. isAdasRunning() gives
                // the recognizer status independently of cached or demo callback data.
                if (reply != null) reply.writeNoException()
                return true
            }
        }
    }

    companion object {
        const val PACKAGE: String = "com.teyes.adas"
        private val COMPONENT: ComponentName =
            ComponentName(TeyesAdasClient.PACKAGE, "com.spd.teyes.AdasService")
        private val RPC: Executor? =
            Executors.newSingleThreadExecutor({ r -> Thread(r, "TeyesAdasBinder") })

        private fun reason(message: String?, e: Exception): String? {
            val detail = e.message
            return message.toString() +
                " (" +
                (if (detail == null) e.javaClass.getSimpleName() else detail) +
                ")"
        }
    }
}
