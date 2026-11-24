package kia.app.transport.usb

/**
 * Serializes publication/queue replay with live USB writes.
 *
 * The connection path holds this gate from before publishing the new port until the offline queue
 * is drained. A live producer which observes that port can therefore only write after every older
 * replay frame. The monitor is reentrant because queue replay uses the regular write path.
 */
internal class UsbWriteOrderGate {
    internal fun interface Operation<T> {
        fun run(): T
    }

    @kotlin.jvm.Synchronized
    fun <T> run(operation: Operation<T>): T {
        return operation.run()
    }
}
