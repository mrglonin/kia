package kia.app.media.capture;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public final class MediaSessionSelectionPolicyTest {
    @Test
    public void firstFrameworkOrderedPlayingSessionWinsByDefault() {
        List<MediaSessionSelectionPolicy.Candidate> candidates = Arrays.asList(
                candidate("music.paused", false),
                candidate("music.first", true),
                candidate("music.second", true));

        assertEquals(1, select(candidates, "", Collections.emptySet()));
    }

    @Test
    public void preferredPlayingPackageWinsAmongPlayingSessions() {
        List<MediaSessionSelectionPolicy.Candidate> candidates = Arrays.asList(
                candidate("music.first", true),
                candidate("music.preferred", true));

        assertEquals(1, select(candidates, "music.preferred", Collections.emptySet()));
    }

    @Test
    public void pausedPreferredNeverOverridesAnotherPlayingSession() {
        List<MediaSessionSelectionPolicy.Candidate> candidates = Arrays.asList(
                candidate("music.preferred", false),
                candidate("music.playing", true));

        assertEquals(1, select(candidates, "music.preferred", Collections.emptySet()));
    }

    @Test
    public void preferredPausedSessionWinsWhenNothingIsPlaying() {
        List<MediaSessionSelectionPolicy.Candidate> candidates = Arrays.asList(
                candidate("music.other", false),
                candidate("music.preferred", false));

        assertEquals(1, select(candidates, "music.preferred", Collections.emptySet()));
    }

    @Test
    public void denylistWinsOverPreferenceAndFallsThroughToPlayingSession() {
        List<MediaSessionSelectionPolicy.Candidate> candidates = Arrays.asList(
                candidate("music.preferred", true),
                candidate("music.allowed", true));

        assertEquals(1, select(candidates, "music.preferred",
                new HashSet<>(Collections.singletonList("MUSIC.PREFERRED"))));
    }

    @Test
    public void allBlockedSessionsProduceNoSelection() {
        List<MediaSessionSelectionPolicy.Candidate> candidates = Arrays.asList(
                candidate("music.one", true),
                candidate("music.two", false));

        assertEquals(MediaSessionSelectionPolicy.NONE, select(candidates, "",
                new HashSet<>(Arrays.asList("music.one", "music.two"))));
    }

    private static MediaSessionSelectionPolicy.Candidate candidate(
            String packageName, boolean playing) {
        return new MediaSessionSelectionPolicy.Candidate(packageName, playing);
    }

    private static int select(List<MediaSessionSelectionPolicy.Candidate> candidates,
                              String preferred, java.util.Set<String> blocked) {
        return MediaSessionSelectionPolicy.selectIndex(candidates, preferred, blocked);
    }
}
