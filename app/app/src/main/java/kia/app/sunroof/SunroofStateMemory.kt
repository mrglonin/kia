package kia.app.sunroof

import android.content.Context
import android.content.SharedPreferences
import kia.app.core.AppLog

/** Last confirmed roof position, independent of USB availability and presentation state. */
object SunroofStateMemory {
    private const val PREFS: String = "kia_sunroof_state"
    private const val KEY_KNOWN: String = "roof_known"
    private const val KEY_OPEN: String = "roof_open"
    private const val KEY_SOURCE: String = "roof_source"
    private const val KEY_RECORDED_AT: String = "roof_recorded_at"
    private const val KEY_ACKNOWLEDGED: String = "warning_acknowledged"
    private const val REAL_SOURCE: String = "0x76"

    @JvmStatic
    @kotlin.jvm.Synchronized
    fun snapshot(context: Context): SunroofStateMemory.Snapshot {
        val values: Map<String?, *>? = SunroofStateMemory.prefs(context)!!.getAll()
        val known: Boolean =
            true.equals(values!!.get(SunroofStateMemory.KEY_KNOWN)) &&
                values.get(SunroofStateMemory.KEY_OPEN) is Boolean &&
                SunroofStateMemory.REAL_SOURCE.equals(values.get(SunroofStateMemory.KEY_SOURCE))
        return SunroofStateMemory.Snapshot(
            known,
            known && true.equals(values.get(SunroofStateMemory.KEY_OPEN)),
            if (known) SunroofStateMemory.REAL_SOURCE else "",
            (if (values.get(SunroofStateMemory.KEY_RECORDED_AT) is Long)
                values.get(SunroofStateMemory.KEY_RECORDED_AT) as Long?
            else 0L)!!,
            known && true.equals(values.get(SunroofStateMemory.KEY_ACKNOWLEDGED)),
        )
    }

    @JvmStatic
    /** Called only after a real 0x76 packet has passed the production router's ordering checks. */
    @kotlin.jvm.Synchronized
    fun record(context: Context, open: Boolean, source: String?) {
        if (!SunroofStateMemory.REAL_SOURCE.equals(source)) return
        val previous: SunroofStateMemory.Snapshot = SunroofStateMemory.snapshot(context)
        if (previous.known && previous.open == open) return
        val committed: Boolean =
            SunroofStateMemory.prefs(context)!!.edit()
                .putBoolean(SunroofStateMemory.KEY_KNOWN, true)
                .putBoolean(SunroofStateMemory.KEY_OPEN, open)
                .putString(SunroofStateMemory.KEY_SOURCE, SunroofStateMemory.REAL_SOURCE)
                .putLong(SunroofStateMemory.KEY_RECORDED_AT, System.currentTimeMillis())
                .putBoolean(SunroofStateMemory.KEY_ACKNOWLEDGED, false)
                .commit()
        if (!committed) AppLog.line(context, "Sunroof memory: position could not be saved")
    }

    @JvmStatic
    /** Acknowledging a warning never writes or clears the roof's actual position. */
    @kotlin.jvm.Synchronized
    fun acknowledge(context: Context) {
        val previous: SunroofStateMemory.Snapshot = SunroofStateMemory.snapshot(context)
        if (!previous.known || !previous.open || previous.acknowledged) return
        if (
            !SunroofStateMemory.prefs(context)!!.edit()
                .putBoolean(SunroofStateMemory.KEY_ACKNOWLEDGED, true)
                .commit()
        ) {
            AppLog.line(context, "Sunroof memory: acknowledgement could not be saved")
        }
    }

    @JvmStatic
    /** Only a confirmed ACC ON begins the next ignition cycle. Unknown ACC is ignored. */
    @kotlin.jvm.Synchronized
    fun observeAcc(context: Context, known: Boolean, on: Boolean) {
        if (!known || !on || !SunroofStateMemory.snapshot(context).acknowledged) return
        if (
            !SunroofStateMemory.prefs(context)!!.edit()
                .putBoolean(SunroofStateMemory.KEY_ACKNOWLEDGED, false)
                .commit()
        ) {
            AppLog.line(context, "Sunroof memory: ignition cycle could not be saved")
        }
    }

    private fun prefs(context: Context): SharedPreferences? {
        return context.getSharedPreferences(SunroofStateMemory.PREFS, Context.MODE_PRIVATE)
    }

    class Snapshot
    internal constructor(
        known: Boolean,
        open: Boolean,
        source: String?,
        recordedAt: Long,
        acknowledged: Boolean,
    ) {
        @JvmField val known: Boolean
        @JvmField val open: Boolean
        @JvmField val source: String?
        @JvmField val recordedAt: Long
        @JvmField val acknowledged: Boolean

        init {
            this.known = known
            this.open = open
            this.source = source
            this.recordedAt = recordedAt
            this.acknowledged = acknowledged
        }
    }
}
