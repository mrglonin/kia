package kia.app.media.cluster;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import kia.app.core.settings.AppSettings;
import kia.app.core.model.CallState;
import kia.app.core.model.MediaState;
import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.protocol.adapter.MediaSourceKind;

public final class MediaClusterSender {
    private static final long TRACK_SWAP_DELAY_MS = 2500L;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastMediaKey = "";
    private String lastSourceKey = "";
    private Runnable pendingTrackText;

    public MediaClusterSender(Context context) {
        this.app = context.getApplicationContext();
    }

    public synchronized void send(MediaState state) {
        if (state == null) return;
        MediaSourceKind kind = effectiveKind(state);
        AdapterGateway gateway = AdapterGateway.get(app);
        boolean radioLike = isRadioKind(kind);
        String source = TextUtils.isEmpty(state.source) ? kind.defaultLabel() : state.source;
        String artist = cleanDisplay(state.artist);
        String title = cleanDisplay(state.title);
        int textMode = AppSettings.mediaTextMode(app);
        String firstText;
        String trackText;
        if (radioLike) {
            firstText = radioHeaderText(source, artist, title);
            trackText = firstText;
        } else if (textMode == AppSettings.MEDIA_TEXT_TRACK_ONLY) {
            firstText = firstNonEmpty(title, artist, source);
            trackText = firstText;
        } else {
            firstText = firstNonEmpty(artist, title, source);
            trackText = firstNonEmpty(title, artist, source);
        }
        String sourceKey = sourceKey(kind, source);
        String mediaKey = sourceKey + "|" + state.packageName + "|" + artist + "|" + title
                + "|" + state.playing + "|text=" + textMode;
        boolean sameMedia = TextUtils.equals(mediaKey, lastMediaKey);
        boolean sendSource = !sameMedia || !TextUtils.equals(sourceKey, lastSourceKey);

        if (radioLike) {
            if (sendSource) {
                if (!TextUtils.equals(sourceKey, lastSourceKey)) {
                    sendMediaOff(gateway);
                }
                gateway.send(AdapterCommand.quiet("radio source", radioSourceStatus(kind, artist, title)));
                rememberSource(sourceKey);
            }
            if (!sameMedia && kind != MediaSourceKind.AM_RADIO) {
                sendTextSequence(gateway, AdapterProtocol.CMD_RADIO_TEXT, mediaKey, firstText, trackText);
            }
            return;
        }

        if (sendSource) {
            sendMediaOff(gateway);
            if (!textOnlySource(kind)) {
                gateway.send(AdapterCommand.quiet("media source status",
                        AdapterProtocol.mediaSourceStatus(kind, title)));
            }
            rememberSource(sourceKey);
        }
        if (!sameMedia) sendTextSequence(gateway, textCommand(kind, state), mediaKey, firstText, trackText);
    }

    public synchronized void sendSourceOnly(MediaState state) {
        if (state == null) return;
        MediaSourceKind kind = effectiveKind(state);
        String source = TextUtils.isEmpty(state.source) ? kind.defaultLabel() : state.source;
        String sourceKey = sourceKey(kind, source);
        String mediaKey = sourceKey + "|" + state.packageName + "|source-only|" + state.playing;
        boolean sameMedia = TextUtils.equals(mediaKey, lastMediaKey);
        boolean sendSource = !sameMedia || !TextUtils.equals(sourceKey, lastSourceKey);
        if (!sendSource) return;
        lastMediaKey = mediaKey;
        cancelPendingTrackText();
        AdapterGateway gateway = AdapterGateway.get(app);
        sendMediaOff(gateway);
        if (textOnlySource(kind)) {
            gateway.send(AdapterCommand.quiet("media source text only",
                    AdapterProtocol.textPacket(textCommand(kind, state), source)));
        } else {
            gateway.send(AdapterCommand.quiet("media source only",
                    radioLikeSource(kind)
                            ? radioSourceStatus(kind, "", "")
                            : AdapterProtocol.mediaSourceStatus(kind, source)));
        }
        rememberSource(sourceKey);
    }

    public synchronized void sendRadioSearch(MediaState state) {
        if (state == null) return;
        MediaSourceKind kind = effectiveKind(state);
        if (!radioLikeSource(kind)) return;
        String source = TextUtils.isEmpty(state.source) ? kind.defaultLabel() : state.source;
        String frequency = cleanDisplay(state.artist);
        if (TextUtils.isEmpty(frequency) && kind != MediaSourceKind.AM_RADIO) return;
        String sourceKey = sourceKey(kind, source);
        String mediaKey = sourceKey + "|" + state.packageName + "|radio-search|" + frequency;
        boolean sameMedia = TextUtils.equals(mediaKey, lastMediaKey);
        boolean sameSource = TextUtils.equals(sourceKey, lastSourceKey);
        boolean wasSearch = lastMediaKey.contains("|radio-search|");
        if (sameMedia) return;
        lastMediaKey = mediaKey;
        cancelPendingTrackText();
        AdapterGateway gateway = AdapterGateway.get(app);
        if (kind == MediaSourceKind.AM_RADIO) {
            if (!sameSource || !wasSearch) {
                gateway.send(AdapterCommand.quiet("radio search text",
                        AdapterProtocol.textPacket(AdapterProtocol.CMD_RADIO_TEXT,
                                radioSearchText(source, frequency))));
            }
            rememberSource(sourceKey);
            return;
        }
        if (!sameSource) sendMediaOff(gateway);
        gateway.send(AdapterCommand.quiet("radio search source",
                radioSourceStatus(kind, frequency, "")));
        if (!sameSource || !wasSearch) {
            gateway.send(AdapterCommand.quiet("radio search text",
                    AdapterProtocol.textPacket(AdapterProtocol.CMD_RADIO_TEXT,
                            radioSearchText(source, frequency))));
        }
        rememberSource(sourceKey);
    }

    public synchronized void sendCall(CallState call) {
        if (call == null || !call.active) return;
        cancelPendingTrackText();
        AdapterGateway gateway = AdapterGateway.get(app);
        int callSource = AppSettings.callSourceMode(app);
        MediaSourceKind kind = callSourceKind(callSource);
        String sourceKey = sourceKey(kind, AppSettings.callSourceLabel(app));
        String line = cleanDisplay(call.clusterLine(System.currentTimeMillis()));
        String mediaKey = sourceKey + "|call|" + line;
        boolean sameMedia = TextUtils.equals(mediaKey, lastMediaKey);
        boolean sameSource = TextUtils.equals(sourceKey, lastSourceKey);
        if (!sameSource) {
            gateway.send(AdapterCommand.loud("call media off before " + AppSettings.callSourceLabel(app),
                    AdapterProtocol.mediaOffStatus()));
            byte[] sourceStatus = callSourceStatus(callSource);
            if (sourceStatus != null) {
                gateway.send(AdapterCommand.loud("call " + AppSettings.callSourceLabel(app) + " source",
                        sourceStatus));
            }
            rememberSource(sourceKey);
        }
        if (!sameMedia) {
            lastMediaKey = mediaKey;
            gateway.send(AdapterCommand.loud("call text",
                    AdapterProtocol.textPacket(callTextCommand(callSource), line)));
        }
    }

    public synchronized void clearCall(CallState previous) {
        cancelPendingTrackText();
        lastMediaKey = "";
        lastSourceKey = "";
        AdapterGateway.get(app).send(AdapterCommand.loud("call release media off",
                AdapterProtocol.mediaOffStatus()));
    }

    private void sendTextSequence(AdapterGateway gateway, int command, String mediaKey,
                                  String firstText, String trackText) {
        lastMediaKey = mediaKey;
        cancelPendingTrackText();
        if (TextUtils.isEmpty(firstText)) return;
        gateway.send(AdapterCommand.quiet("media text first",
                AdapterProtocol.textPacket(command, firstText)));
        if (TextUtils.isEmpty(trackText) || TextUtils.equals(firstText, trackText)) return;
        pendingTrackText = () -> {
            synchronized (MediaClusterSender.this) {
                if (!TextUtils.equals(mediaKey, lastMediaKey)) return;
                AdapterGateway.get(app).send(AdapterCommand.quiet("media text track",
                        AdapterProtocol.textPacket(command, trackText)));
            }
        };
        handler.postDelayed(pendingTrackText, TRACK_SWAP_DELAY_MS);
    }

    private void cancelPendingTrackText() {
        if (pendingTrackText == null) return;
        handler.removeCallbacks(pendingTrackText);
        pendingTrackText = null;
    }

    private void rememberSource(String sourceKey) {
        lastSourceKey = sourceKey;
    }

    private static void sendMediaOff(AdapterGateway gateway) {
        gateway.send(AdapterCommand.quiet("media off before source", AdapterProtocol.mediaOffStatus()));
    }

    private static int textCommand(MediaSourceKind kind, MediaState state) {
        if (isRadioKind(kind)) {
            return AdapterProtocol.CMD_RADIO_TEXT;
        }
        if (kind == MediaSourceKind.USB_MUSIC) {
            return AdapterProtocol.CMD_USB_TEXT;
        }
        if (kind == MediaSourceKind.ANDROID_AUTO) {
            return AdapterProtocol.CMD_ANDROID_AUTO_TEXT;
        }
        if (kind == MediaSourceKind.CARPLAY) {
            return AdapterProtocol.CMD_CARPLAY_TEXT;
        }
        if (kind == MediaSourceKind.MY_MUSIC) {
            return AdapterProtocol.CMD_MY_MUSIC_TEXT;
        }
        if (kind == MediaSourceKind.BLUETOOTH_AUDIO || kind == MediaSourceKind.BT_PHONE) {
            return AdapterProtocol.CMD_MEDIA_TEXT;
        }
        return AdapterProtocol.CMD_ANDROID_AUTO_TEXT;
    }

    private static byte[] callSourceStatus(int mode) {
        switch (mode) {
            case AppSettings.CALL_SOURCE_ANDROID_AUTO:
                return null;
            case AppSettings.CALL_SOURCE_BLUETOOTH:
                return AdapterProtocol.mediaSourceStatus(MediaSourceKind.BLUETOOTH_AUDIO, "BT Audio");
            case AppSettings.CALL_SOURCE_MY_MUSIC:
                return null;
            case AppSettings.CALL_SOURCE_USB:
                return AdapterProtocol.mediaSourceStatus(MediaSourceKind.USB_MUSIC, "USB Music");
            case AppSettings.CALL_SOURCE_FM:
                return AdapterProtocol.mediaFmSourceStatusNoFrequency();
            case AppSettings.CALL_SOURCE_CARPLAY:
            default:
                return null;
        }
    }

    private static int callTextCommand(int mode) {
        switch (mode) {
            case AppSettings.CALL_SOURCE_ANDROID_AUTO:
                return AdapterProtocol.CMD_ANDROID_AUTO_TEXT;
            case AppSettings.CALL_SOURCE_BLUETOOTH:
                return AdapterProtocol.CMD_MEDIA_TEXT;
            case AppSettings.CALL_SOURCE_MY_MUSIC:
                return AdapterProtocol.CMD_MY_MUSIC_TEXT;
            case AppSettings.CALL_SOURCE_USB:
                return AdapterProtocol.CMD_USB_TEXT;
            case AppSettings.CALL_SOURCE_FM:
                return AdapterProtocol.CMD_RADIO_TEXT;
            case AppSettings.CALL_SOURCE_CARPLAY:
            default:
                return AdapterProtocol.CMD_CARPLAY_TEXT;
        }
    }

    private static MediaSourceKind callSourceKind(int mode) {
        switch (mode) {
            case AppSettings.CALL_SOURCE_ANDROID_AUTO:
                return MediaSourceKind.ANDROID_AUTO;
            case AppSettings.CALL_SOURCE_BLUETOOTH:
                return MediaSourceKind.BLUETOOTH_AUDIO;
            case AppSettings.CALL_SOURCE_MY_MUSIC:
                return MediaSourceKind.MY_MUSIC;
            case AppSettings.CALL_SOURCE_USB:
                return MediaSourceKind.USB_MUSIC;
            case AppSettings.CALL_SOURCE_FM:
                return MediaSourceKind.FM_RADIO;
            case AppSettings.CALL_SOURCE_CARPLAY:
            default:
                return MediaSourceKind.CARPLAY;
        }
    }

    private static boolean isRadioKind(MediaSourceKind kind) {
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO;
    }

    private static boolean radioLikeSource(MediaSourceKind kind) {
        return isRadioKind(kind);
    }

    private static boolean textOnlySource(MediaSourceKind kind) {
        return kind == MediaSourceKind.ANDROID_AUTO
                || kind == MediaSourceKind.CARPLAY
                || kind == MediaSourceKind.MY_MUSIC;
    }

    private static byte[] radioSourceStatus(MediaSourceKind kind, String artist, String title) {
        if (kind == MediaSourceKind.FM_RADIO) {
            String frequency = firstFrequency(artist, title);
            return TextUtils.isEmpty(frequency)
                    ? AdapterProtocol.mediaFmSourceStatusNoFrequency()
                    : AdapterProtocol.mediaSourceStatus(kind, frequency);
        }
        return AdapterProtocol.mediaSourceStatus(kind, firstNonEmpty(artist, title));
    }

    private static String sourceKey(MediaSourceKind kind, String source) {
        return kind.name() + "|" + source;
    }

    private static String radioHeaderText(String source, String artist, String title) {
        String station = firstNonEmpty(nonFrequency(title), nonFrequency(artist));
        if (!TextUtils.isEmpty(station)) return station;
        return firstNonEmpty(firstFrequency(artist, title), source);
    }

    private static String radioSearchText(String source, String frequency) {
        String label = TextUtils.isEmpty(source) ? "Radio" : source;
        return TextUtils.isEmpty(frequency) ? label + " поиск" : label + " поиск";
    }

    private static String firstFrequency(String first, String second) {
        if (looksLikeOnlyFrequency(first)) return first;
        if (looksLikeOnlyFrequency(second)) return second;
        return "";
    }

    private static String nonFrequency(String value) {
        return looksLikeOnlyFrequency(value) ? "" : value;
    }

    private static boolean looksLikeOnlyFrequency(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String text = value.trim();
        return text.matches("(?i)^(FM|AM)?\\s*\\d{2,4}([\\.,]\\d{1,2})?\\s*(MHz|kHz)?$");
    }

    private static boolean isTeyesOnline(MediaState state) {
        if (state == null || TextUtils.isEmpty(state.source)) return false;
        return "TEYES".equalsIgnoreCase(state.source.trim());
    }

    private MediaSourceKind effectiveKind(MediaState state) {
        MediaSourceKind kind = isTeyesOnline(state)
                ? MediaSourceKind.MY_MUSIC
                : MediaSourceKind.from(state.source, state.packageName);
        if (kind != MediaSourceKind.CLOUD_MUSIC && kind != MediaSourceKind.GENERIC_MUSIC) return kind;
        switch (AppSettings.otherMediaSourceMode(app)) {
            case AppSettings.OTHER_SOURCE_MY_MUSIC:
                return MediaSourceKind.MY_MUSIC;
            case AppSettings.OTHER_SOURCE_CARPLAY:
                return MediaSourceKind.CARPLAY;
            case AppSettings.OTHER_SOURCE_ANDROID:
            default:
                return kind;
        }
    }

    private static String cleanDisplay(String value) {
        if (TextUtils.isEmpty(value) || "<unknown>".equalsIgnoreCase(value.trim())) return "";
        return value.trim();
    }

    private static String firstNonEmpty(String first, String second) {
        return TextUtils.isEmpty(first) ? second : first;
    }

    private static String firstNonEmpty(String first, String second, String third) {
        if (!TextUtils.isEmpty(first)) return first;
        if (!TextUtils.isEmpty(second)) return second;
        return third;
    }
}
