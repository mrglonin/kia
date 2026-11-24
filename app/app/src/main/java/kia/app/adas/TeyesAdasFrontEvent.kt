package kia.app.adas

/** The read-only fields of the CC4 front-camera callback. No vendor classes are loaded. */
class TeyesAdasFrontEvent
private constructor(
    warningType: Int,
    laneState: Int?,
    lineCount: Int?,
    laneChange: Int?,
    testEvent: Boolean,
) {
    interface Words {
        fun remaining(): Int

        fun read(): Int

        fun skip(count: Int)
    }

    @JvmField val warningType: Int
    @JvmField val laneState: Int?
    @JvmField val lineCount: Int?
    @JvmField val laneChange: Int?
    @JvmField val testEvent: Boolean

    init {
        this.warningType = warningType
        this.laneState = laneState
        this.lineCount = lineCount
        this.laneChange = laneChange
        this.testEvent = testEvent
    }

    companion object {
        private const val LDW: Int = 0x8
        private const val LDW_PRE: Int = 0x200
        private const val LDW_LEFT: Int = 0x2000
        private const val LDW_RIGHT: Int = 0x4000

        @JvmStatic
        fun read(data: Words): TeyesAdasFrontEvent? {
            if (!TeyesAdasFrontEvent.present(data)) {
                TeyesAdasFrontEvent.requireEnd(data)
                return null
            }
            val warning: Int = TeyesAdasFrontEvent.readWord(data)
            var laneState: Int? = null
            var lineCount: Int? = null
            var laneChange: Int? = null
            if (TeyesAdasFrontEvent.present(data)) {
                TeyesAdasFrontEvent.skipObjectResult(data) // FCW is written before LDW.
                laneState = TeyesAdasFrontEvent.readWord(data)
                lineCount = TeyesAdasFrontEvent.readWord(data)
                laneChange = TeyesAdasFrontEvent.readWord(data)
                // Three line attributes, two floats and the eight words of AlgoCurveLane.
                TeyesAdasFrontEvent.skipFixedArray(data, 13)
                TeyesAdasFrontEvent.skip(data, 2) // laneWidth and distance_line_num
                TeyesAdasFrontEvent.skipFixedArray(data, 2) // flattened distance_line points
                TeyesAdasFrontEvent.skipObjectResult(data) // pedestrians
            }
            val testEvent: Boolean = TeyesAdasFrontEvent.readWord(data) > 0
            TeyesAdasFrontEvent.skip(data, 2) // audio type and front pedestrian bumper sound
            TeyesAdasFrontEvent.requireEnd(data)
            return TeyesAdasFrontEvent(warning, laneState, lineCount, laneChange, testEvent)
        }

        @JvmStatic
        fun hasLaneWarning(flags: Int): Boolean {
            return (flags and
                (TeyesAdasFrontEvent.LDW or
                    TeyesAdasFrontEvent.LDW_PRE or
                    TeyesAdasFrontEvent.LDW_LEFT or
                    TeyesAdasFrontEvent.LDW_RIGHT)) != 0
        }

        @JvmStatic // TEYES also sends the generic LDW flag with state 1 for left or 2 for right.
        fun directionFromState(flags: Int, state: Int?): Int {
            if (
                (flags and TeyesAdasFrontEvent.LDW) == 0 ||
                    (flags and (TeyesAdasFrontEvent.LDW_LEFT or TeyesAdasFrontEvent.LDW_RIGHT)) !=
                        0 ||
                    state == null
            )
                return 0
            if (state == 1) return TeyesAdasFrontEvent.LDW_LEFT
            if (state == 2) return TeyesAdasFrontEvent.LDW_RIGHT
            return 0
        }

        @JvmStatic
        fun warningText(flags: Int, laneState: Int?): String? {
            var flags: Int = flags
            flags = flags or TeyesAdasFrontEvent.directionFromState(flags, laneState)
            val left: Boolean = (flags and TeyesAdasFrontEvent.LDW_LEFT) != 0
            val right: Boolean = (flags and TeyesAdasFrontEvent.LDW_RIGHT) != 0
            if ((flags and TeyesAdasFrontEvent.LDW) == 0 && (left || right)) {
                if (left && right) return "Наезд на левую и правую линии"
                return if (left) "Наезд на левую линию" else "Наезд на правую линию"
            }
            if (left && right) return "Срабатывания слева и справа"
            if (left) return "Выезд влево"
            if (right) return "Выезд вправо"
            if ((flags and TeyesAdasFrontEvent.LDW) != 0) return "Выезд из полосы"
            if ((flags and TeyesAdasFrontEvent.LDW_PRE) != 0)
                return "Предварительное предупреждение о выезде"
            return "Предупреждений о выезде нет"
        }

        private fun skipObjectResult(data: Words) {
            TeyesAdasFrontEvent.skip(data, 3) // object count, primary object and warning
            val count: Int = TeyesAdasFrontEvent.arraySize(data)
            for (i in 0..<count) {
                if (TeyesAdasFrontEvent.present(data)) {
                    // Type, score, collision times, distance, velocity, bounding box and ID.
                    TeyesAdasFrontEvent.skip(data, 13)
                    TeyesAdasFrontEvent.skipFixedArray(data, 2) // landmarks
                    TeyesAdasFrontEvent.skip(data, 3) // rotation, has_plate, plate_length
                    val chars: Int =
                        TeyesAdasFrontEvent.readWord(data) // Parcel.writeString uses padded UTF-16.
                    if (chars < -1) throw TeyesAdasFrontEvent.invalid()
                    if (chars >= 0) TeyesAdasFrontEvent.skip(data, chars / 2 + 1)
                    TeyesAdasFrontEvent.skip(data, 4) // plate bounding box
                }
            }
        }

        private fun skipFixedArray(data: Words, wordsPerItem: Int) {
            val count: Int = TeyesAdasFrontEvent.arraySize(data)
            for (i in 0..<count) {
                if (TeyesAdasFrontEvent.present(data)) TeyesAdasFrontEvent.skip(data, wordsPerItem)
            }
        }

        private fun arraySize(data: Words): Int {
            val size: Int = TeyesAdasFrontEvent.readWord(data)
            // Every element, even a null one, has a presence word.
            if (size < -1 || size > data.remaining()) throw TeyesAdasFrontEvent.invalid()
            return size
        }

        private fun present(data: Words): Boolean {
            val value: Int = TeyesAdasFrontEvent.readWord(data)
            if (value != 0 && value != 1) throw TeyesAdasFrontEvent.invalid()
            return value != 0
        }

        private fun readWord(data: Words): Int {
            if (data.remaining() < 1) throw TeyesAdasFrontEvent.invalid()
            return data.read()
        }

        private fun skip(data: Words, count: Int) {
            if (count > data.remaining()) throw TeyesAdasFrontEvent.invalid()
            data.skip(count)
        }

        private fun requireEnd(data: Words) {
            if (data.remaining() != 0) throw TeyesAdasFrontEvent.invalid()
        }

        private fun invalid(): IllegalArgumentException {
            return IllegalArgumentException("Неизвестный формат кадра TEYES ADAS")
        }
    }
}
