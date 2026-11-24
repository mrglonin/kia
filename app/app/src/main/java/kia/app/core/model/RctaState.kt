package kia.app.core.model

class RctaState(left: Boolean, right: Boolean, updatedAt: Long) {
    @JvmField val left: Boolean
    @JvmField val right: Boolean
    @JvmField val updatedAt: Long

    init {
        this.left = left
        this.right = right
        this.updatedAt = updatedAt
    }

    fun active(): Boolean {
        return left || right
    }

    fun summary(): String {
        if (left && right) return "left+right"
        if (left) return "left"
        if (right) return "right"
        return "clear"
    }

    companion object {
        @JvmStatic
        fun empty(): RctaState {
            return RctaState(false, false, 0L)
        }
    }
}
