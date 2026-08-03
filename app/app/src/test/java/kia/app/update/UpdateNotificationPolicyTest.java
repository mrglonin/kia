package kia.app.update;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdateNotificationPolicyTest {
    @Test
    public void inFlightSecondCheckKeepsExistingNotification() {
        assertEquals(UpdateNotificationPolicy.Action.KEEP,
                UpdateNotificationPolicy.action(false, false, false, true, "", "old"));
    }

    @Test
    public void partialAppResultWaitsForNavigatorCheck() {
        assertEquals(UpdateNotificationPolicy.Action.KEEP,
                UpdateNotificationPolicy.action(true, false, false, true,
                        "kia_363", ""));
    }

    @Test
    public void partialNavigatorResultWaitsForAppCheck() {
        assertEquals(UpdateNotificationPolicy.Action.KEEP,
                UpdateNotificationPolicy.action(false, true, true, false,
                        "nav_new", ""));
    }

    @Test
    public void completedCurrentChecksCancelNotification() {
        assertEquals(UpdateNotificationPolicy.Action.CANCEL,
                UpdateNotificationPolicy.action(false, false, false, false, "", "old"));
    }

    @Test
    public void sameReleaseDoesNotAlertAgain() {
        assertEquals(UpdateNotificationPolicy.Action.KEEP,
                UpdateNotificationPolicy.action(true, false, false, false,
                        "kia_363", "kia_363"));
    }

    @Test
    public void newReleasePostsNotification() {
        assertEquals(UpdateNotificationPolicy.Action.NOTIFY,
                UpdateNotificationPolicy.action(true, true, false, false,
                        "kia_364|nav_new", "kia_363"));
    }
}
