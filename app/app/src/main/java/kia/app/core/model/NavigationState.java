package kia.app.core.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NavigationState {
    private static final Pattern DISTANCE_TOKEN = Pattern.compile(
            "[-+]?\\d+(?:[\\.,]\\d+)?\\s*(?:км|km|м|m)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public final boolean active;
    public final boolean finishReached;
    public final boolean speedExceeded;
    public final String maneuver;
    public final String maneuverText;
    public final String distance;
    public final String eta;
    public final String street;
    public final String maneuverDistance;
    public final String routeDistance;
    public final String routeTime;
    public final String arrivalTime;
    public final String currentStreet;
    public final String nextStreet;
    public final String finishStreet;
    public final String speedLimit;
    public final String currentSpeed;
    public final String source;
    public final String mainManeuverId;
    public final String routeActionId;
    public final String microManeuverId;
    public final String microDistance;
    public final String microStatus;
    public final String grayRoadId;
    public final String grayRoadScheme;
    public final String laneHint;
    public final String laneSource;
    public final String clusterVisual;
    public final String clusterTx;
    public final long updatedAt;

    public NavigationState(boolean active, String maneuver, String distance, String eta,
                           String street, String speedLimit, long updatedAt) {
        this(active, false, false, maneuver, "", distance, eta, "", "",
                "", street, "", speedLimit, "", "", updatedAt);
    }

    public NavigationState(boolean active, boolean finishReached, boolean speedExceeded,
                           String maneuver, String maneuverText, String maneuverDistance,
                           String routeDistance, String routeTime, String arrivalTime,
                           String currentStreet, String nextStreet, String finishStreet,
                           String speedLimit, String currentSpeed, String source, long updatedAt) {
        this(active, finishReached, speedExceeded, maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime, currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source, "", "", "", "", "", "", "",
                "", "", "", "", updatedAt);
    }

    private NavigationState(boolean active, boolean finishReached, boolean speedExceeded,
                            String maneuver, String maneuverText, String maneuverDistance,
                            String routeDistance, String routeTime, String arrivalTime,
                            String currentStreet, String nextStreet, String finishStreet,
                            String speedLimit, String currentSpeed, String source,
                            String mainManeuverId, String routeActionId,
                            String microManeuverId, String microDistance,
                            String microStatus,
                            String grayRoadId, String grayRoadScheme,
                            String laneHint, String laneSource, String clusterVisual,
                            String clusterTx, long updatedAt) {
        this.active = active;
        this.finishReached = finishReached;
        this.speedExceeded = speedExceeded;
        this.maneuver = safe(maneuver);
        this.maneuverText = safe(maneuverText);
        this.maneuverDistance = safe(maneuverDistance);
        this.routeDistance = safe(routeDistance);
        this.routeTime = safe(routeTime);
        this.arrivalTime = safe(arrivalTime);
        this.currentStreet = safe(currentStreet);
        this.nextStreet = safe(nextStreet);
        this.finishStreet = safe(finishStreet);
        this.speedLimit = safe(speedLimit);
        this.currentSpeed = safe(currentSpeed);
        this.source = safe(source);
        this.mainManeuverId = safe(mainManeuverId);
        this.routeActionId = safe(routeActionId);
        this.microManeuverId = safe(microManeuverId);
        this.microDistance = safe(microDistance);
        this.microStatus = safe(microStatus);
        this.grayRoadId = safe(grayRoadId);
        this.grayRoadScheme = safe(grayRoadScheme);
        this.laneHint = safe(laneHint);
        this.laneSource = safe(laneSource);
        this.clusterVisual = safe(clusterVisual);
        this.clusterTx = safe(clusterTx);
        this.updatedAt = updatedAt;
        this.distance = this.maneuverDistance;
        this.eta = this.routeDistance;
        this.street = this.active && !this.nextStreet.isEmpty()
                ? this.nextStreet
                : (!this.currentStreet.isEmpty() ? this.currentStreet : this.nextStreet);
    }

    public static NavigationState empty() {
        return new NavigationState(false, false, false, "", "", "", "", "",
                "", "", "", "", "", "", "", 0L);
    }

    public NavigationState withLaneHint(String laneHint, String laneSource, long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, clusterVisual, clusterTx, updatedAt);
    }

    public NavigationState withManeuverDistance(String maneuverDistance, long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, clusterVisual, clusterTx, updatedAt);
    }

    public NavigationState withNavigationDebug(String mainManeuverId, String routeActionId,
                                               String microManeuverId, String grayRoadId,
                                               String grayRoadScheme, long updatedAt) {
        return withNavigationDebug(mainManeuverId, routeActionId, microManeuverId, "",
                grayRoadId, grayRoadScheme, updatedAt);
    }

    public NavigationState withNavigationDebug(String mainManeuverId, String routeActionId,
                                               String microManeuverId, String microDistance,
                                               String grayRoadId, String grayRoadScheme,
                                               long updatedAt) {
        return withNavigationDebug(mainManeuverId, routeActionId, microManeuverId, microDistance,
                "", grayRoadId, grayRoadScheme, updatedAt);
    }

    public NavigationState withNavigationDebug(String mainManeuverId, String routeActionId,
                                               String microManeuverId, String microDistance,
                                               String microStatus, String grayRoadId,
                                               String grayRoadScheme, long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, clusterVisual, clusterTx, updatedAt);
    }

    public NavigationState withClusterVisual(String imageId, int progressBucket,
                                             String distance, long updatedAt) {
        String visual = safe(imageId);
        if (!safe(distance).isEmpty()) visual += " / " + safe(distance);
        visual += " / progress=" + progressBucket;
        return withClusterVisualText(visual, updatedAt);
    }

    public NavigationState withClusterVisualText(String visual, long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, safe(visual), clusterTx, updatedAt);
    }

    public NavigationState withClusterTxText(String tx, long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, clusterVisual, safe(tx), updatedAt);
    }

    public String summary() {
        if (!active) {
            String out = "маршрут выключен";
            if (!currentStreet.isEmpty()) out += " | " + currentStreet;
            if (!speedLimit.isEmpty()) out += " | лимит " + speedLimit;
            return out;
        }
        if (loading()) return "Загрузка маршрута";
        String out = finishReached ? "финиш достигнут" : "маршрут активен";
        if (!routeDistance.isEmpty()) out += " | " + routeDistance;
        if (!arrivalTime.isEmpty()) out += " | прибытие " + arrivalTime;
        if (!maneuverText.isEmpty()) out += " | " + maneuverText;
        if (!maneuverDistance.isEmpty()) out += " через " + maneuverDistance;
        if (!street.isEmpty()) out += " | " + street;
        return out;
    }

    public String details() {
        StringBuilder out = new StringBuilder();
        out.append("Маршрут: ").append(statusText()).append('\n');
        out.append("Длина: ").append(orDash(routeDistance)).append('\n');
        out.append("Время: ").append(orDash(routeTime)).append('\n');
        out.append("Прибытие: ").append(orDash(arrivalTime)).append('\n');
        out.append("Манёвр: ").append(orDash(maneuverText)).append('\n');
        out.append("До манёвра: ").append(orDash(maneuverDistance)).append('\n');
        out.append("Улица сейчас: ").append(orDash(currentStreet)).append('\n');
        out.append("Улица после: ").append(orDash(nextStreet)).append('\n');
        out.append("Адрес финиша: ").append(orDash(finishStreet)).append('\n');
        out.append("Скорость сейчас: ").append(orDash(currentSpeed)).append('\n');
        out.append("Лимит скорости: ").append(orDash(speedLimit)).append('\n');
        out.append("Превышение: ").append(speedExceeded ? "да" : "нет").append('\n');
        out.append("Источник: ").append(orDash(source));
        return out.toString();
    }

    public String overlayDetails() {
        StringBuilder out = new StringBuilder();
        boolean routeMetricsVisible = active && !finishReached;
        out.append("Статус: ").append(statusText()).append('\n');
        out.append("Сейчас: ").append(orDash(currentStreet)).append('\n');
        out.append("После: ").append(orDash(nextStreet)).append('\n');
        out.append("Финиш: ").append(orDash(finishStreet)).append('\n');
        out.append("Осталось: ").append(orDash(routeMetricsVisible ? routeDistance : ""))
                .append("  ETA: ").append(orDash(routeMetricsVisible ? arrivalTime : "")).append('\n');
        out.append("Маршрут всего: ").append(orDash(routeMetricsVisible ? routeTotalDistanceText() : "")).append('\n');

        out.append("Манёвр").append('\n');
        out.append("Показ: ").append(orDash(maneuverText)).append("  До: ")
                .append(orDash(maneuverDistance)).append('\n');
        String roundabout = roundaboutText();
        if (!roundabout.isEmpty()) {
            out.append("Круг: ").append(roundabout).append('\n');
        }

        out.append("Микра").append('\n');
        out.append("Показ: ").append(orDash(microLabel()))
                .append("  До: ").append(orDash(microDistance)).append('\n');
        out.append("Статус: ").append(orDash(first(microStatus, inferredMicroStatus()))).append('\n');

        out.append("Серая дорога").append('\n');
        out.append("Схема: ").append(orDash(grayRoadScheme)).append('\n');

        out.append("События").append('\n');
        out.append("Событие: ").append(orDash(eventHintText())).append('\n');
        out.append("Скорость: ").append(orDash(currentSpeed))
                .append("  Лимит: ").append(orDash(speedLimit))
                .append("  Превышение: ").append(speedExceeded ? "да" : "нет");
        return out.toString();
    }

    public String clusterTxDetails() {
        return clusterTxDetails(false, false);
    }

    public String clusterTxDetails(boolean tbtMode, boolean finishDirectionMode) {
        ClusterTxMode mode = clusterTxMode(tbtMode, finishDirectionMode);
        StringBuilder out = new StringBuilder();
        out.append("Режим TX: ").append(mode.name()).append('\n');
        switch (mode) {
            case FINISH_DIRECTION:
                out.append(clusterCompassTxDetails(true));
                break;
            case NAV_TBT:
                out.append(clusterTbtTxDetails());
                break;
            case NAV_NORMAL:
                out.append(clusterManeuverTxDetails());
                break;
            case COMPASS:
            default:
                out.append(clusterCompassTxDetails(false));
                break;
        }
        return out.toString();
    }

    private ClusterTxMode clusterTxMode(boolean tbtMode, boolean finishDirectionMode) {
        if (!active || finishReached) return ClusterTxMode.COMPASS;
        if (finishDirectionMode) return ClusterTxMode.FINISH_DIRECTION;
        if (tbtMode) return ClusterTxMode.NAV_TBT;
        return ClusterTxMode.NAV_NORMAL;
    }

    private enum ClusterTxMode {
        COMPASS,
        NAV_NORMAL,
        NAV_TBT,
        FINISH_DIRECTION
    }

    private String clusterManeuverTxDetails() {
        StringBuilder out = new StringBuilder();
        String maneuverFrame = first(lastClusterManeuverLine(), clusterVisual);
        out.append("Манёвр: ").append(orDash(clusterYellowText(maneuverFrame))).append('\n');
        out.append("Дорога: ").append(orDash(clusterGrayRoadText(maneuverFrame))).append('\n');
        out.append("До: ").append(orDash(clusterDistanceText(maneuverFrame)))
                .append("  Прогресс: ").append(orDash(clusterProgressText(maneuverFrame))).append('\n');
        out.append("Улица: ").append(orDash(first(clusterTextValue(),
                first(currentStreet, first(nextStreet, finishStreet))))).append('\n');
        out.append("Осталось: ").append(orDash(first(clusterEtaDistanceValue(), routeDistance)))
                .append("  Прибытие: ").append(orDash(first(clusterEtaTimeValue(), arrivalTime))).append('\n');
        out.append("Маршрут всего: ").append(orDash(routeTotalDistanceText())).append('\n');
        out.append("Лимит: ").append(orDash(first(clusterSpeedLimitValue(), speedLimit)))
                .append("  Скорость: ").append(orDash(currentSpeed)).append('\n');
        out.append("Кадр манёвра: ").append(orDash(humanClusterLine(maneuverFrame)));
        return out.toString();
    }

    private String clusterTbtTxDetails() {
        StringBuilder out = new StringBuilder();
        String textLine = lastClusterLineStarting("text=");
        String maneuverFrame = lastClusterManeuverLine();
        out.append("TBT: ").append(orDash(clusterTextAnyValue())).append('\n');
        out.append("Манёвр: ").append(orDash(clusterYellowText(maneuverFrame))).append('\n');
        out.append("До: ").append(orDash(clusterDistanceText(maneuverFrame)))
                .append("  Прогресс: ").append(orDash(clusterProgressText(maneuverFrame))).append('\n');
        out.append("Осталось: ").append(orDash(first(clusterEtaDistanceValue(), routeDistance)))
                .append("  Прибытие: ").append(orDash(first(clusterEtaTimeValue(), arrivalTime))).append('\n');
        out.append("Маршрут всего: ").append(orDash(routeTotalDistanceText())).append('\n');
        out.append("Лимит: ").append(orDash(first(clusterSpeedLimitValue(), speedLimit)))
                .append("  Скорость: ").append(orDash(currentSpeed)).append('\n');
        out.append("Кадр TBT: ").append(orDash(humanClusterLine(first(textLine, maneuverFrame))));
        return out.toString();
    }

    private String clusterCompassTxDetails(boolean finishDirectionMode) {
        StringBuilder out = new StringBuilder();
        String compassFrame = finishDirectionMode
                ? lastClusterLineStarting("finish direction ", "compass step=")
                : lastClusterLineStarting("compass step=");
        out.append("Стрелка: ").append(orDash(clusterCompassStepText(compassFrame))).append('\n');
        if (finishDirectionMode) {
            out.append("До финиша: ").append(orDash(clusterDistanceText(compassFrame))).append('\n');
        }
        out.append("Улица: ").append(orDash(first(clusterTextValue(),
                first(currentStreet, first(nextStreet, finishStreet))))).append('\n');
        if (finishDirectionMode) {
            out.append("Осталось: ").append(orDash(first(clusterEtaDistanceValue(), routeDistance)))
                    .append("  Прибытие: ").append(orDash(first(clusterEtaTimeValue(), arrivalTime))).append('\n');
        }
        out.append("Лимит: ").append(orDash(first(clusterSpeedLimitValue(), speedLimit)))
                .append("  Скорость: ").append(orDash(currentSpeed)).append('\n');
        out.append(finishDirectionMode ? "Кадр стрелки: " : "Кадр компаса: ")
                .append(orDash(humanClusterLine(compassFrame)));
        return out.toString();
    }

    public boolean loading() {
        boolean hasManeuver = !maneuverText.isEmpty() || !maneuverDistance.isEmpty() || !maneuver.isEmpty();
        boolean hasRouteMetrics = !routeDistance.isEmpty() || !routeTime.isEmpty()
                || !arrivalTime.isEmpty();
        if (active && hasRouteMetrics) return false;
        boolean notificationOnly = source.toLowerCase().contains("2gis");
        if (notificationOnly && hasManeuver) return false;
        return active && !finishReached && !hasManeuver;
    }

    private String statusText() {
        if (!active) return "маршрут выключен";
        if (finishReached) return "финиш достигнут";
        if (loading()) return "Загрузка маршрута";
        return "маршрут активен";
    }

    private static String orDash(String value) {
        return safe(value).isEmpty() ? "-" : safe(value);
    }

    private String providerLabel() {
        String all = (source + " " + laneSource).toLowerCase();
        if (all.contains("2gis") || all.contains("2гис")) return "2GIS";
        if (all.contains("yandex") || all.contains("яндекс")) return "Yandex";
        if (all.contains("gps")) return "GPS";
        return "-";
    }

    private String roundaboutText() {
        String value = first(maneuver, first(routeActionId, mainManeuverId));
        String p = safe(value).toLowerCase();
        if (!(p.contains("roundabout") || p.contains("circular") || p.contains("круг"))) return "";
        if (p.contains("exit_1")) return "1-й съезд";
        if (p.contains("exit_2")) return "2-й съезд";
        if (p.contains("exit_3")) return "3-й съезд";
        if (p.contains("exit_4")) return "4-й съезд";
        return "круг без номера съезда";
    }

    private String microLabel() {
        String id = safe(microManeuverId);
        if (id.isEmpty()) return "";
        String label = maneuverLabel(id);
        return label.isEmpty() ? id : label;
    }

    private String inferredMicroStatus() {
        if (safe(microManeuverId).isEmpty()) return "нет данных";
        if (safe(microDistance).isEmpty()) return "есть подсказка, но без дистанции";
        return "есть подсказка";
    }

    private String eventHintText() {
        String source = safe(laneSource).toLowerCase();
        String txEvent = clusterEventTextValue();
        if (!txEvent.isEmpty()) return txEvent;
        if (!(source.contains("direction_sign") || source.contains("road_sign")
                || source.contains("roadsign") || source.contains("event")
                || source.contains("camera") || source.contains("warning"))) {
            return "";
        }
        return humanClusterLine(laneHint);
    }

    private String humanClusterVisual() {
        return humanClusterLine(first(clusterVisual, lastClusterTxLine()));
    }

    private String lastClusterTxLine() {
        String text = safe(clusterTx);
        if (text.isEmpty()) return "";
        String[] lines = text.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = safe(lines[i]);
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    private String lastClusterManeuverLine() {
        return lastClusterLineStarting("maneuver+gray ", "maneuver ", "finish direction ");
    }

    private String clusterTextValue() {
        String text = clusterValueAfter(lastClusterLineStarting("text="), "text=");
        return eventLikeText(text) ? "" : text;
    }

    private String clusterTextAnyValue() {
        return clusterValueAfter(lastClusterLineStarting("text="), "text=");
    }

    private String clusterEventTextValue() {
        String text = clusterValueAfter(lastClusterLineStarting("text="), "text=");
        if (text.isEmpty()) {
            text = clusterValueAfter(lastClusterLineContaining(" text="), "text=");
        }
        return eventLikeText(text) ? text : "";
    }

    private String clusterEtaDistanceValue() {
        return normalizeClusterDistance(clusterValueAfter(lastClusterLineStarting("eta distance="),
                "eta distance="));
    }

    private String clusterEtaTimeValue() {
        return clusterValueAfter(lastClusterLineStarting("eta time="), "eta time=");
    }

    private String clusterSpeedLimitValue() {
        return clusterValueAfter(lastClusterLineStarting("speedLimit="), "speedLimit=");
    }

    private String routeTotalDistanceText() {
        return cleanRouteTotalDistance(clusterValueAfter(lastClusterLineStarting("route total="),
                "route total="));
    }

    private String lastClusterLineStarting(String... prefixes) {
        String text = safe(clusterTx);
        if (text.isEmpty()) return "";
        String[] lines = text.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = safe(lines[i]);
            if (line.isEmpty()) continue;
            for (String prefix : prefixes) {
                if (line.startsWith(prefix)) return line;
            }
        }
        return "";
    }

    private String lastClusterLineContaining(String marker) {
        String text = safe(clusterTx);
        if (text.isEmpty()) return "";
        String[] lines = text.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = safe(lines[i]);
            if (!line.isEmpty() && line.contains(marker)) return line;
        }
        return "";
    }

    private static String clusterYellowText(String value) {
        String id = clusterYellowId(value);
        if (id.isEmpty()) return "";
        if (id.startsWith("finish direction")) return "стрелка к финишу";
        String label = maneuverLabel(id);
        return label.isEmpty() ? humanClusterLine(id) : label;
    }

    private static String clusterGrayRoadText(String value) {
        String id = clusterGrayRoadId(value);
        if (id.isEmpty()) return "";
        String label = grayRoadLabel(id);
        return label.isEmpty() ? humanClusterLine(id) : label;
    }

    private static String clusterDistanceText(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        if (text.contains(" dist=")) {
            return normalizeClusterDistance(tokenAfter(text, " dist="));
        }
        String[] parts = text.split("/");
        return parts.length >= 2 ? normalizeClusterDistance(parts[1]) : "";
    }

    private static String clusterProgressText(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        if (text.contains(" progress=")) return tokenAfter(text, " progress=");
        if (text.contains("progress=")) return tokenAfter(text, "progress=");
        return "";
    }

    private static String clusterCompassStepText(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        if (text.startsWith("compass step=")) return tokenAfter(text, "compass step=");
        if (text.contains(" step=")) return tokenAfter(text, " step=");
        return "";
    }

    private static String clusterYellowId(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        if (text.startsWith("finish direction")) return "finish direction";
        if (text.startsWith("maneuver+gray ")) {
            return before(tokenAfter(text, "maneuver+gray "), " gray=");
        }
        if (text.startsWith("maneuver ")) {
            return before(tokenAfter(text, "maneuver "), " dist=");
        }
        int slash = text.indexOf('/');
        String visual = slash >= 0 ? text.substring(0, slash) : text;
        int plus = visual.indexOf(" + ");
        return safe(plus >= 0 ? visual.substring(0, plus) : visual);
    }

    private static String clusterGrayRoadId(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        if (text.contains(" gray=")) return before(tokenAfter(text, " gray="), " dist=");
        int slash = text.indexOf('/');
        String visual = slash >= 0 ? text.substring(0, slash) : text;
        int plus = visual.indexOf(" + ");
        return plus >= 0 ? safe(visual.substring(plus + 3)) : "";
    }

    private static String clusterValueAfter(String line, String marker) {
        String text = safe(line);
        if (text.isEmpty()) return "";
        int start = text.indexOf(marker);
        if (start < 0) return "";
        return before(text.substring(start + marker.length()), " bytes=");
    }

    private static String cleanRouteTotalDistance(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        Matcher matcher = DISTANCE_TOKEN.matcher(text);
        return matcher.find() ? normalizeClusterDistance(matcher.group()) : "";
    }

    private static boolean eventLikeText(String value) {
        String text = safe(value).toLowerCase();
        return text.contains("превыш") || text.contains("overspeed")
                || text.contains("камера") || text.contains("camera")
                || text.contains("warning") || text.contains("event");
    }

    private static String tokenAfter(String value, String marker) {
        String text = safe(value);
        int start = text.indexOf(marker);
        if (start < 0) return "";
        String tail = text.substring(start + marker.length()).trim();
        int space = tail.indexOf(' ');
        return safe(space >= 0 ? tail.substring(0, space) : tail);
    }

    private static String before(String value, String marker) {
        String text = safe(value);
        int index = text.indexOf(marker);
        return safe(index >= 0 ? text.substring(0, index) : text);
    }

    private static String normalizeClusterDistance(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        text = text.replace(',', '.');
        String lower = text.toLowerCase();
        if (lower.endsWith("km")) {
            return trimClusterNumber(text.substring(0, text.length() - 2)) + " км";
        }
        if (lower.endsWith("m")) {
            return trimClusterNumber(text.substring(0, text.length() - 1)) + " м";
        }
        return safe(text.replace('.', ','));
    }

    private static String trimClusterNumber(String value) {
        String text = safe(value);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text.replace('.', ',');
    }

    private static String grayRoadLabel(String grayRoad) {
        String value = safe(grayRoad);
        if ("context_ra_gray_straight_left_right".equals(value)) return "прямо + налево + направо";
        if ("context_ra_gray_left_right".equals(value)) return "налево + направо";
        if ("context_ra_gray_straight_left_exit_right".equals(value)) return "прямо + налево + съезд направо";
        if ("context_ra_gray_straight_exit_left_right".equals(value)) return "прямо + съезд налево + направо";
        if ("context_ra_gray_left_exit_right".equals(value)) return "налево + съезд направо";
        if ("context_ra_gray_exit_left_right".equals(value)) return "съезд налево + направо";
        if ("context_ra_gray_straight_right".equals(value)) return "прямо + направо";
        if ("context_ra_gray_straight_left".equals(value)) return "прямо + налево";
        if ("context_ra_gray_exit_right".equals(value)) return "прямо + съезд направо";
        if ("context_ra_gray_only_exit_right".equals(value)) return "съезд направо";
        if ("context_ra_gray_straight_exit_left".equals(value)) return "прямо + съезд налево";
        if ("context_ra_gray_exit_left".equals(value)) return "съезд налево";
        if ("context_ra_gray_hard_right".equals(value)) return "резко направо";
        if ("context_ra_gray_hard_left".equals(value)) return "резко налево";
        if ("context_ra_gray_right".equals(value)) return "направо";
        if ("context_ra_gray_left".equals(value)) return "налево";
        if ("context_ra_gray_straight".equals(value)) return "прямо";
        return "";
    }

    private static String humanClusterLine(String value) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        int bytes = text.indexOf(" bytes=");
        if (bytes >= 0) text = text.substring(0, bytes);
        text = text.replace("maneuver+gray", "манёвр + серая");
        text = text.replace("maneuver", "манёвр");
        text = text.replace("finish direction", "стрелка к финишу");
        text = text.replace("compass step=", "компас шаг ");
        text = text.replace("speedLimit=", "лимит ");
        text = text.replace("text=", "текст ");
        text = text.replace("eta distance=", "остаток ");
        text = text.replace("eta time=", "прибытие ");
        text = text.replace("gray=", "серая ");
        text = text.replace("dist=", "до ");
        text = text.replace("progress=", "прогресс ");
        text = text.replace("context_ra_roundabout_exit_1", "круговое, 1-й съезд");
        text = text.replace("context_ra_roundabout_exit_2", "круговое, 2-й съезд");
        text = text.replace("context_ra_roundabout_exit_3", "круговое, 3-й съезд");
        text = text.replace("context_ra_roundabout_exit_4", "круговое, 4-й съезд");
        text = text.replace("context_ra_in_circular_movement", "круговое");
        text = text.replace("context_ra_turn_back_right", "разворот направо");
        text = text.replace("context_ra_turn_back_left", "разворот налево");
        text = text.replace("context_ra_turn_back", "разворот");
        text = text.replace("context_ra_take_right", "держитесь правее");
        text = text.replace("context_ra_take_left", "держитесь левее");
        text = text.replace("context_ra_exit_right", "съезд направо");
        text = text.replace("context_ra_exit_left", "съезд налево");
        text = text.replace("context_ra_hard_turn_right", "резко направо");
        text = text.replace("context_ra_hard_turn_left", "резко налево");
        text = text.replace("context_ra_turn_right", "направо");
        text = text.replace("context_ra_turn_left", "налево");
        text = text.replace("context_ra_forward", "прямо");
        text = text.replace("context_ra_finish", "финиш");
        text = text.replace("context_ra_direction_to_finish", "стрелка к финишу");
        text = text.replace("context_ra_gray_straight_left_right", "серая прямо + налево + направо");
        text = text.replace("context_ra_gray_left_right", "серая налево + направо");
        text = text.replace("context_ra_gray_straight_left_exit_right", "серая прямо + налево + съезд направо");
        text = text.replace("context_ra_gray_straight_exit_left_right", "серая прямо + съезд налево + направо");
        text = text.replace("context_ra_gray_left_exit_right", "серая налево + съезд направо");
        text = text.replace("context_ra_gray_exit_left_right", "серая съезд налево + направо");
        text = text.replace("context_ra_gray_straight_right", "серая прямо + направо");
        text = text.replace("context_ra_gray_straight_left", "серая прямо + налево");
        text = text.replace("context_ra_gray_exit_right", "серая прямо + съезд направо");
        text = text.replace("context_ra_gray_only_exit_right", "серая съезд направо");
        text = text.replace("context_ra_gray_straight_exit_left", "серая прямо + съезд налево");
        text = text.replace("context_ra_gray_exit_left", "серая съезд налево");
        text = text.replace("context_ra_gray_hard_right", "серая резко направо");
        text = text.replace("context_ra_gray_hard_left", "серая резко налево");
        text = text.replace("context_ra_gray_right", "серая направо");
        text = text.replace("context_ra_gray_left", "серая налево");
        text = text.replace("context_ra_gray_straight", "серая прямо");
        return text.trim();
    }

    private static String maneuverLabel(String maneuver) {
        String p = safe(maneuver).toLowerCase();
        if (p.contains("turn_back")) return p.contains("right") ? "разворот направо" : "разворот налево";
        if (p.contains("take_left")) return "держитесь левее";
        if (p.contains("take_right")) return "держитесь правее";
        if (p.contains("exit_left")) return "съезд налево";
        if (p.contains("exit_right")) return "съезд направо";
        if (p.contains("hard_turn_left")) return "резко налево";
        if (p.contains("hard_turn_right")) return "резко направо";
        if (p.contains("turn_left") || p.contains("left")) return "налево";
        if (p.contains("turn_right") || p.contains("right")) return "направо";
        if (p.contains("forward") || p.contains("straight")) return "прямо";
        return "";
    }

    private static String first(String a, String b) {
        return !safe(a).isEmpty() ? safe(a) : safe(b);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
