package kia.app.core.model;

public final class RctaState {
    public final boolean left;
    public final boolean right;
    public final long updatedAt;

    public RctaState(boolean left, boolean right, long updatedAt) {
        this.left = left;
        this.right = right;
        this.updatedAt = updatedAt;
    }

    public static RctaState empty() {
        return new RctaState(false, false, 0L);
    }

    public boolean active() {
        return left || right;
    }

    public String summary() {
        if (left && right) return "left+right";
        if (left) return "left";
        if (right) return "right";
        return "clear";
    }
}
