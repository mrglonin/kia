package kia.app.navigation.compass

/** Pure transition policy for reasserting the compass after cluster/USB ownership changes. */
internal object CompassTxPolicy {
    @JvmStatic
    fun shouldReassert(
        sendAllowed: Boolean,
        previouslySendAllowed: Boolean,
        usbReady: Boolean,
        connectionEpoch: Long,
        previousConnectionEpoch: Long,
    ): Boolean {
        return sendAllowed &&
            usbReady &&
            (!previouslySendAllowed || connectionEpoch != previousConnectionEpoch)
    }

    @JvmStatic
    fun shouldKeepAlive(
        sendAllowed: Boolean,
        usbReady: Boolean,
        nowElapsedMs: Long,
        lastSuccessfulTxElapsedMs: Long,
        keepAliveMs: Long,
    ): Boolean {
        return sendAllowed &&
            usbReady &&
            nowElapsedMs > 0L &&
            lastSuccessfulTxElapsedMs > 0L &&
            lastSuccessfulTxElapsedMs <= nowElapsedMs &&
            keepAliveMs >= 0L &&
            nowElapsedMs - lastSuccessfulTxElapsedMs >= keepAliveMs
    }

    @JvmStatic
    fun retryAllowed(
        step: Int,
        lastAttemptedStep: Int,
        nowElapsedMs: Long,
        lastAttemptElapsedMs: Long,
        retryIntervalMs: Long,
    ): Boolean {
        if (step != lastAttemptedStep || lastAttemptElapsedMs <= 0L) return true
        return retryIntervalMs >= 0L &&
            lastAttemptElapsedMs <= nowElapsedMs &&
            nowElapsedMs - lastAttemptElapsedMs >= retryIntervalMs
    }

    @JvmStatic
    fun headingStep(heading: Float, kiaNaviFormat: Boolean): Int {
        var normalized: Float = heading % 360f
        if (normalized < 0f) normalized += 360f
        val degreesPerSector: Float = if (kiaNaviFormat) 22.5f else 30f
        return CompassTxPolicy.normalizeStep(
            Math.round(normalized / degreesPerSector) * 3,
            kiaNaviFormat,
        )
    }

    @JvmStatic
    fun nextStep(current: Int, target: Int, kiaNaviFormat: Boolean): Int {
        val fullCircle: Int = if (kiaNaviFormat) 48 else 36
        val forward: Int = (target - current + fullCircle) % fullCircle
        val backward: Int = (current - target + fullCircle) % fullCircle
        if (forward == 0) return target
        return CompassTxPolicy.normalizeStep(
            current + (if (forward <= backward) 3 else -3),
            kiaNaviFormat,
        )
    }

    @JvmStatic
    fun storedStep(clusterTx: String?, kiaNaviFormat: Boolean): Int {
        val text = if (clusterTx == null) "" else clusterTx.trim()
        if (text.isEmpty()) return -1
        val lines = java.util.regex.Pattern.compile("\\n").split(text)
        for (i in lines.indices.reversed()) {
            val line = if (lines.get(i) == null) "" else lines.get(i)!!.trim()
            if (!line.startsWith("compass step=") || !line.endsWith(" outcome=WRITTEN")) continue
            // Only the last delivered frame can describe the direction actually on the cluster.
            if (line.contains(" format=kia-navi ") != kiaNaviFormat) return -1
            val tail = line.substring("compass step=".length).trim()
            val space: Int = tail.indexOf(' ')
            val token = if (space >= 0) tail.substring(0, space).trim() else tail
            try {
                return CompassTxPolicy.normalizeStep(Integer.parseInt(token), kiaNaviFormat)
            } catch (ignored: RuntimeException) {
                return -1
            }
        }
        return -1
    }

    private fun normalizeStep(step: Int, kiaNaviFormat: Boolean): Int {
        val fullCircle: Int = if (kiaNaviFormat) 48 else 36
        var out: Int = ((step % fullCircle) + fullCircle) % fullCircle
        out = Math.round(out / 3f) * 3
        return if (out == fullCircle) 0 else out
    }
}
