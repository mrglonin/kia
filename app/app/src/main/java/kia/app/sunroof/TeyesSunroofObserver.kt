package kia.app.sunroof

import android.content.Context
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kia.app.core.AppLog

/**
 * Optional, read-only observer for the native TEYES ACC interface.
 *
 * The retained framework sends common command 5 before its sleep timer; arg1 is willPowerOff (1 =
 * ACC off). Command 8, getPowerState(), Android screen state and USB connection state cannot
 * substitute for that event. Native classes are optional and are never packaged or instantiated
 * here. Access can be unavailable on other firmware.
 */
class TeyesSunroofObserver
internal constructor(
    context: Context?,
    listener: kia.app.sunroof.TeyesSunroofObserver.Listener?,
    serviceLocator: ServiceLocator?,
) {
    interface Listener {
        fun onAcc(known: Boolean, on: Boolean, receivedElapsed: Long, source: String?)

        fun onHardwareAction(keyCode: Int, action: Int)

        fun onSleep(asleep: Boolean) {}
    }

    internal fun interface ServiceLocator {
        @Throws(ReflectiveOperationException::class) fun findService(): IBinder?
    }

    private val app: Context
    private val listener: kia.app.sunroof.TeyesSunroofObserver.Listener
    private val serviceLocator: ServiceLocator
    private val main: Handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService =
        ThreadPoolExecutor(
            0,
            1,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            { runnable ->
                val thread: Thread = Thread(runnable, "kia-sunroof-teyes-observer")
                thread.setDaemon(true)
                thread
            },
        )
    private val signalOrder: AtomicLong = AtomicLong()
    @kotlin.concurrent.Volatile private var running: Boolean = false
    @kotlin.concurrent.Volatile private var generation: Long = 0
    @kotlin.concurrent.Volatile private var status: String? = "TEYES ACC: наблюдение выключено"
    private val callbackToken: AtomicLong = AtomicLong()

    // Main-thread state. In-flight work always rechecks its generation before delivery.
    private var pendingPoll: Runnable? = null
    private var deliveredOrder: Long = 0
    private var stateDelivered: Boolean = false
    private var lastKnown: Boolean = false
    private var lastKnownToken: Long = 0
    private var lastErrorLogElapsed: Long = -TeyesSunroofObserver.ERROR_LOG_INTERVAL_MS

    // Worker-thread state. Registration/reads/unregistration never run on the UI thread.
    private var bindingGeneration: Long = 0
    private var bindingToken: Long = 0
    private var nextCallbackToken: Long = 0
    private var service: TeyesSunroofBinder? = null
    private var callback: CallbackBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    constructor(
        context: Context,
        listener: kia.app.sunroof.TeyesSunroofObserver.Listener,
    ) : this(context, listener, ServiceLocator { TeyesSunroofBinder.findService() })

    init {
        require(!(context == null || listener == null || serviceLocator == null)) {
            "context/listener/serviceLocator"
        }
        val application = context.getApplicationContext()
        this.app = if (application == null) context else application
        this.listener = listener
        this.serviceLocator = serviceLocator
    }

    fun start() {
        onMain(
            Runnable {
                if (running) return@Runnable
                running = true
                val current: Long = ++generation
                stateDelivered = false
                deliveredOrder = 0L
                publishUnknown(current, "TEYES ACC: подключение", "teyes:pending")
                poll(current)
            }
        )
    }

    fun stop() {
        onMain(
            Runnable {
                if (!running) return@Runnable
                val stopped: Long = generation
                running = false
                generation++
                if (pendingPoll != null) main.removeCallbacks(pendingPoll!!)
                pendingPoll = null
                status = "TEYES ACC: наблюдение выключено"
                worker.execute({ clearBinding(stopped) })
            }
        )
    }

    fun statusSummary(): String? {
        return status
    }

    private fun active(current: Long): Boolean {
        return running && generation == current
    }

    private fun poll(current: Long) {
        if (!active(current)) return
        worker.execute({
            if (!active(current)) return@execute
            // Reserve the observation before native calls: an ACC callback received while a
            // read is in flight must supersede both its value and its eventual failure.
            val observationOrder: Long = signalOrder.incrementAndGet()
            try {
                try {
                    ensureBinding(current)
                } catch (error: ReflectiveOperationException) {
                    clearBinding(current)
                    reportUnavailable(current, error, observationOrder)
                    return@execute
                } catch (error: RemoteException) {
                    clearBinding(current)
                    reportUnavailable(current, error, observationOrder)
                    return@execute
                } catch (error: RuntimeException) {
                    clearBinding(current)
                    reportUnavailable(current, error, observationOrder)
                    return@execute
                } catch (error: LinkageError) {
                    clearBinding(current)
                    reportUnavailable(current, error, observationOrder)
                    return@execute
                }
                val observedToken: Long = callbackToken.get()
                if (!active(current) || observedToken == 0L) return@execute
                val acc: Int = service!!.readAcc()
                if (acc == TeyesSunroofSignals.UNKNOWN) {
                    publishUnknown(
                        current,
                        "TEYES ACC: неизвестное значение",
                        "teyes:invalid",
                        observationOrder,
                    )
                } else {
                    publishAcc(
                        current,
                        acc,
                        SystemClock.elapsedRealtime(),
                        TeyesSunroofObserver.BASELINE_SOURCE,
                        observationOrder,
                        observedToken,
                    )
                }
            } catch (error: RemoteException) {
                // An unavailable aggregate must not remove a successfully registered
                // callback: the command-5 ACC channel is independent of baseline reads.
                // Only a dead Binder or an explicit disconnect invalidates that binding.
                if (TeyesSunroofObserver.isDeadBinder(error)) clearBinding(current)
                reportUnavailable(current, error, observationOrder)
            } catch (error: RuntimeException) {
                if (TeyesSunroofObserver.isDeadBinder(error)) clearBinding(current)
                reportUnavailable(current, error, observationOrder)
            } catch (error: LinkageError) {
                if (TeyesSunroofObserver.isDeadBinder(error)) clearBinding(current)
                reportUnavailable(current, error, observationOrder)
            } finally {
                main.post({
                    if (!active(current)) return@post
                    pendingPoll = Runnable { poll(current) }
                    main.postDelayed(pendingPoll!!, TeyesSunroofObserver.POLL_MS)
                })
            }
        })
    }

    @Throws(ReflectiveOperationException::class, RemoteException::class)
    private fun ensureBinding(current: Long) {
        if (
            service != null &&
                bindingGeneration == current &&
                callbackToken.get() == bindingToken &&
                service!!.asBinder()!!.isBinderAlive()
        )
            return
        if (service != null) clearBinding(bindingGeneration)
        val nativeService: TeyesSunroofBinder = TeyesSunroofBinder(serviceLocator.findService()!!)
        val token: Long = ++nextCallbackToken
        val binder: CallbackBinder = CallbackBinder(current, token)
        val death: IBinder.DeathRecipient =
            IBinder.DeathRecipient {
                invalidateBinding(
                    current,
                    token,
                    "TEYES ACC: служба отключена",
                    "teyes:binder-died",
                )
            }
        service = nativeService
        callback = binder
        deathRecipient = death
        bindingGeneration = current
        bindingToken = token
        callbackToken.set(token)
        if (!active(current)) {
            clearBinding(current)
            return
        }
        nativeService.asBinder()!!.linkToDeath(death, 0)
        nativeService.register(app.getPackageName(), binder)
    }

    private fun clearBinding(current: Long) {
        if (service == null || bindingGeneration != current) return
        val oldService: TeyesSunroofBinder = service!!
        val oldDeath = deathRecipient
        service = null
        callback = null
        deathRecipient = null
        bindingGeneration = 0L
        bindingToken = 0L
        callbackToken.set(0L)
        if (oldDeath != null) {
            try {
                oldService.asBinder()!!.unlinkToDeath(oldDeath, 0)
            } catch (ignored: RuntimeException) {
                // Death can race cleanup.
            }
        }
        try {
            oldService.unregister(app.getPackageName())
        } catch (ignored: RemoteException) {
            // Native reconnect/stop is allowed to race unregistration.
        } catch (ignored: RuntimeException) {} catch (ignored: LinkageError) {}
    }

    private fun invalidateBinding(current: Long, token: Long, summary: String?, source: String?) {
        invalidateBinding(current, token, summary, source, signalOrder.incrementAndGet())
    }

    private fun invalidateBinding(
        current: Long,
        token: Long,
        summary: String?,
        source: String?,
        disconnectedOrder: Long,
    ) {
        if (!active(current) || !callbackToken.compareAndSet(token, 0L)) return
        // Fence both newly arriving and already queued callbacks before worker cleanup.
        publishDisconnected(current, token, summary, source, disconnectedOrder)
        worker.execute({
            if (bindingGeneration == current && bindingToken == token) clearBinding(current)
        })
    }

    private fun publishDisconnected(
        current: Long,
        token: Long,
        summary: String?,
        source: String?,
        order: Long,
    ) {
        val receivedElapsed: Long = SystemClock.elapsedRealtime()
        main.post({
            if (!active(current)) return@post
            // A callback may finish between reserving the disconnect order and its CAS.
            // It cannot keep an invalidated binding known, but a new binding's accepted
            // baseline/callback must not be replaced by this old disconnect either.
            if (callbackToken.get() != 0L && (!lastKnown || lastKnownToken != token)) return@post
            deliveredOrder = Math.max(deliveredOrder, order)
            status = summary
            if (stateDelivered && !lastKnown) return@post
            stateDelivered = true
            lastKnown = false
            listener.onAcc(false, false, receivedElapsed, source)
        })
    }

    private fun handleNativeCallback(
        current: Long,
        token: Long,
        name: String?,
        args: kotlin.Array<Any?>?,
    ) {
        if (!active(current) || callbackToken.get() != token) return
        if ("sendCommonToSouce".equals(name) && TeyesSunroofObserver.threeIntegers(args)) {
            val acc: Int =
                TeyesSunroofSignals.callbackAcc(
                    args!!.get(0) as Int,
                    args.get(1) as Int,
                    args.get(2) as Int,
                )
            if (acc != TeyesSunroofSignals.UNKNOWN) {
                val receivedOrder: Long = signalOrder.incrementAndGet()
                publishAcc(
                    current,
                    acc,
                    SystemClock.elapsedRealtime(),
                    TeyesSunroofObserver.CALLBACK_SOURCE,
                    receivedOrder,
                    token,
                )
            }
            val asleep: Int =
                TeyesSunroofSignals.callbackSleep(
                    args.get(0) as Int,
                    args.get(1) as Int,
                    args.get(2) as Int,
                )
            if (asleep != TeyesSunroofSignals.UNKNOWN) {
                main.post({
                    if (active(current) && callbackToken.get() == token)
                        listener.onSleep(asleep == 1)
                })
            }
        } else if ("sendKeyCode".equals(name) && TeyesSunroofObserver.threeIntegers(args)) {
            val keyCode: Int = args!!.get(0) as Int
            val repeatCount: Int = args.get(1) as Int
            val action: Int = args.get(2) as Int
            if (TeyesSunroofSignals.hardwareAction(keyCode, repeatCount, action)) {
                main.post({
                    if (active(current) && callbackToken.get() == token)
                        listener.onHardwareAction(keyCode, action)
                })
            }
        } else if (
            "onServiceConnect".equals(name) &&
                args != null &&
                args.size == 1 &&
                false.equals(args.get(0))
        ) {
            invalidateBinding(current, token, "TEYES ACC: служба отключена", "teyes:disconnected")
        }
    }

    private fun publishAcc(
        current: Long,
        state: Int,
        receivedElapsed: Long,
        source: String?,
        order: Long,
        token: Long,
    ) {
        main.post({
            if (!active(current) || callbackToken.get() != token || order <= deliveredOrder)
                return@post
            deliveredOrder = order
            stateDelivered = true
            lastKnown = true
            lastKnownToken = token
            status =
                if (state == TeyesSunroofSignals.ON) "TEYES ACC: включено"
                else "TEYES ACC: выключено"
            // Identical valid polls still refresh the sample time; consumers detect transitions.
            listener.onAcc(true, state == TeyesSunroofSignals.ON, receivedElapsed, source)
        })
    }

    private fun publishUnknown(current: Long, summary: String?, source: String?) {
        publishUnknown(current, summary, source, signalOrder.incrementAndGet())
    }

    private fun publishUnknown(current: Long, summary: String?, source: String?, order: Long) {
        val receivedElapsed: Long = SystemClock.elapsedRealtime()
        main.post({
            if (!active(current) || order <= deliveredOrder) return@post
            deliveredOrder = order
            status = summary
            if (stateDelivered && !lastKnown) return@post
            stateDelivered = true
            lastKnown = false
            listener.onAcc(false, false, receivedElapsed, source)
        })
    }

    private fun reportUnavailable(current: Long, error: Throwable?, observationOrder: Long) {
        val cause =
            if (error is InvocationTargetException && error.cause != null) error.cause else error
        val reason = cause!!.javaClass.getSimpleName()
        publishUnknown(
            current,
            "TEYES ACC: недоступно (" + reason + ")",
            "teyes:unavailable",
            observationOrder,
        )
        main.post({
            if (!active(current)) return@post
            val now: Long = SystemClock.elapsedRealtime()
            if (now - lastErrorLogElapsed < TeyesSunroofObserver.ERROR_LOG_INTERVAL_MS) return@post
            lastErrorLogElapsed = now
            AppLog.line(app, "Sunroof TEYES ACC unavailable: " + reason)
        })
    }

    private fun onMain(action: Runnable) {
        if (Looper.myLooper() === Looper.getMainLooper()) action.run() else main.post(action)
    }

    /** Verified callback wire format; unknown transaction numbers remain unhandled. */
    internal inner class CallbackBinder(current: Long, token: Long) : Binder() {
        private val current: Long
        private val token: Long

        init {
            this.current = current
            this.token = token
            attachInterface(null, TeyesSunroofObserver.CALLBACK_DESCRIPTOR)
        }

        @Throws(RemoteException::class)
        protected override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(TeyesSunroofObserver.CALLBACK_DESCRIPTOR)
                return true
            }
            if (code < 1 || code > TeyesSunroofSignals.KEY_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(TeyesSunroofObserver.CALLBACK_DESCRIPTOR)
            if (
                code == TeyesSunroofSignals.COMMON_TRANSACTION ||
                    code == TeyesSunroofSignals.KEY_TRANSACTION
            ) {
                requireBytes(data, 12)
                val first: Int = data.readInt()
                val second: Int = data.readInt()
                val third: Int = data.readInt()
                requireEnd(data)
                handleNativeCallback(
                    current,
                    token,
                    if (code == TeyesSunroofSignals.COMMON_TRANSACTION) "sendCommonToSouce"
                    else "sendKeyCode",
                    arrayOf<Any?>(first, second, third),
                )
                if (code == TeyesSunroofSignals.KEY_TRANSACTION && reply != null) {
                    reply.writeNoException()
                    reply.writeInt(-1)
                }
                return true
            }
            if (code == 12) {
                data.readHashMap(javaClass.getClassLoader())
            } else {
                requireBytes(data, if (code == 5 || code == 6 || code == 11) 8 else 4)
                val first: Int = data.readInt()
                if (code == 5 || code == 6 || code == 11) data.readInt()
                requireEnd(data)
                if (code == 1)
                    handleNativeCallback(
                        current,
                        token,
                        "onServiceConnect",
                        arrayOf<Any?>(first != 0),
                    )
                return true
            }
            requireEnd(data)
            return true
        }

        private fun requireEnd(data: Parcel) {
            require(data.dataAvail() == 0) { "Unexpected TEYES callback data" }
        }

        private fun requireBytes(data: Parcel, count: Int) {
            require(!(data.dataAvail() < count)) { "Truncated TEYES callback data" }
        }
    }

    companion object {
        const val CALLBACK_DESCRIPTOR: String = "com.spd.system.aidl.ISpdServiceCallback"
        private const val BASELINE_SOURCE: String = "teyes:DeviceStatusInfo.accStatus"
        private const val CALLBACK_SOURCE: String = "teyes:cmd5"
        private const val POLL_MS: Long = 1000L
        private const val ERROR_LOG_INTERVAL_MS: Long = 30000L

        private fun isDeadBinder(error: Throwable?): Boolean {
            var error: Throwable? = error
            var depth: Int = 0
            while (error != null && depth < 8) {
                if (error is DeadObjectException) return true
                depth++
                error = error.cause
            }
            return false
        }

        private fun threeIntegers(values: kotlin.Array<Any?>?): Boolean {
            return values != null &&
                values.size == 3 &&
                values.get(0) is Int &&
                values.get(1) is Int &&
                values.get(2) is Int
        }
    }
}
