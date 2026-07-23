package kia.app.navigation.domain;

import java.util.Objects;

/** Immutable semantic key for a cluster maneuver frame. */
final class NavigationTxKey {
    final String maneuver;
    final String grayRoad;
    final float distance;
    final boolean km;
    final int progress;

    NavigationTxKey(String maneuver, String grayRoad, float distance, boolean km, int progress) {
        this.maneuver = clean(maneuver);
        this.grayRoad = clean(grayRoad);
        this.distance = distance;
        this.km = km;
        this.progress = progress;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NavigationTxKey)) return false;
        NavigationTxKey that = (NavigationTxKey) other;
        return Float.compare(distance, that.distance) == 0
                && km == that.km
                && progress == that.progress
                && maneuver.equals(that.maneuver)
                && grayRoad.equals(that.grayRoad);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maneuver, grayRoad, distance, km, progress);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
