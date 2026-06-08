package kia.app.core.model;

public final class AmpState {
    public final int volume;
    public final int balance;
    public final int fader;
    public final int bass;
    public final int mid;
    public final int treble;
    public final int mode;
    public final long updatedAt;

    public AmpState(int volume, int balance, int fader, int bass, int mid, int treble,
                    int mode, long updatedAt) {
        this.volume = volume;
        this.balance = balance;
        this.fader = fader;
        this.bass = bass;
        this.mid = mid;
        this.treble = treble;
        this.mode = mode;
        this.updatedAt = updatedAt;
    }

    public static AmpState empty() {
        return new AmpState(20, 10, 10, 10, 10, 10, 10, 0L);
    }

    public String summary() {
        return "vol=" + volume + " bal=" + (balance - 10) + " fad=" + (fader - 10)
                + " bass=" + (bass - 10) + " mid=" + (mid - 10)
                + " treble=" + (treble - 10) + " mode=" + mode;
    }
}
