package kia.app.sunroof;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import kia.app.R;

import static org.junit.Assert.*;

/** Exercises cancellation and timing without Android playback, speakers, or sleeping. */
public final class SunroofSoundPlayerTest {
    private final FakeScheduler clock = new FakeScheduler();
    private final FakeFactory factory = new FakeFactory();
    private final SunroofSoundPlayer sound = new SunroofSoundPlayer(clock, factory);

    @Test public void openingAndClosingSelectTheSameNativeResourceAndRemainOneShot() {
        sound.play(SunroofAlertPolicy.Kind.OPENING);
        assertEquals(R.raw.sunroof_transition_teyes, (int) factory.resources.get(0));
        clock.advanceTo(3_000);
        sound.play(SunroofAlertPolicy.Kind.CLOSING);
        assertEquals(R.raw.sunroof_transition_teyes, (int) factory.resources.get(1));
        clock.advanceTo(20_000);
        assertEquals(2, factory.resources.size());
        assertEquals(2, factory.started);
    }

    @Test public void parkingWarningUsesItsOwnCueAtAllThreeFixedSlots() {
        sound.play(SunroofAlertPolicy.Kind.PARKING_WARNING);
        clock.advanceTo(10_000);
        assertEquals(3, factory.resources.size());
        assertNotEquals(R.raw.sunroof_transition_teyes, R.raw.sunroof_warning_adas);
        for (int resource : factory.resources) {
            assertEquals(R.raw.sunroof_warning_adas, resource);
        }
        assertEquals(0, factory.live);
    }

    @Test public void replacingWarningWithClosingCancelsOldWarningCueAndCallbacks() {
        sound.play(SunroofAlertPolicy.Kind.PARKING_WARNING);
        List<Runnable> stale = clock.pendingRunnables();
        FakeClip warning = factory.clips.get(0);
        clock.advanceTo(100);
        sound.play(SunroofAlertPolicy.Kind.CLOSING);
        warning.complete.run();
        for (Runnable callback : stale) callback.run();
        assertEquals(2, factory.resources.size());
        assertEquals(R.raw.sunroof_transition_teyes, (int) factory.resources.get(1));
        assertEquals(1, factory.live);
        clock.advanceTo(20_000);
        assertEquals(2, factory.resources.size());
        assertEquals(0, factory.live);
    }

    @Test public void noneAndNullStopWithoutSelectingOrStartingAnyCue() {
        sound.play(SunroofAlertPolicy.Kind.PARKING_WARNING);
        sound.play(SunroofAlertPolicy.Kind.NONE);
        sound.play((SunroofAlertPolicy.Kind) null);
        clock.advanceTo(20_000);
        assertEquals(1, factory.resources.size());
        assertEquals(1, factory.started);
        assertEquals(0, factory.live);
    }

    @Test public void transitionPlaysExactlyOnceAndReleasesOnCompletion() {
        sound.play(false);
        assertEquals(1, factory.started);
        FakeClip clip = factory.clips.get(0);
        clip.complete.run();
        assertEquals(1, clip.closed);
        clock.advanceTo(100_000);
        assertEquals(1, factory.started);
        assertEquals(0, clock.pending.size());
    }

    @Test public void warningPlaysOnlyAtZeroThreeAndSixSeconds() {
        sound.play(true);
        assertEquals(1, factory.started);
        clock.advanceTo(2_999);
        assertEquals(1, factory.started);
        clock.advanceTo(3_000);
        assertEquals(2, factory.started);
        clock.advanceTo(5_999);
        assertEquals(2, factory.started);
        clock.advanceTo(6_000);
        assertEquals(3, factory.started);
        clock.advanceTo(9_499);
        assertEquals(3, factory.started);
        clock.advanceTo(9_500);
        assertEquals(3, factory.started);
        assertEquals(1, factory.clips.get(2).closed);
        assertEquals(0, clock.pending.size());
        clock.advanceTo(1_000_000);
        assertEquals(3, factory.started);
    }

    @Test public void unfinishedClipIsReleasedBeforeNextSlotNoOverlap() {
        sound.play(true);
        clock.advanceTo(3_000);
        assertEquals(1, factory.clips.get(0).closed);
        assertEquals(0, factory.clips.get(1).closed);
        assertEquals(1, factory.live);
        assertEquals(1, factory.maxLive);
        clock.advanceTo(9_500);
        assertEquals(0, factory.live);
    }

    @Test public void stopImmediatelyReleasesAndCancelsEveryPendingRepeat() {
        sound.play(true);
        FakeClip clip = factory.clips.get(0);
        sound.stop();
        assertEquals(1, clip.closed);
        assertEquals(0, clock.pending.size());
        clock.advanceTo(30_000);
        assertEquals(1, factory.started);
    }

    @Test public void disablingStopsAndEnablingDoesNotAutoReplay() {
        sound.play(true);
        sound.setEnabled(false);
        sound.play(true);
        assertEquals(1, factory.started);
        assertEquals(0, factory.live);
        clock.advanceTo(30_000);
        sound.setEnabled(true);
        assertEquals(1, factory.started);
        sound.play(false);
        assertEquals(2, factory.started);
    }

    @Test public void replacementCancelsOldRepeatsAndOldCompletionCannotCloseNewClip() {
        sound.play(true);
        FakeClip oldClip = factory.clips.get(0);
        List<Runnable> cancelled = clock.pendingRunnables();
        clock.advanceTo(100);
        sound.play(false);
        FakeClip replacement = factory.clips.get(1);
        oldClip.complete.run();
        oldClip.error.run();
        for (Runnable stale : cancelled) stale.run();
        assertEquals(0, replacement.closed);
        assertEquals(2, factory.started);
        clock.advanceTo(2_600);
        assertEquals(1, replacement.closed);
        clock.advanceTo(30_000);
        assertEquals(2, factory.started);
    }

    @Test public void oldClipCompletionWithinSameWarningCannotReleaseTheNextClip() {
        sound.play(true);
        FakeClip first = factory.clips.get(0);
        clock.advanceTo(3_000);
        FakeClip second = factory.clips.get(1);
        first.complete.run();
        first.error.run();
        assertEquals(0, second.closed);
        assertEquals(1, factory.live);
    }

    @Test public void errorReleasesOnceAndDoesNotAddAnUnboundedRetryLoop() {
        sound.play(true);
        FakeClip first = factory.clips.get(0);
        first.error.run();
        first.complete.run();
        assertEquals(1, first.closed);
        assertEquals(1, factory.started);
        clock.advanceTo(9_500);
        assertEquals(3, factory.started);
        assertEquals(0, factory.live);
    }

    @Test public void nullFactoryResultConsumesFixedSlotsAndEventuallyCancels() {
        factory.returnNull = true;
        sound.play(true);
        clock.advanceTo(20_000);
        assertEquals(3, factory.attempts);
        assertEquals(0, factory.started);
        assertEquals(0, clock.pending.size());
    }

    @Test public void createAndStartFailuresRemainBoundedAndReleaseAllocatedPlayers() {
        factory.throwOnCreate = true;
        sound.play(true);
        clock.advanceTo(20_000);
        assertEquals(3, factory.attempts);
        assertEquals(0, factory.live);
        factory.throwOnCreate = false;
        factory.throwOnStart = true;
        sound.play(true);
        clock.advanceTo(40_000);
        assertEquals(6, factory.attempts);
        assertEquals(0, factory.live);
        for (FakeClip clip : factory.clips) assertEquals(1, clip.closed);
    }

    @Test public void completedFirstClipDoesNotPreventTheRemainingBoundedSlots() {
        sound.play(true);
        factory.clips.get(0).complete.run();
        clock.advanceTo(3_000);
        factory.clips.get(1).complete.run();
        clock.advanceTo(6_000);
        factory.clips.get(2).complete.run();
        clock.advanceTo(100_000);
        assertEquals(3, factory.started);
        assertEquals(0, factory.live);
    }

    @Test public void stalledCallbacksCannotPlayBackToBackCues() {
        sound.play(true);
        clock.stallTo(7_000);
        clock.runDue();
        assertEquals(2, factory.started);
        assertEquals(1, factory.live);
        clock.advanceTo(9_500);
        assertEquals(2, factory.started);
        assertEquals(0, factory.live);
    }

    @Test public void elapsedDeadlinePreventsReplayAfterSleepOrLongUiStall() {
        sound.play(true);
        clock.stallTo(100_000);
        clock.runDue();
        assertEquals(1, factory.started);
        assertEquals(0, factory.live);
        assertEquals(0, clock.pending.size());
    }

    @Test public void normalTransitionHasItsOwnHardReleaseDeadline() {
        sound.play(false);
        clock.advanceTo(2_499);
        assertEquals(1, factory.live);
        clock.advanceTo(2_500);
        assertEquals(0, factory.live);
        assertEquals(0, clock.pending.size());
    }

    @Test public void playerCreationThatOutlivesTheDeadlineNeverStartsAudio() {
        factory.createDelay = 10_000;
        sound.play(true);
        assertEquals(1, factory.attempts);
        assertEquals(0, factory.started);
        assertEquals(1, factory.clips.get(0).closed);
        assertEquals(0, clock.pending.size());
    }

    @Test public void stopAndErrorsAreIdempotentAfterEverythingWasReleased() {
        sound.play(true);
        FakeClip clip = factory.clips.get(0);
        sound.stop();
        sound.stop();
        sound.setEnabled(false);
        clip.complete.run();
        clip.error.run();
        assertEquals(1, clip.closed);
        assertEquals(0, factory.live);
        assertEquals(0, clock.pending.size());
    }

    private static final class FakeScheduler implements SunroofSoundPlayer.Scheduler {
        private long time;
        private final List<Task> pending = new ArrayList<>();

        @Override public long now() { return time; }
        @Override public void postDelayed(Runnable runnable, long delayMs) {
            pending.add(new Task(time + delayMs, runnable));
        }
        @Override public void remove(Runnable runnable) {
            pending.removeIf(task -> task.runnable == runnable);
        }

        void advanceTo(long target) {
            while (true) {
                Task next = pending.stream().min(Comparator.comparingLong(t -> t.due)).orElse(null);
                if (next == null || next.due > target) break;
                time = Math.max(time, next.due);
                pending.remove(next);
                next.runnable.run();
            }
            time = target;
        }

        void stallTo(long target) { time = target; }

        void runDue() {
            while (true) {
                Task next = pending.stream().min(Comparator.comparingLong(t -> t.due)).orElse(null);
                if (next == null || next.due > time) return;
                pending.remove(next);
                next.runnable.run();
            }
        }

        List<Runnable> pendingRunnables() {
            List<Runnable> result = new ArrayList<>();
            for (Task task : pending) result.add(task.runnable);
            return result;
        }

        private static final class Task {
            final long due;
            final Runnable runnable;
            Task(long due, Runnable runnable) { this.due = due; this.runnable = runnable; }
        }
    }

    private final class FakeFactory implements SunroofSoundPlayer.Factory {
        private final List<FakeClip> clips = new ArrayList<>();
        private final List<Integer> resources = new ArrayList<>();
        private boolean returnNull;
        private boolean throwOnCreate;
        private boolean throwOnStart;
        private int attempts;
        private int started;
        private int live;
        private int maxLive;
        private long createDelay;

        @Override public SunroofSoundPlayer.Clip create(int resource,
                Runnable complete, Runnable error) {
            resources.add(resource);
            attempts++;
            if (throwOnCreate) throw new IllegalStateException("create");
            if (returnNull) return null;
            clock.stallTo(clock.now() + createDelay);
            FakeClip clip = new FakeClip(complete, error);
            clips.add(clip);
            return clip;
        }
    }

    private final class FakeClip implements SunroofSoundPlayer.Clip {
        private final Runnable complete;
        private final Runnable error;
        private int closed;
        private boolean playing;

        FakeClip(Runnable complete, Runnable error) { this.complete = complete; this.error = error; }

        @Override public void start() {
            if (factory.throwOnStart) throw new IllegalStateException("start");
            factory.started++;
            playing = true;
            factory.live++;
            factory.maxLive = Math.max(factory.maxLive, factory.live);
        }

        @Override public void close() {
            closed++;
            if (playing) {
                playing = false;
                factory.live--;
            }
        }
    }
}
