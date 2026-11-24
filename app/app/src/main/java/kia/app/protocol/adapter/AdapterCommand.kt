package kia.app.protocol.adapter

class AdapterCommand(label: String?, frame: ByteArray?, quiet: Boolean, firmwareUpdate: Boolean) {
    @JvmField val label: String
    @JvmField val frame: ByteArray?
    @JvmField val quiet: Boolean
    @JvmField val firmwareUpdate: Boolean

    constructor(
        label: String?,
        frame: ByteArray?,
        quiet: Boolean,
    ) : this(label, frame, quiet, false)

    init {
        this.label = if (label == null) "" else label
        this.frame = frame
        this.quiet = quiet
        this.firmwareUpdate = firmwareUpdate
    }

    companion object {
        @JvmStatic
        fun loud(label: String?, frame: ByteArray?): AdapterCommand {
            return AdapterCommand(label, frame, false)
        }

        @JvmStatic
        fun quiet(label: String?, frame: ByteArray?): AdapterCommand {
            return AdapterCommand(label, frame, true)
        }

        @JvmStatic
        fun firmware(label: String?, frame: ByteArray?, quiet: Boolean): AdapterCommand {
            return AdapterCommand(label, frame, quiet, true)
        }
    }
}
