package kia.app.navigation.domain

object NavigationOutputMode {
    const val NORMAL: Int = 0
    const val TBT: Int = 1
    const val FINISH_DIRECTION: Int = 2

    @JvmStatic
    fun normalize(mode: Int): Int {
        if (mode == NavigationOutputMode.TBT || mode == NavigationOutputMode.FINISH_DIRECTION)
            return mode
        return NavigationOutputMode.NORMAL
    }

    @JvmStatic
    fun isNormal(mode: Int): Boolean {
        return NavigationOutputMode.normalize(mode) == NavigationOutputMode.NORMAL
    }

    @JvmStatic
    fun isTbt(mode: Int): Boolean {
        return NavigationOutputMode.normalize(mode) == NavigationOutputMode.TBT
    }

    @JvmStatic
    fun isFinishDirection(mode: Int): Boolean {
        return NavigationOutputMode.normalize(mode) == NavigationOutputMode.FINISH_DIRECTION
    }

    @JvmStatic
    fun label(mode: Int): String {
        when (NavigationOutputMode.normalize(mode)) {
            NavigationOutputMode.TBT -> return "TBT"
            NavigationOutputMode.FINISH_DIRECTION -> return "стрелка к флагу"
            NavigationOutputMode.NORMAL -> return "обычный"
            else -> return "обычный"
        }
    }
}
