package kia.app.media.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import kia.app.media.domain.MediaFeature;

public final class MediaBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!"kia.app.MEDIA_STATE".equals(intent.getAction())) return;
        String source = first(intent, "source", "media_source", "app");
        String pkg = first(intent, "package", "pkg", "packageName");
        String artist = first(intent, "artist", "author", "subtitle");
        String title = first(intent, "title", "track", "text");
        MediaFeature.get(context).report(source, pkg, artist, title, intent.getLongExtra("duration", -1L));
    }

    private String first(Intent intent, String... keys) {
        for (String key : keys) {
            String value = intent.getStringExtra(key);
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }
}
