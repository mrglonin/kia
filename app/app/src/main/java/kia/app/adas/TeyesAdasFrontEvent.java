package kia.app.adas;

/** The read-only fields of the CC4 front-camera callback. No vendor classes are loaded. */
final class TeyesAdasFrontEvent {
    private static final int LDW = 0x8;
    private static final int LDW_PRE = 0x200;
    private static final int LDW_LEFT = 0x2000;
    private static final int LDW_RIGHT = 0x4000;

    interface Words {
        int remaining();
        int read();
        void skip(int count);
    }

    final int warningType;
    final Integer laneState;
    final Integer lineCount;
    final Integer laneChange;
    final boolean testEvent;

    private TeyesAdasFrontEvent(int warningType, Integer laneState, Integer lineCount, Integer laneChange,
                                boolean testEvent) {
        this.warningType = warningType;
        this.laneState = laneState;
        this.lineCount = lineCount;
        this.laneChange = laneChange;
        this.testEvent = testEvent;
    }

    static TeyesAdasFrontEvent read(Words data) {
        if (!present(data)) {
            requireEnd(data);
            return null;
        }
        int warning = readWord(data);
        Integer laneState = null;
        Integer lineCount = null;
        Integer laneChange = null;
        if (present(data)) {
            skipObjectResult(data); // FCW is written before LDW.
            laneState = readWord(data);
            lineCount = readWord(data);
            laneChange = readWord(data);
            // Three line attributes, two floats and the eight words of AlgoCurveLane.
            skipFixedArray(data, 13);
            skip(data, 2); // laneWidth and distance_line_num
            skipFixedArray(data, 2); // flattened distance_line points
            skipObjectResult(data); // pedestrians
        }
        boolean testEvent = readWord(data) > 0;
        skip(data, 2); // audio type and front pedestrian bumper sound
        requireEnd(data);
        return new TeyesAdasFrontEvent(warning, laneState, lineCount, laneChange, testEvent);
    }

    static boolean hasLaneWarning(int flags) {
        return (flags & (LDW | LDW_PRE | LDW_LEFT | LDW_RIGHT)) != 0;
    }

    // TEYES also sends the generic LDW flag with state 1 for left or 2 for right.
    static int directionFromState(int flags, Integer state) {
        if ((flags & LDW) == 0 || (flags & (LDW_LEFT | LDW_RIGHT)) != 0 || state == null) return 0;
        if (state == 1) return LDW_LEFT;
        if (state == 2) return LDW_RIGHT;
        return 0;
    }

    static String warningText(int flags, Integer laneState) {
        flags |= directionFromState(flags, laneState);
        boolean left = (flags & LDW_LEFT) != 0;
        boolean right = (flags & LDW_RIGHT) != 0;
        if ((flags & LDW) == 0 && (left || right)) {
            if (left && right) return "Наезд на левую и правую линии";
            return left ? "Наезд на левую линию" : "Наезд на правую линию";
        }
        if (left && right) return "Срабатывания слева и справа";
        if (left) return "Выезд влево";
        if (right) return "Выезд вправо";
        if ((flags & LDW) != 0) return "Выезд из полосы";
        if ((flags & LDW_PRE) != 0) return "Предварительное предупреждение о выезде";
        return "Предупреждений о выезде нет";
    }

    private static void skipObjectResult(Words data) {
        skip(data, 3); // object count, primary object and warning
        int count = arraySize(data);
        for (int i = 0; i < count; i++) {
            if (present(data)) {
                // Type, score, collision times, distance, velocity, bounding box and ID.
                skip(data, 13);
                skipFixedArray(data, 2); // landmarks
                skip(data, 3); // rotation, has_plate, plate_length
                int chars = readWord(data); // Parcel.writeString uses padded UTF-16.
                if (chars < -1) throw invalid();
                if (chars >= 0) skip(data, chars / 2 + 1);
                skip(data, 4); // plate bounding box
            }
        }
    }

    private static void skipFixedArray(Words data, int wordsPerItem) {
        int count = arraySize(data);
        for (int i = 0; i < count; i++) {
            if (present(data)) skip(data, wordsPerItem);
        }
    }

    private static int arraySize(Words data) {
        int size = readWord(data);
        // Every element, even a null one, has a presence word.
        if (size < -1 || size > data.remaining()) throw invalid();
        return size;
    }

    private static boolean present(Words data) {
        int value = readWord(data);
        if (value != 0 && value != 1) throw invalid();
        return value != 0;
    }

    private static int readWord(Words data) {
        if (data.remaining() < 1) throw invalid();
        return data.read();
    }

    private static void skip(Words data, int count) {
        if (count > data.remaining()) throw invalid();
        data.skip(count);
    }

    private static void requireEnd(Words data) {
        if (data.remaining() != 0) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Неизвестный формат кадра TEYES ADAS");
    }
}
