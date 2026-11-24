package kia.app.amp

import android.content.Context
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.AmpState
import kia.app.core.settings.AppSettings
import kia.app.protocol.adapter.AdapterCommand
import kia.app.protocol.adapter.AdapterGateway
import kia.app.protocol.adapter.AdapterProtocol

class AmpController private constructor(context: Context) {
    private val app: Context
    private var state: AmpState = AmpState.empty()

    init {
        this.app = context.applicationContext ?: context
    }

    fun state(): AmpState {
        return state
    }

    fun requestSettings() {
        if (!AppSettings.ampEnabled(app)) {
            AppLog.line(app, "AMP: disabled, request skipped")
            return
        }
        AdapterGateway.get(app)
            .send(AdapterCommand.loud("AMP request", AdapterProtocol.requestAmpSettings()))
        AppLog.line(app, "AMP: request settings")
    }

    fun setSettings(value: AmpState?, save: Boolean) {
        if (!AppSettings.ampEnabled(app)) {
            AppLog.line(app, "AMP: disabled, send skipped")
            return
        }
        val next = value ?: state
        state = next
        StateStore.setAmp(app, state)
        AdapterGateway.get(app)
            .send(
                AdapterCommand.loud(
                    "AMP set",
                    AdapterProtocol.ampSettings(
                        next.volume,
                        next.balance,
                        next.fader,
                        next.bass,
                        next.mid,
                        next.treble,
                        next.mode,
                        save,
                    ),
                )
            )
        AppLog.line(app, "AMP: send " + state.summary())
    }

    fun handleFrame(frame: ByteArray?) {
        if (frame == null || frame.size < 13) return
        val mode: Int = if (frame.size > 5) frame.get(5).toInt() and 0xff else 0
        if (mode != 0x00 && mode != 0x01 && mode != 0x02) return
        val volume: Int = u8(frame, 6, state.volume)
        val balance: Int = u8(frame, 7, state.balance)
        val fader: Int = u8(frame, 8, state.fader)
        val bass: Int = u8(frame, 9, state.bass)
        val mid: Int = u8(frame, 10, state.mid)
        val treble: Int = u8(frame, 11, state.treble)
        val ampMode: Int = u8(frame, 12, state.mode)
        state =
            AmpState(volume, balance, fader, bass, mid, treble, ampMode, System.currentTimeMillis())
        StateStore.setAmp(app, state)
        AppLog.line(app, "AMP: received " + state.summary())
    }

    private fun u8(frame: ByteArray, offset: Int, fallback: Int): Int {
        return if (frame.size > offset) frame.get(offset).toInt() and 0xff else fallback
    }

    companion object {
        private var instance: AmpController? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): AmpController {
            if (AmpController.instance == null) AmpController.instance = AmpController(context)
            return AmpController.instance!!
        }
    }
}
