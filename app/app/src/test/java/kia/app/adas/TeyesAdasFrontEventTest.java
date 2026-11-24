package kia.app.adas;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class TeyesAdasFrontEventTest {
    private static final class Fixture implements TeyesAdasFrontEvent.Words {
        final ArrayList<Integer> words = new ArrayList<>();
        int position;

        Fixture add(int... values) {
            for (int value : values) words.add(value);
            return this;
        }

        Fixture empty(int count) {
            for (int i = 0; i < count; i++) words.add(0);
            return this;
        }

        public int remaining() { return words.size() - position; }
        public int read() { return words.get(position++); }
        public void skip(int count) { position += count; }
    }

    @Test public void preservesUnknownBitmaskAndOptionalMissingResult() {
        TeyesAdasFrontEvent event = TeyesAdasFrontEvent.read(new Fixture().add(1, 0x10008, 0, 0, 0, 0));
        assertEquals(0x10008, event.warningType);
        assertNull(event.laneState);
        assertNull(event.lineCount);
        assertNull(event.laneChange);
        assertFalse(event.testEvent);
        assertNull(TeyesAdasFrontEvent.read(new Fixture().add(0)));
    }

    @Test public void readsLdwWithNestedObjectsAndNullableArrayElements() {
        Fixture input = new Fixture().add(1, 0x4008, 1);
        input.add(1, 0, 1, 3, 0, 1); // FCW result and two of three array presence flags
        object(input, 3);
        input.add(1);
        object(input, -1);
        input.add(2, 4, 9); // LDW state, number of lines and lane change
        input.add(2, 1).empty(13).add(0); // one actual line and a null slot
        input.add(0, 2, 2, 0, 1).empty(2); // distance line with one non-null point
        input.add(1, 0, 1, 1, 1); // pedestrian with a non-null object
        object(input, 0);
        input.add(1, 3, 1);
        TeyesAdasFrontEvent result = TeyesAdasFrontEvent.read(input);
        assertEquals(0x4008, result.warningType);
        assertTrue(result.testEvent);
        assertEquals(Integer.valueOf(2), result.laneState);
        assertEquals(Integer.valueOf(4), result.lineCount);
        assertEquals(Integer.valueOf(9), result.laneChange);
        assertEquals(0, input.remaining());
    }

    private static void object(Fixture data, int stringLength) {
        data.empty(13);
        data.add(3, 0, 1).empty(2).add(1).empty(2); // nullable landmark typed array
        data.empty(3).add(stringLength);
        if (stringLength >= 0) data.empty((stringLength + 2) / 2);
        data.empty(4);
    }

    @Test public void rejectsPartialAndUnknownVersionsWithoutReportingLaneStates() {
        Fixture full = new Fixture().add(1, 8, 1, 0, -1, 0, -1, 1, 2, 0, -1, 0, 0,
                -1, 0, -1, 0, -1, 0, 0, 0);
        for (int length = 0; length < full.words.size(); length++) {
            Fixture truncated = new Fixture();
            truncated.words.addAll(full.words.subList(0, length));
            assertThrows(IllegalArgumentException.class, () -> TeyesAdasFrontEvent.read(truncated));
        }
        assertNotNull(TeyesAdasFrontEvent.read(full));
        full.position = 0;
        full.add(123);
        assertThrows(IllegalArgumentException.class, () -> TeyesAdasFrontEvent.read(full));
    }

    @Test public void rejectsInvalidCountsAndPresence() {
        for (int count : new int[] { -4, Integer.MAX_VALUE }) {
            Fixture input = new Fixture().add(1, 0, 1, 0, 0, 0, count);
            assertThrows(IllegalArgumentException.class, () -> TeyesAdasFrontEvent.read(input));
        }
        assertThrows(IllegalArgumentException.class, () -> TeyesAdasFrontEvent.read(new Fixture().add(2)));
    }
}
