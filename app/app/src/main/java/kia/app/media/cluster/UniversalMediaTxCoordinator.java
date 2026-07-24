package kia.app.media.cluster;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.Locale;

import kia.app.core.StateStore;
import kia.app.core.model.MediaState;
import kia.app.core.settings.AppSettings;
import kia.app.media.domain.RealMediaSourceStatus;
import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.protocol.adapter.AdapterTxOutcome;
import kia.app.protocol.adapter.MediaSourceKind;

/**
 * Ordered media transport used only by Universal Android and UART Real.
 *
 * <p>The legacy TEYES path intentionally remains in {@link MediaClusterSender}. This coordinator
 * separates source transitions from metadata changes, so a new song cannot reset the source.</p>
 */
final class UniversalMediaTxCoordinator {
    private static final long SOURCE_REASSERT_DELAY_MS = 1800L;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int activeProfile = -1;
    private int generation;
    private String lastModeKey = "";
    private String lastSourceKey = "";
    private String lastMediaKey = "";
    private String lastObservedContentKey = "";
    private String desiredMediaKey = "";
    private String pendingMediaKey = "";
    private String offCompletedSourceKey = "";
    private String recoveredRealSourceMediaKey = "";
    private long settleUntilElapsed;
    private long lastUsbEpoch = -1L;
    private boolean lastObservedPlaying;
    private boolean reassertNextText;
    private boolean usbStateKnown;
    private boolean lastUsbReady;
    private boolean latestIdle = true;
    private MediaState latestState = MediaState.empty();
    private RealMediaSourceStatus realSource;
    private String realSourceKey = "";
    private Runnable pendingFirstText;
    private Runnable pendingTrackText;
    private Runnable pendingReassertText;

    UniversalMediaTxCoordinator(Context context) {
        this.app = context.getApplicationContext();
    }

    synchronized void submit(MediaState state) {
        if (state == null) return;
        int profile = AppSettings.mediaProfile(app);
        if (!MediaTxPolicy.usesSynchronizedTx(profile)) {
            onProfileChanged(profile);
            return;
        }
        ensureProfile(profile);
        if (StateStore.call().active) {
            latestState = state;
            latestIdle = false;
            cancelPending();
            return;
        }

        AdapterGateway gateway = AdapterGateway.get(app);
        boolean usbReady = gateway.usbReady();
        observeUsbState(usbReady, gateway.connectionEpoch());
        latestState = state;
        latestIdle = false;
        if (!usbReady) return;
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL
                && realSource != null && realSource.off) {
            cancelPending();
            return;
        }
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL
                && realSource != null && realSource.radio() && !isRadioState(state)) {
            return;
        }

        MediaSourceKind kind = effectiveKind(profile, state);
        boolean radioLike = isRadioKind(kind);
        String source = sourceLabel(profile, kind, state);
        String artist = cleanDisplay(state.artist);
        String title = cleanDisplay(state.title);
        String frequency = radioLike ? firstFrequency(artist, title) : "";
        if (radioLike && TextUtils.isEmpty(frequency) && realSource != null) {
            frequency = cleanDisplay(realSource.frequency);
        }
        int textMode = AppSettings.mediaTextMode(app);
        String firstText;
        String trackText;
        if (radioLike) {
            firstText = radioHeaderText(source, artist, title, frequency);
            trackText = firstText;
        } else if (textMode == AppSettings.MEDIA_TEXT_TRACK_ONLY) {
            firstText = firstNonEmpty(title, artist, source);
            trackText = firstText;
        } else {
            firstText = firstNonEmpty(artist, title, source);
            trackText = firstNonEmpty(title, artist, source);
        }

        String modeKey = MediaTxPolicy.modeKey(profile, kind);
        String sourceKey = profile == AppSettings.MEDIA_PROFILE_UART_REAL
                && realSource != null
                ? profile + "|real|" + realSource.key()
                : MediaTxPolicy.sourceKey(profile, kind, frequency);
        String contentKey = sourceKey + "|" + cleanDisplay(state.packageName)
                + "|" + artist + "|" + title + "|text=" + textMode;
        boolean resumed = MediaTxPolicy.resumedSameContent(
                lastObservedContentKey, lastObservedPlaying, contentKey, state.playing);
        lastObservedContentKey = contentKey;
        lastObservedPlaying = state.playing;
        String mediaKey = contentKey;
        MediaTxTransition transition = MediaTxTransition.decide(
                profile, lastModeKey, lastSourceKey, lastMediaKey,
                modeKey, sourceKey, mediaKey);
        boolean modeChanged = transition.modeChanged;
        boolean sourceChanged = transition.sourceChanged;

        if (!transition.sendText && !resumed) return;
        if (resumed || !TextUtils.equals(desiredMediaKey, mediaKey)) {
            beginGeneration(mediaKey);
        }

        if (sourceChanged) {
            if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) {
                if (!sendAndroidSourceTransition(
                        gateway, kind, modeChanged, sourceKey, artist, title)) {
                    return;
                }
            }
            lastModeKey = modeKey;
            lastSourceKey = sourceKey;
            settleUntilElapsed = SystemClock.elapsedRealtime()
                    + AppSettings.mediaSourceDelayMs(app, profile);
            reassertNextText = AppSettings.mediaSourceReassertEnabled(app);
            appendJournal("SCHEDULE source=" + kind.name()
                    + " delay=" + AppSettings.mediaSourceDelayMs(app, profile) + "ms");
        }

        long delayMs = Math.max(0L, settleUntilElapsed - SystemClock.elapsedRealtime());
        dispatchText(kind, state, mediaKey, firstText, trackText, delayMs);
    }

    synchronized void submitSourceOnly(MediaState state) {
        submit(state);
    }

    synchronized void submitRadioSearch(MediaState state) {
        submit(state);
    }

    synchronized void forceResend(MediaState state) {
        lastMediaKey = "";
        desiredMediaKey = "";
        settleUntilElapsed = 0L;
        reassertNextText = false;
        cancelPending();
        if (state != null) submit(state);
    }

    synchronized boolean onRealSource(RealMediaSourceStatus status) {
        if (status == null || !AppSettings.uartRealMediaProfile(app)) return false;
        ensureProfile(AppSettings.MEDIA_PROFILE_UART_REAL);
        String nextKey = status.key();
        if (TextUtils.equals(realSourceKey, nextKey)) {
            return recoverAfterSameRealSource(status);
        }

        realSource = status;
        realSourceKey = nextKey;
        lastModeKey = "";
        lastSourceKey = "";
        lastMediaKey = "";
        settleUntilElapsed = SystemClock.elapsedRealtime()
                + AppSettings.mediaSourceDelayMs(app, AppSettings.MEDIA_PROFILE_UART_REAL);
        reassertNextText = AppSettings.mediaSourceReassertEnabled(app);
        beginGeneration("");
        appendJournal("SOURCE_RX raw=0x" + String.format(Locale.US, "%02X", status.rawSource)
                + " kind=" + status.kind.name()
                + (TextUtils.isEmpty(status.frequency) ? "" : " frequency=" + status.frequency));

        if (latestState != null && MediaTxPolicy.shouldReassertAfterRealSource(
                status.off, status.radio(), isRadioState(latestState),
                hasMediaText(latestState), latestIdle)) {
            submit(latestState);
            recoveredRealSourceMediaKey = desiredMediaKey;
        }
        return true;
    }

    synchronized boolean hasRealRadioSource() {
        return AppSettings.uartRealMediaProfile(app)
                && realSource != null && !realSource.off && realSource.radio();
    }

    synchronized boolean hasRealSource() {
        return AppSettings.uartRealMediaProfile(app) && realSource != null;
    }

    synchronized String realRadioFrequency() {
        return hasRealRadioSource() ? cleanDisplay(realSource.frequency) : "";
    }

    synchronized void onIdle(MediaState state) {
        if (!MediaTxPolicy.usesSynchronizedTx(AppSettings.mediaProfile(app))) return;
        latestState = state == null ? MediaState.empty() : state;
        latestIdle = true;
        lastMediaKey = "";
        lastObservedPlaying = false;
        reassertNextText = false;
        beginGeneration("");
    }

    synchronized void onProfileChanged(int profile) {
        if (profile == activeProfile && MediaTxPolicy.usesSynchronizedTx(profile)) return;
        resetInternal();
        activeProfile = profile;
        if (profile != AppSettings.MEDIA_PROFILE_UART_REAL) {
            realSource = null;
            realSourceKey = "";
        }
    }

    synchronized void suspendForCall() {
        cancelPending();
        desiredMediaKey = "";
        pendingMediaKey = "";
    }

    synchronized void resumeAfterCall(MediaState current) {
        lastMediaKey = "";
        desiredMediaKey = "";
        settleUntilElapsed = 0L;
        cancelPending();
        if (current != null) submit(current);
    }

    synchronized void stop() {
        resetInternal();
        activeProfile = -1;
        realSource = null;
        realSourceKey = "";
    }

    private void ensureProfile(int profile) {
        if (activeProfile == profile) return;
        resetInternal();
        activeProfile = profile;
        if (profile != AppSettings.MEDIA_PROFILE_UART_REAL) {
            realSource = null;
            realSourceKey = "";
        }
    }

    private void observeUsbState(boolean ready, long epoch) {
        if (!usbStateKnown) {
            usbStateKnown = true;
            lastUsbReady = ready;
            lastUsbEpoch = epoch;
            return;
        }
        if (!MediaTxPolicy.transportChanged(
                true, lastUsbReady, lastUsbEpoch, ready, epoch)) return;
        lastUsbReady = ready;
        lastUsbEpoch = epoch;
        lastModeKey = "";
        lastSourceKey = "";
        lastMediaKey = "";
        lastObservedContentKey = "";
        lastObservedPlaying = false;
        desiredMediaKey = "";
        offCompletedSourceKey = "";
        recoveredRealSourceMediaKey = "";
        settleUntilElapsed = 0L;
        cancelPending();
        generation++;
        appendJournal("USB_STATE " + (ready
                ? "ready epoch=" + epoch + "; resync"
                : "offline; waiting for current state"));
    }

    private boolean sendAndroidSourceTransition(AdapterGateway gateway, MediaSourceKind kind,
                                                boolean modeChanged, String sourceKey,
                                                String artist, String title) {
        if (MediaTxPolicy.shouldSendMediaOff(
                modeChanged, sourceKey, offCompletedSourceKey)) {
            if (!tx(gateway, "media source off", AdapterProtocol.mediaOffStatus())) {
                return false;
            }
            offCompletedSourceKey = sourceKey;
        }
        if (textOnlySource(kind)) {
            offCompletedSourceKey = "";
            return true;
        }
        byte[] sourceFrame = isRadioKind(kind)
                ? radioSourceStatus(kind, artist, title)
                : AdapterProtocol.mediaSourceStatus(kind, title);
        if (!tx(gateway, "media source " + kind.name(), sourceFrame)) return false;
        offCompletedSourceKey = "";
        return true;
    }

    private boolean recoverAfterSameRealSource(RealMediaSourceStatus status) {
        String currentMediaKey = TextUtils.isEmpty(desiredMediaKey)
                ? lastMediaKey : desiredMediaKey;
        if (!MediaTxPolicy.shouldRecoverSameRealSource(
                status.off, status.radio(), isRadioState(latestState),
                hasMediaText(latestState), latestIdle,
                currentMediaKey, recoveredRealSourceMediaKey)) {
            return false;
        }
        recoveredRealSourceMediaKey = currentMediaKey;
        settleUntilElapsed = SystemClock.elapsedRealtime()
                + AppSettings.mediaSourceDelayMs(app, AppSettings.MEDIA_PROFILE_UART_REAL);
        reassertNextText = false;
        lastMediaKey = "";
        beginGeneration(currentMediaKey);
        appendJournal("SOURCE_RX same " + status.kind.name()
                + "; recover current text once");
        submit(latestState);
        return true;
    }

    private void dispatchText(MediaSourceKind kind, MediaState state, String mediaKey,
                              String firstText, String trackText, long delayMs) {
        if (TextUtils.isEmpty(firstText)) return;
        int profile = activeProfile;
        int token = generation;
        int command = textCommand(kind);
        if (delayMs <= 0L) {
            transmitTextSequence(profile, token, command, mediaKey, firstText, trackText);
            return;
        }
        if (TextUtils.equals(pendingMediaKey, mediaKey) && pendingFirstText != null) return;
        if (pendingFirstText != null) handler.removeCallbacks(pendingFirstText);
        pendingMediaKey = mediaKey;
        pendingFirstText = () -> {
            synchronized (UniversalMediaTxCoordinator.this) {
                pendingFirstText = null;
                pendingMediaKey = "";
                if (!valid(profile, token, mediaKey)) return;
                transmitTextSequence(profile, token, command, mediaKey, firstText, trackText);
            }
        };
        handler.postDelayed(pendingFirstText, delayMs);
    }

    private void transmitTextSequence(int profile, int token, int command, String mediaKey,
                                      String firstText, String trackText) {
        if (!valid(profile, token, mediaKey)) return;
        cancelTextTail();
        AdapterGateway gateway = AdapterGateway.get(app);
        if (!tx(gateway, textLabel(command, "first", firstText),
                AdapterProtocol.textPacket(command, firstText))) {
            lastMediaKey = "";
            return;
        }
        lastMediaKey = mediaKey;

        long artistDelayMs = AppSettings.mediaArtistDelayMs(app);
        boolean differentTrack = !TextUtils.isEmpty(trackText)
                && !TextUtils.equals(firstText, trackText);
        if (differentTrack) {
            pendingTrackText = () -> {
                synchronized (UniversalMediaTxCoordinator.this) {
                    pendingTrackText = null;
                    if (!valid(profile, token, mediaKey)) return;
                    if (!tx(AdapterGateway.get(app), textLabel(command, "track", trackText),
                            AdapterProtocol.textPacket(command, trackText))) {
                        lastMediaKey = "";
                    }
                }
            };
            if (artistDelayMs <= 0L) pendingTrackText.run();
            else handler.postDelayed(pendingTrackText, artistDelayMs);
        }

        if (reassertNextText) {
            reassertNextText = false;
            String finalText = differentTrack ? trackText : firstText;
            long repeatDelay = Math.max(SOURCE_REASSERT_DELAY_MS,
                    (differentTrack ? artistDelayMs : 0L) + SOURCE_REASSERT_DELAY_MS);
            pendingReassertText = () -> {
                synchronized (UniversalMediaTxCoordinator.this) {
                    pendingReassertText = null;
                    if (!valid(profile, token, mediaKey)) return;
                    if (!tx(AdapterGateway.get(app), textLabel(command, "reassert", finalText),
                            AdapterProtocol.textPacket(command, finalText))) {
                        lastMediaKey = "";
                    }
                }
            };
            handler.postDelayed(pendingReassertText, repeatDelay);
        }
    }

    private boolean valid(int profile, int token, String mediaKey) {
        if (!MediaTxPolicy.callbackCurrent(
                token, generation, profile, activeProfile, AppSettings.mediaProfile(app),
                StateStore.call().active, mediaKey, desiredMediaKey)) {
            return false;
        }
        AdapterGateway gateway = AdapterGateway.get(app);
        return MediaTxPolicy.transportCurrent(
                gateway.usbReady(), lastUsbEpoch, gateway.connectionEpoch());
    }

    private void beginGeneration(String mediaKey) {
        String next = mediaKey == null ? "" : mediaKey;
        if (!TextUtils.equals(desiredMediaKey, next)) {
            recoveredRealSourceMediaKey = "";
        }
        cancelPending();
        generation++;
        desiredMediaKey = next;
        pendingMediaKey = "";
    }

    private void cancelPending() {
        if (pendingFirstText != null) handler.removeCallbacks(pendingFirstText);
        pendingFirstText = null;
        cancelTextTail();
        pendingMediaKey = "";
    }

    private void cancelTextTail() {
        if (pendingTrackText != null) handler.removeCallbacks(pendingTrackText);
        if (pendingReassertText != null) handler.removeCallbacks(pendingReassertText);
        pendingTrackText = null;
        pendingReassertText = null;
    }

    private void resetInternal() {
        cancelPending();
        generation++;
        lastModeKey = "";
        lastSourceKey = "";
        lastMediaKey = "";
        lastObservedContentKey = "";
        lastObservedPlaying = false;
        desiredMediaKey = "";
        offCompletedSourceKey = "";
        recoveredRealSourceMediaKey = "";
        settleUntilElapsed = 0L;
        reassertNextText = false;
        usbStateKnown = false;
        lastUsbEpoch = -1L;
        latestIdle = true;
        latestState = MediaState.empty();
    }

    private boolean tx(AdapterGateway gateway, String label, byte[] frame) {
        AdapterTxOutcome outcome = gateway.send(AdapterCommand.quiet(label, frame));
        StateStore.appendMediaTx(app, cleanDisplay(label) + " outcome=" + outcome
                + " bytes=" + AdapterProtocol.hex(frame));
        return outcome == AdapterTxOutcome.WRITTEN || outcome == AdapterTxOutcome.QUEUED;
    }

    private void appendJournal(String value) {
        StateStore.appendMediaTx(app, cleanDisplay(value));
    }

    private MediaSourceKind effectiveKind(int profile, MediaState state) {
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL
                && realSource != null && !realSource.off
                && realSource.kind != MediaSourceKind.GENERIC_MUSIC) {
            return realSource.kind;
        }
        MediaSourceKind detected = MediaTxPolicy.detectUniversalSource(
                state.source, state.packageName);
        return MediaTxPolicy.resolveOtherSource(detected, AppSettings.otherMediaSourceMode(app));
    }

    private String sourceLabel(int profile, MediaSourceKind kind, MediaState state) {
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL
                && realSource != null && !TextUtils.isEmpty(realSource.source)) {
            return realSource.source;
        }
        return TextUtils.isEmpty(state.source) ? kind.defaultLabel() : state.source;
    }

    private static boolean isRadioState(MediaState state) {
        return state != null
                && MediaTxPolicy.isPhysicalRadioSource(state.source, state.packageName);
    }

    private static boolean hasMediaText(MediaState state) {
        return state != null && (!TextUtils.isEmpty(state.source)
                || !TextUtils.isEmpty(state.artist) || !TextUtils.isEmpty(state.title));
    }

    private static int textCommand(MediaSourceKind kind) {
        if (isRadioKind(kind)) return AdapterProtocol.CMD_RADIO_TEXT;
        if (kind == MediaSourceKind.USB_MUSIC) return AdapterProtocol.CMD_USB_TEXT;
        if (kind == MediaSourceKind.ANDROID_AUTO) return AdapterProtocol.CMD_ANDROID_AUTO_TEXT;
        if (kind == MediaSourceKind.CARPLAY) return AdapterProtocol.CMD_CARPLAY_TEXT;
        if (kind == MediaSourceKind.MY_MUSIC) return AdapterProtocol.CMD_MY_MUSIC_TEXT;
        if (kind == MediaSourceKind.BLUETOOTH_AUDIO || kind == MediaSourceKind.BT_PHONE) {
            return AdapterProtocol.CMD_MEDIA_TEXT;
        }
        return AdapterProtocol.CMD_ANDROID_AUTO_TEXT;
    }

    private static boolean textOnlySource(MediaSourceKind kind) {
        return kind == MediaSourceKind.ANDROID_AUTO
                || kind == MediaSourceKind.CARPLAY
                || kind == MediaSourceKind.MY_MUSIC;
    }

    private static boolean isRadioKind(MediaSourceKind kind) {
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO;
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

    private static String radioHeaderText(String source, String artist, String title,
                                          String frequency) {
        String station = firstNonEmpty(nonFrequency(title), nonFrequency(artist));
        if (!TextUtils.isEmpty(station)) return station;
        return firstNonEmpty(frequency, source);
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
        return value.trim().matches("(?i)^(FM|AM)?\\s*\\d{2,4}([\\.,]\\d{1,2})?\\s*(MHz|kHz)?$");
    }

    private static String textLabel(int command, String stage, String text) {
        return "media text " + commandLabel(command) + " " + stage + ": \""
                + shortText(text) + "\"";
    }

    private static String commandLabel(int command) {
        return String.format(Locale.US, "0x%02X", command);
    }

    private static String shortText(String value) {
        String clean = cleanDisplay(value);
        return clean.length() <= 42 ? clean : clean.substring(0, 39) + "...";
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
