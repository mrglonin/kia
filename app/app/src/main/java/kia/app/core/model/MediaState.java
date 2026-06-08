package kia.app.core.model;

public final class MediaState {
    public final String source;
    public final String packageName;
    public final String artist;
    public final String title;
    public final long durationMs;
    public final boolean playing;
    public final long updatedAt;

    public MediaState(String source, String packageName, String artist, String title,
                      long durationMs, long updatedAt) {
        this(source, packageName, artist, title, durationMs, true, updatedAt);
    }

    public MediaState(String source, String packageName, String artist, String title,
                      long durationMs, boolean playing, long updatedAt) {
        this.source = safe(source);
        this.packageName = safe(packageName);
        this.artist = safe(artist);
        this.title = safe(title);
        this.durationMs = durationMs;
        this.playing = playing;
        this.updatedAt = updatedAt;
    }

    public static MediaState empty() {
        return new MediaState("", "", "", "", -1L, false, 0L);
    }

    public String summary() {
        if (source.isEmpty() && artist.isEmpty() && title.isEmpty()) return "нет данных";
        String cleanArtist = artist.isEmpty() || "<unknown>".equalsIgnoreCase(artist) ? "-" : artist;
        String cleanTitle = title.isEmpty() ? "-" : title;
        return "Мультимедиа: " + sourceCode()
                + " | " + cleanArtist
                + " | " + cleanTitle
                + " | " + formatDuration(durationMs)
                + " | " + (playing ? "играет" : "не играет");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String formatDuration(long value) {
        if (value < 0L) return "-";
        long seconds = value / 1000L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return minutes + ":" + (rest < 10 ? "0" : "") + rest;
    }

    private String sourceCode() {
        String value = (source + " " + packageName).toLowerCase();
        String sourceValue = source.toLowerCase();
        if (value.contains("usb") || value.contains("local_music") || value.contains("spd.media")) return "USB";
        if (value.contains("bluetooth") || value.contains("bt") || value.contains("a2dp") || value.contains("avrcp")) return "BT";
        if (value.contains("am")) return "AM";
        if (sourceValue.contains("teyes online") || sourceValue.equals("teyes")) return "TEYES";
        if (value.contains("fm") || value.contains("radio") || value.contains("радио")) return "FM";
        return "OTHER";
    }
}
