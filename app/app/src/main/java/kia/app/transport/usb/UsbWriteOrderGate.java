package kia.app.transport.usb;

/**
 * Serializes publication/queue replay with live USB writes.
 *
 * <p>The connection path holds this gate from before publishing the new port until the offline
 * queue is drained. A live producer which observes that port can therefore only write after every
 * older replay frame. The monitor is reentrant because queue replay uses the regular write path.
 */
final class UsbWriteOrderGate {
    interface Operation<T> {
        T run();
    }

    synchronized <T> T run(Operation<T> operation) {
        return operation.run();
    }
}
