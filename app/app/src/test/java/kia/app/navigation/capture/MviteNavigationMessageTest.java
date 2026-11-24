package kia.app.navigation.capture;

import org.junit.Test;

import static kia.app.navigation.capture.MviteNavigationMessage.Kind.ARRIVAL_TIME;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.CAMERA;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.EXIT_NUMBER;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.MANEUVER;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.OVERSPEED;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.REMAINING_TIME;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.ROUTE_ACTIVE;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.ROUTE_DISTANCE;
import static kia.app.navigation.capture.MviteNavigationMessage.Kind.SPEED_LIMIT;
import static kia.app.navigation.capture.MviteNavigationMessage.parse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MviteNavigationMessageTest {
    @Test
    public void parsesRouteAndOverspeedOnlyWithExplicitBoolean() {
        assertEquals("ssa.labs.monjaro.YN_HOOK", MviteNavigationMessage.ACTION);
        assertEquals(ROUTE_ACTIVE, parse("{\"event\":\"RouteActive\",\"value\":true}").kind());
        assertTrue(parse("{\"event\":\"RouteActive\",\"value\":true}").active());
        assertFalse(parse("{\"event\":\"RouteActive\",\"value\":false}").active());
        assertEquals(OVERSPEED, parse("{\"event\":\"SpeedLimit.Alarm\",\"value\":true}").kind());
        assertTrue(parse("{\"event\":\"SpeedLimit.Alarm\",\"value\":true}").active());
        assertFalse(parse("{\"event\":\"SpeedLimit.Alarm\",\"value\":false}").active());
        assertNull(parse("{\"event\":\"RouteActive\"}"));
        assertNull(parse("{\"event\":\"RouteActive\",\"value\":\"false\"}"));
        assertNull(parse("{\"event\":\"SpeedLimit.Alarm\",\"value\":1}"));
    }

    @Test
    public void normalizesManeuversAndCleansRoadNames() {
        MviteNavigationMessage turn = parse("""
                {"event":"Maneuver","sign":"turn_right","distance":250,"metric":"м",
                 "nextRoad":"  Абая\\nугол Пушкина [центр]  "}
                """);
        assertEquals(MANEUVER, turn.kind());
        assertEquals("context_ra_turn_right", turn.maneuver());
        assertEquals(250d, turn.distanceMeters(), 0.001);
        assertEquals("Абая угол Пушкина [центр]", turn.text());

        MviteNavigationMessage roundabout = parse("""
                {"event":"Maneuver","sign":"in_circular_movement","distance":0.4,"metric":"км",
                 "nextRoad":"null"}
                """);
        assertEquals("context_ra_in_circular_movement", roundabout.maneuver());
        assertEquals(400d, roundabout.distanceMeters(), 0.001);
        assertEquals("", roundabout.text());
        assertEquals("context_ra_turn_back_left", parse("""
                {"event":"Maneuver","sign":"context_ra_turn_back_left","distance":5,"metric":"m"}
                """).maneuver());
        assertNull(parse("{\"event\":\"Maneuver\",\"sign\":\"unknown\",\"distance\":0,\"metric\":\"m\"}"));
    }

    @Test
    public void parsesRouteDistanceExitAndSpeedLimit() {
        MviteNavigationMessage distance = parse("{\"event\":\"Eta.DistanceLeft\",\"value\":12.7,\"metric\":\"km\"}");
        assertEquals(ROUTE_DISTANCE, distance.kind());
        assertEquals(12_700d, distance.distanceMeters(), 0.001);

        MviteNavigationMessage exit = parse("{\"event\":\"Maneuver.ExitNumber\",\"value\":3}");
        assertEquals(EXIT_NUMBER, exit.kind());
        assertEquals(3, exit.value());
        assertEquals(-1, parse("{\"event\":\"Maneuver.ExitNumber\",\"value\":-1}").value());

        MviteNavigationMessage limit = parse("{\"event\":\"SpeedLimit\",\"value\":60}");
        assertEquals(SPEED_LIMIT, limit.kind());
        assertEquals(60, limit.value());
        assertNull(parse("{\"event\":\"Maneuver.ExitNumber\",\"value\":1.5}"));
        assertNull(parse("{\"event\":\"SpeedLimit\",\"value\":256}"));
        assertNull(parse("{\"event\":\"Eta.DistanceLeft\",\"value\":-5,\"metric\":\"m\"}"));
        assertNull(parse("{\"event\":\"Eta.DistanceLeft\",\"value\":3,\"metric\":\"mi\"}"));
    }

    @Test
    public void formatsRemainingAndArrivalTimeIndependently() {
        MviteNavigationMessage remaining = parse("{\"event\":\"Eta.TimeLeft\",\"hour\":26,\"minute\":5}");
        assertEquals(REMAINING_TIME, remaining.kind());
        assertEquals("26:05", remaining.text());
        assertEquals(1565, remaining.value());

        MviteNavigationMessage arrival = parse("{\"event\":\"Eta.ArrivalTime\",\"hour\":9,\"minute\":0}");
        assertEquals(ARRIVAL_TIME, arrival.kind());
        assertEquals("09:00", arrival.text());
        assertEquals(540, arrival.value());
        assertNull(parse("{\"event\":\"Eta.ArrivalTime\",\"hour\":24,\"minute\":0}"));
        assertNull(parse("{\"event\":\"Eta.TimeLeft\",\"hour\":0,\"minute\":60}"));
    }

    @Test
    public void cameraOnlyAcceptsFormattedDistancesAndClearsOnHide() {
        MviteNavigationMessage show = parse("{\"event\":\"Camera\",\"action\":\"show\",\"dist\":\"300м\"}");
        assertEquals(CAMERA, show.kind());
        assertTrue(show.active());
        assertEquals("300 м", show.text());
        assertEquals(300d, show.distanceMeters(), 0.001);

        MviteNavigationMessage update = parse("{\"event\":\"Camera\",\"action\":\"update\",\"dist\":\"0,2\u00a0км\"}");
        assertTrue(update.active());
        assertEquals("0,2 км", update.text());
        assertEquals(200d, update.distanceMeters(), 0.001);

        MviteNavigationMessage hide = parse("{\"event\":\"Camera\",\"action\":\"hide\"}");
        assertFalse(hide.active());
        assertEquals("", hide.text());
        assertNull(parse("{\"event\":\"Camera\",\"action\":\"show\",\"dist\":\"Камера 300 м\"}"));
    }

    @Test
    public void rejectsMalformedUnsupportedAndUnboundedPayloads() {
        assertNull(parse(null));
        assertNull(parse("{\"event\":\"RouteActive\",\"value\":true"));
        assertNull(parse("{\"event\":\"RouteActive\",\"value\":true}{}"));
        assertNull(parse("{\"event\":\"RouteActive\",\"value\":true}\0{}"));
        assertNull(parse("{\"event\":\"Lanes\",\"value\":[]}"));
        assertNull(parse("{\"event\":\"RouteActive\",\"value\":true,\"x\":\"" + "a".repeat(4096) + "\"}"));
        assertNull(parse("{\"event\":\"RouteActive\",\"value\":true,\"x\":"
                + "[".repeat(12) + "false" + "]".repeat(12) + "}"));
    }
}
