package kia.app.transport.usb

/** Rejects callbacks and failures from a reader belonging to an older USB connection. */
internal object UsbReaderGenerationPolicy {
    @JvmStatic
    fun current(
        readRunning: Boolean,
        samePort: Boolean,
        readerGeneration: Long,
        activeGeneration: Long,
    ): Boolean {
        return readRunning && samePort && readerGeneration == activeGeneration
    }
}
