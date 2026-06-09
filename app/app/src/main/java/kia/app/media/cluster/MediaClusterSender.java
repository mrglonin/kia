package kia.app.media.cluster;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import kia.app.core.StateStore;
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

        if (AppSettings.uartRealMediaProfile(app)) {
            if (!sameMedia) {
                sendTextSequence(gateway, textCommand(kind, state), mediaKey, firstText, trackText);
            }
            rememberSource(sourceKey);
            return;
        }

        if (radioLike) {
            if (sendSource) {
                if (!TextUtils.equals(sourceKey, lastSourceKey)) {
                    sendMediaOff(gateway);
                }
                gateway.send(quiet("Источник 0x7A радио " + firstNonEmpty(artist, title, source),
                        radioSourceStatus(kind, artist, title)));
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
                gateway.send(quiet("Источник 0x7A " + kind.defaultLabel(),
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
        if (AppSettings.uartRealMediaProfile(app)) {
            String line = firstNonEmpty(cleanDisplay(state.title), cleanDisplay(state.artist), source);
            if (!TextUtils.isEmpty(line)) {
                gateway.send(quiet(textTxLabel(textCommand(kind, state), "source", line),
                        AdapterProtocol.textPacket(textCommand(kind, state), line)));
            }
            rememberSource(sourceKey);
            return;
        }
        sendMediaOff(gateway);
        if (textOnlySource(kind)) {
            gateway.send(quiet(textTxLabel(textCommand(kind, state), "source", source),
                    AdapterProtocol.textPacket(textCommand(kind, state), source)));
        } else {
            gateway.send(quiet("Источник 0x7A " + source,
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
                gateway.send(quiet(textTxLabel(AdapterProtocol.CMD_RADIO_TEXT, "search",
                        radioSearchText(source, frequency)),
                        AdapterProtocol.textPacket(AdapterProtocol.CMD_RADIO_TEXT,
                                radioSearchText(source, frequency))));
            }
            rememberSource(sourceKey);
            return;
        }
        if (!sameSource) sendMediaOff(gateway);
        gateway.send(quiet("Источник 0x7A поиск радио " + frequency,
                radioSourceStatus(kind, frequency, "")));
        if (!sameSource || !wasSearch) {
            gateway.send(quiet(textTxLabel(AdapterProtocol.CMD_RADIO_TEXT, "search",
                    radioSearchText(source, frequency)),
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
            gateway.send(loud("Звонок: сброс media перед " + AppSettings.callSourceLabel(app),
                    AdapterProtocol.mediaOffStatus()));
            byte[] sourceStatus = callSourceStatus(callSource);
            if (sourceStatus != null) {
                gateway.send(loud("Звонок: источник " + AppSettings.callSourceLabel(app),
                        sourceStatus));
            }
            rememberSource(sourceKey);
        }
        if (!sameMedia) {
            lastMediaKey = mediaKey;
            gateway.send(loud(textTxLabel(callTextCommand(callSource), "call", line),
                    AdapterProtocol.textPacket(callTextCommand(callSource), line)));
        }
    }

    public synchronized void clearCall(CallState previous) {
        cancelPendingTrackText();
        lastMediaKey = "";
        lastSourceKey = "";
        AdapterGateway.get(app).send(loud("Звонок: завершение, media off",
                AdapterProtocol.mediaOffStatus()));
    }

    private void sendTextSequence(AdapterGateway gateway, int command, String mediaKey,
                                  String firstText, String trackText) {
        lastMediaKey = mediaKey;
        cancelPendingTrackText();
        if (TextUtils.isEmpty(firstText)) return;
        gateway.send(quiet(textTxLabel(command, "first", firstText),
                AdapterProtocol.textPacket(command, firstText)));
        if (TextUtils.isEmpty(trackText) || TextUtils.equals(firstText, trackText)) return;
        pendingTrackText = () -> {
            synchronized (MediaClusterSender.this) {
                if (!TextUtils.equals(mediaKey, lastMediaKey)) return;
                AdapterGateway.get(app).send(quiet(textTxLabel(command, "track", trackText),
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

    private void sendMediaOff(AdapterGateway gateway) {
        gateway.send(quiet("Сброс media source 0x7A", AdapterProtocol.mediaOffStatus()));
    }

    private AdapterCommand quiet(String label, byte[] frame) {
        recordTx(label, frame);
        return AdapterCommand.quiet(label, frame);
    }

    private AdapterCommand loud(String label, byte[] frame) {
        recordTx(label, frame);
        return AdapterCommand.loud(label, frame);
    }

    private void recordTx(String label, byte[] frame) {
        StateStore.appendMediaTx(app, cleanDisplay(label) + " bytes=" + AdapterProtocol.hex(frame));
    }

    private static String textTxLabel(int command, String stage, String text) {
        return "Текст " + commandLabel(command) + " " + stage + ": " + quote(shortText(text));
    }

    private static String commandLabel(int command) {
        switch (command) {
            case AdapterProtocol.CMD_RADIO_TEXT:
                return "0x20 radio";
            case AdapterProtocol.CMD_MEDIA_TEXT:
                return "0x21 BT/media";
            case AdapterProtocol.CMD_USB_TEXT:
                return "0x22 USB";
            case AdapterProtocol.CMD_ANDROID_AUTO_TEXT:
                return "0x23 Android Auto";
            case AdapterProtocol.CMD_MY_MUSIC_TEXT:
                return "0x24 My Music";
            case AdapterProtocol.CMD_CARPLAY_TEXT:
                return "0x25 CarPlay";
            default:
                return "0x" + Integer.toHexString(command).toUpperCase();
        }
    }

    private static String quote(String value) {
        return "\"" + cleanDisplay(value) + "\"";
    }

    private static String shortText(String value) {
        String clean = cleanDisplay(value);
        return clean.length() <= 42 ? clean : clean.substring(0, 39) + "...";
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
        if (AppSettings.universalMediaProfile(app) && isYandexMusic(state)) {
            return MediaSourceKind.MY_MUSIC;
        }
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

    private static boolean isYandexMusic(MediaState state) {
        if (state == null) return false;
        String text = ((state.source == null ? "" : state.source) + " "
                + (state.packageName == null ? "" : state.packageName)).toLowerCase();
        return text.contains("yandex") || text.contains("яндекс");
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
