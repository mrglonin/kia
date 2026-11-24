package kia.app.core.model

class AmpState(
    volume: Int,
    balance: Int,
    fader: Int,
    bass: Int,
    mid: Int,
    treble: Int,
    mode: Int,
    updatedAt: Long,
) {
    @JvmField val volume: Int
    @JvmField val balance: Int
    @JvmField val fader: Int
    @JvmField val bass: Int
    @JvmField val mid: Int
    @JvmField val treble: Int
    @JvmField val mode: Int
    @JvmField val updatedAt: Long

    init {
        this.volume = volume
        this.balance = balance
        this.fader = fader
        this.bass = bass
        this.mid = mid
        this.treble = treble
        this.mode = mode
        this.updatedAt = updatedAt
    }

    fun summary(): String {
        return ("vol=" +
            volume +
            " bal=" +
            (balance - 10) +
            " fad=" +
            (fader - 10) +
            " bass=" +
            (bass - 10) +
            " mid=" +
            (mid - 10) +
            " treble=" +
            (treble - 10) +
            " mode=" +
            mode)
    }

    companion object {
        @JvmStatic
        fun empty(): AmpState {
            return AmpState(20, 10, 10, 10, 10, 10, 10, 0L)
        }
    }
}
