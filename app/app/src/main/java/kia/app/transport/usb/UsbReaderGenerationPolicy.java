package kia.app.transport.usb;

/** Rejects callbacks and failures from a reader belonging to an older USB connection. */
final class UsbReaderGenerationPolicy {
    private UsbReaderGenerationPolicy() {
    }

    static boolean current(boolean readRunning, boolean samePort,
                           long readerGeneration, long activeGeneration) {
        return readRunning && samePort && readerGeneration == activeGeneration;
    }
}
