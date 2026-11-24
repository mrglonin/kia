package kia.app.core.model

class CallState(
    active: Boolean,
    name: String?,
    phone: String?,
    source: String?,
    startedAt: Long,
    updatedAt: Long,
) {
    @JvmField val active: Boolean
    @JvmField val name: String
    @JvmField val phone: String
    @JvmField val source: String
    @JvmField val startedAt: Long
    @JvmField val updatedAt: Long

    init {
        this.active = active
        this.name = CallState.safe(name)
        this.phone = CallState.safe(phone)
        this.source = CallState.safe(source)
        this.startedAt = startedAt
        this.updatedAt = updatedAt
    }

    fun displayName(): String {
        if (!name.isEmpty()) return name
        return "BT звонок"
    }

    fun subtitle(): String {
        if (!phone.isEmpty()) return phone
        return if (source.isEmpty()) "BT Audio" else source
    }

    fun elapsedText(now: Long): String {
        if (!active || startedAt <= 0L) return "0:00"
        val total: Long = Math.max(0L, (now - startedAt) / 1000L)
        val minutes: Long = total / 60L
        val seconds: Long = total % 60L
        return minutes.toString() + ":" + (if (seconds < 10) "0" else "") + seconds
    }

    fun panelTitle(): String {
        return displayName()
    }

    fun panelSubtitle(now: Long): String {
        val phoneText = subtitle()
        val time: String = elapsedText(now)
        return if (phoneText.isEmpty()) time else phoneText + " · " + time
    }

    fun clusterLine(now: Long): String {
        val identity: String =
            if (!name.isEmpty()) name else (if (!phone.isEmpty()) phone else "BT звонок")
        val time: String = elapsedText(now)
        val maxIdentity: Int = Math.max(0, CallState.CLUSTER_TEXT_MAX - time.length - 1)
        val shortIdentity = CallState.shorten(identity, maxIdentity)
        return if (shortIdentity.isEmpty()) time else shortIdentity + " " + time
    }

    fun summary(now: Long): String {
        if (!active) return "нет активного звонка"
        return displayName() + "\n" + panelSubtitle(now)
    }

    companion object {
        private const val CLUSTER_TEXT_MAX: Int = 28

        @JvmStatic
        fun empty(): CallState {
            return CallState(false, "", "", "", 0L, 0L)
        }

        @JvmStatic
        fun active(name: String?, phone: String?, source: String?, startedAt: Long): CallState {
            val now: Long = System.currentTimeMillis()
            val start: Long = if (startedAt > 0L) startedAt else now
            return CallState(true, name, phone, source, start, now)
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value.trim()
        }

        private fun shorten(value: String?, max: Int): String {
            val text: String = CallState.safe(value)
            if (text.length <= max) return text
            if (max <= 0) return ""
            if (max == 1) return text.substring(0, 1)
            return text.substring(0, max - 1) + "…"
        }
    }
}
