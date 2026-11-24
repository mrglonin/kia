package kia.app.media.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.media.domain.CallFeature

class BluetoothCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (!BluetoothCallReceiver.ACTION_CALL_CHANGED.equals(intent.getAction())) return
        val call: ParsedCall? = BluetoothCallReceiver.parseCall(context, intent)
        if (call == null) {
            AppLog.line(
                context,
                "HFP call: empty call extra " + BluetoothCallReceiver.extraKeys(intent),
            )
            return
        }
        if (call.ended()) {
            val key: String = call.key()!!
            if (!TextUtils.equals(key, BluetoothCallReceiver.lastEndedKey)) {
                BluetoothCallReceiver.lastEndedKey = key
                BluetoothCallReceiver.lastActiveKey = ""
                CallFeature.get(context).reportEnded("hfp " + call.stateLabel)
            }
            return
        }
        val snapshot: SpdBluetoothServiceClient.CallSnapshot? =
            SpdBluetoothServiceClient.get(context).readCallSnapshot()
        val name: String? = if (snapshot != null && snapshot.active()) snapshot.name else ""
        val number: String? = if (snapshot != null && snapshot.active()) snapshot.number else ""
        val key: String =
            call.key().toString() +
                "|" +
                BluetoothCallReceiver.clean(name) +
                "|" +
                BluetoothCallReceiver.clean(number)
        if (TextUtils.equals(key, BluetoothCallReceiver.lastActiveKey) && StateStore.call().active)
            return
        BluetoothCallReceiver.lastActiveKey = key
        BluetoothCallReceiver.lastEndedKey = ""
        AppLog.line(
            context,
            ("HFP call: " +
                call.stateLabel +
                " " +
                BluetoothCallReceiver.firstNonEmpty(
                    BluetoothCallReceiver.clean(name),
                    BluetoothCallReceiver.clean(number),
                )),
        )
        CallFeature.get(context).reportActive(name, number, "HFP " + call.stateLabel)
    }

    private class ParsedCall(state: String?, uuid: String?) {
        @JvmField val stateLabel: String?
        @JvmField val uuid: String?

        init {
            this.stateLabel = BluetoothCallReceiver.clean(state)!!.uppercase(Locale.US)
            this.uuid = BluetoothCallReceiver.clean(uuid)
        }

        fun ended(): Boolean {
            return stateLabel!!.contains(BluetoothCallReceiver.STATE_TERMINATED)
        }

        fun key(): String? {
            return stateLabel.toString() + "|" + uuid
        }
    }

    companion object {
        const val ACTION_CALL_CHANGED: String =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"

        private val EXTRA_CALL: String = "android.bluetooth.headsetclient.extra.CALL"
        private val STATE_TERMINATED: String = "TERMINATED"
        private var lastActiveKey: String = ""
        private var lastEndedKey: String = ""

        @JvmStatic
        fun addActions(filter: android.content.IntentFilter?) {
            if (filter != null) filter.addAction(BluetoothCallReceiver.ACTION_CALL_CHANGED)
        }

        private fun parseCall(context: Context?, intent: Intent): ParsedCall? {
            val raw: Any? = BluetoothCallReceiver.callExtra(intent)
            if (raw == null) return null
            val rawText: String? = raw.toString()
            var state: String? = BluetoothCallReceiver.parseBetween(rawText, "mState: ", ",")
            val uuid: String? = BluetoothCallReceiver.parseBetween(rawText, "mUUID: ", ",")
            if (TextUtils.isEmpty(state)) {
                AppLog.line(context, "HFP call: state parse failed " + raw)
                state = "UNKNOWN"
            }
            return ParsedCall(state, uuid)
        }

        private fun callExtra(intent: Intent): Any? {
            try {
                val extras: Bundle? = intent.getExtras()
                return if (extras == null) null else extras.get(BluetoothCallReceiver.EXTRA_CALL)
            } catch (ignored: Exception) {
                return null
            }
        }

        private fun parseBetween(value: String?, start: String, end: String?): String? {
            if (value == null) return ""
            var from: Int = value.indexOf(start)
            if (from < 0) return ""
            from += start.length
            var to: Int = value.indexOf(end!!, from)
            if (to < 0) to = value.length
            return BluetoothCallReceiver.clean(value.substring(from, to))
        }

        private fun firstNonEmpty(first: String?, second: String?): String? {
            return if (TextUtils.isEmpty(first)) second else first
        }

        private fun clean(value: String?): String? {
            if (value == null) return ""
            return value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
        }

        private fun extraKeys(intent: Intent): String? {
            try {
                val extras: Bundle? = intent.getExtras()
                return if (extras == null) "[]" else extras.keySet().toString()
            } catch (ignored: Exception) {
                return "unreadable"
            }
        }
    }
}
