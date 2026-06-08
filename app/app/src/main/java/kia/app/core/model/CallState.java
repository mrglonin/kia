package kia.app.core.model;

public final class CallState {
    private static final int CLUSTER_TEXT_MAX = 28;

    public final boolean active;
    public final String name;
    public final String phone;
    public final String source;
    public final long startedAt;
    public final long updatedAt;

    public CallState(boolean active, String name, String phone, String source,
                     long startedAt, long updatedAt) {
        this.active = active;
        this.name = safe(name);
        this.phone = safe(phone);
        this.source = safe(source);
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
    }

    public static CallState empty() {
        return new CallState(false, "", "", "", 0L, 0L);
    }

    public static CallState active(String name, String phone, String source, long startedAt) {
        long now = System.currentTimeMillis();
        long start = startedAt > 0L ? startedAt : now;
        return new CallState(true, name, phone, source, start, now);
    }

    public String displayName() {
        if (!name.isEmpty()) return name;
        return "BT звонок";
    }

    public String subtitle() {
        if (!phone.isEmpty()) return phone;
        return source.isEmpty() ? "BT Audio" : source;
    }

    public String elapsedText(long now) {
        if (!active || startedAt <= 0L) return "0:00";
        long total = Math.max(0L, (now - startedAt) / 1000L);
        long minutes = total / 60L;
        long seconds = total % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    public String panelTitle() {
        return displayName();
    }

    public String panelSubtitle(long now) {
        String phoneText = subtitle();
        String time = elapsedText(now);
        return phoneText.isEmpty() ? time : phoneText + " · " + time;
    }

    public String clusterLine(long now) {
        String identity = !name.isEmpty() ? name : (!phone.isEmpty() ? phone : "BT звонок");
        String time = elapsedText(now);
        int maxIdentity = Math.max(0, CLUSTER_TEXT_MAX - time.length() - 1);
        String shortIdentity = shorten(identity, maxIdentity);
        return shortIdentity.isEmpty() ? time : shortIdentity + " " + time;
    }

    public String summary(long now) {
        if (!active) return "нет активного звонка";
        return displayName() + "\n" + panelSubtitle(now);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String shorten(String value, int max) {
        String text = safe(value);
        if (text.length() <= max) return text;
        if (max <= 0) return "";
        if (max == 1) return text.substring(0, 1);
        return text.substring(0, max - 1) + "…";
    }
}
