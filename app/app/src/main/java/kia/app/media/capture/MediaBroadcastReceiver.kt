package kia.app.media.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kia.app.media.domain.MediaFeature

class MediaBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (!"kia.app.MEDIA_STATE".equals(intent.getAction())) return
        val source: String? = first(intent, "source", "media_source", "app")
        val pkg: String? = first(intent, "package", "pkg", "packageName")
        val artist: String? = first(intent, "artist", "author", "subtitle")
        val title: String? = first(intent, "title", "track", "text")
        MediaFeature.get(context)
            .report(source, pkg!!, artist, title, intent.getLongExtra("duration", -1L))
    }

    private fun first(intent: Intent, vararg keys: String?): String? {
        for (key: String? in keys) {
            val value: String? = intent.getStringExtra(key)
            if (value != null && !value.trim().isEmpty()) return value
        }
        return null
    }
}
