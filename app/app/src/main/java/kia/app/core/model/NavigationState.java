package kia.app.core.model;

import java.util.Locale;
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
    public final String eventHint;
    public final String eventSource;
    public final String clusterVisual;
    public final String clusterTx;
    public final String laneRaw;
    public final String lanePosition;
    public final String roadSchemeRaw;
    public final String upcomingRaw;
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
                "", "", "", "", "", "", "", "", "", "", updatedAt);
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
                            String laneHint, String laneSource,
                            String eventHint, String eventSource,
                            String clusterVisual, String clusterTx,
                            String laneRaw, String lanePosition,
                            String roadSchemeRaw, String upcomingRaw,
                            long updatedAt) {
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
        this.eventHint = safe(eventHint);
        this.eventSource = safe(eventSource);
        this.clusterVisual = safe(clusterVisual);
        this.clusterTx = safe(clusterTx);
        this.laneRaw = safe(laneRaw);
        this.lanePosition = safe(lanePosition);
        this.roadSchemeRaw = safe(roadSchemeRaw);
        this.upcomingRaw = safe(upcomingRaw);
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
                laneHint, laneSource, eventHint, eventSource, clusterVisual, clusterTx,
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
    }

    public NavigationState withEventHint(String eventHint, String eventSource, long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, eventHint, eventSource, clusterVisual, clusterTx,
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
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
                laneHint, laneSource, eventHint, eventSource, clusterVisual, clusterTx,
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
    }

    public NavigationState withMainManeuver(String maneuver, String maneuverText,
                                            String maneuverDistance, String source,
                                            long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, eventHint, eventSource, clusterVisual, clusterTx,
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
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
                laneHint, laneSource, eventHint, eventSource, clusterVisual, clusterTx,
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
    }

    public NavigationState withNavigationRaw(String laneRaw, String lanePosition,
                                             String roadSchemeRaw, String upcomingRaw,
                                             long updatedAt) {
        return new NavigationState(active, finishReached, speedExceeded,
                maneuver, maneuverText, maneuverDistance,
                routeDistance, routeTime, arrivalTime,
                currentStreet, nextStreet, finishStreet,
                speedLimit, currentSpeed, source,
                mainManeuverId, routeActionId, microManeuverId, microDistance,
                microStatus,
                grayRoadId, grayRoadScheme,
                laneHint, laneSource, eventHint, eventSource, clusterVisual, clusterTx,
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
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
                laneHint, laneSource, eventHint, eventSource, safe(visual), clusterTx,
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
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
                laneHint, laneSource, eventHint, eventSource, clusterVisual, safe(tx),
                laneRaw, lanePosition, roadSchemeRaw, upcomingRaw, updatedAt);
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
        out.append("Источник: ").append(orDash(source)).append('\n');
        out.append("Основной ID: ").append(orDash(humanManeuverId(first(maneuver, mainManeuverId)))).append('\n');
        out.append("Route action: ").append(orDash(humanManeuverId(routeActionId))).append('\n');
        out.append("Micro: ").append(orDash(microDebugLine())).append('\n');
        out.append("TX-схема: ").append(orDash(txGrayRoadText())).append('\n');
        out.append("Полосы Яндекса: ").append(orDash(yandexLaneDirectionsText())).append('\n');
        out.append("Подсветка: ").append(orDash(laneHighlightText())).append('\n');
        out.append("До подсказки: ").append(orDash(laneDistanceDebugText())).append('\n');
        out.append("Ближайшие подсказки: ").append(orDash(upcomingLaneHintsText())).append('\n');
        out.append("События: ").append(orDash(first(eventHintText(), upcomingEventText())));
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
        out.append("TX-схема: ").append(orDash(txGrayRoadText())).append('\n');
        out.append("Полосы Яндекса: ").append(orDash(yandexLaneDirectionsText())).append('\n');
        out.append("Подсветка: ").append(orDash(laneHighlightText())).append('\n');
        out.append("До подсказки: ").append(orDash(laneDistanceDebugText())).append('\n');
        out.append("Ближайшие: ").append(orDash(upcomingLaneHintsText())).append('\n');

        out.append("События").append('\n');
        out.append("Событие: ").append(orDash(first(eventHintText(), upcomingEventText()))).append('\n');
        out.append("Ближайшие: ").append(orDash(upcomingEventText())).append('\n');
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
        if (!active) return ClusterTxMode.COMPASS;
        if (finishReached) return ClusterTxMode.NAV_NORMAL;
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
        boolean notificationOnly = source.toLowerCase(Locale.ROOT).contains("2gis");
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
        String all = (source + " " + laneSource).toLowerCase(Locale.ROOT);
        if (all.contains("2gis") || all.contains("2гис")) return "2GIS";
        if (all.contains("yandex") || all.contains("яндекс")) return "Yandex";
        if (all.contains("gps")) return "GPS";
        return "-";
    }

    private String roundaboutText() {
        String value = first(maneuver, first(routeActionId, mainManeuverId));
        String p = safe(value).toLowerCase(Locale.ROOT);
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

    private String microDebugLine() {
        String label = microLabel();
        if (label.isEmpty()) return "";
        StringBuilder out = new StringBuilder(label);
        if (!safe(microDistance).isEmpty()) out.append(", ").append(microDistance);
        String status = first(microStatus, inferredMicroStatus());
        if (!status.isEmpty()) out.append(", ").append(status);
        return out.toString();
    }

    private String txGrayRoadText() {
        return first(grayRoadScheme, grayRoadLabel(grayRoadId));
    }

    private String yandexLaneDirectionsText() {
        return first(humanDirectionsText(roadSchemeRaw), humanDirectionsText(laneRaw));
    }

    private String laneHighlightText() {
        String token = first(fieldAfter(lanePosition, "highlight="),
                fieldAfter(laneRaw, "highlight="));
        token = first(token, fieldAfter(laneRaw, "highlighted_direction="));
        token = first(token, fieldAfter(laneRaw, "lane_highlight="));
        token = first(token, fieldAfter(roadSchemeRaw, "highlight="));
        return humanDirectionToken(token);
    }

    private String laneDistanceDebugText() {
        return first(microDistance, first(fieldAfter(lanePosition, "dist="),
                firstDistanceToken(first(lanePosition, laneRaw))));
    }

    private String upcomingLaneHintsText() {
        return upcomingText(false);
    }

    private String upcomingEventText() {
        return upcomingText(true);
    }

    private String upcomingText(boolean eventsOnly) {
        String raw = safe(upcomingRaw);
        if (raw.isEmpty()) return "";
        String normalized = raw.replace('\n', '|');
        String[] parts = normalized.split("\\|\\||;");
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            String item = humanUpcomingItem(part, eventsOnly);
            if (item.isEmpty()) continue;
            if (out.length() > 0) out.append("; ");
            out.append(item);
            count++;
            if (count >= 3) break;
        }
        return out.toString();
    }

    private static String humanUpcomingItem(String value, boolean eventMode) {
        String text = safe(value);
        if (text.isEmpty()) return "";
        boolean event = eventLikeText(text) || containsAnyLower(text,
                "camera", "камера", "accident", "дтп", "road_work", "works",
                "ремонт", "warning", "event", "police", "полиция", "дпс");
        if (eventMode && !event) return "";
        if (!eventMode && event) return "";
        String distance = firstDistanceToken(text);
        String label = event ? humanEventLabel(text)
                : first(humanDirectionToken(fieldAfter(text, "highlight=")),
                humanDirectionsText(text));
        if (label.isEmpty()) label = event ? "событие" : "подсказка";
        return distance.isEmpty() ? label : label + ", " + distance;
    }

    private static String humanEventLabel(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT);
        if (text.contains("lane") || text.contains("полос")) return "камера на полосу";
        if (text.contains("speed") || text.contains("limit") || text.contains("скор")) {
            return "камера скорости";
        }
        if (text.contains("camera") || text.contains("камера")) return "камера";
        if (text.contains("accident") || text.contains("дтп")) return "ДТП";
        if (text.contains("road_work") || text.contains("works") || text.contains("ремонт")) {
            return "ремонт";
        }
        if (text.contains("police") || text.contains("дпс") || text.contains("полиция")) {
            return "пост ДПС";
        }
        return "событие";
    }

    private String inferredMicroStatus() {
        if (safe(microManeuverId).isEmpty()) return "нет данных";
        if (safe(microDistance).isEmpty()) return "есть подсказка, но без дистанции";
        return "есть подсказка";
    }

    private String eventHintText() {
        String txEvent = clusterEventTextValue();
        if (!txEvent.isEmpty()) return txEvent;
        String explicit = humanClusterLine(eventHint);
        if (!explicit.isEmpty()) return explicit;
        String source = (safe(laneSource) + " " + safe(eventSource)).toLowerCase(Locale.ROOT);
        String hint = humanClusterLine(laneHint);
        if (eventLikeText(hint) || hint.toLowerCase(Locale.ROOT).contains("знак")) return hint;
        if (!(source.contains("direction_sign") || source.contains("road_sign")
                || source.contains("roadsign") || source.contains("event")
                || source.contains("camera") || source.contains("warning"))) {
            return "";
        }
        return hint;
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
        String text = safe(value).toLowerCase(Locale.ROOT);
        return text.contains("превыш") || text.contains("overspeed")
                || text.contains("камера") || text.contains("camera")
                || text.contains("warning") || text.contains("event");
    }

    private static String humanManeuverId(String value) {
        String id = safe(value);
        if (id.isEmpty()) return "";
        String label = maneuverLabel(id);
        return label.isEmpty() ? humanClusterLine(id) : label;
    }

    private static String humanDirectionsText(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT).replace('-', '_');
        if (text.isEmpty()) return "";
        boolean uturnLeft = hasDirectionToken(text, "left180")
                || hasDirectionToken(text, "uturn_left")
                || hasDirectionToken(text, "turn_back_left");
        boolean uturnRight = hasDirectionToken(text, "right180")
                || hasDirectionToken(text, "uturn_right")
                || hasDirectionToken(text, "turn_back_right");
        boolean straight = hasDirectionToken(text, "straight")
                || hasDirectionToken(text, "straight_ahead")
                || hasDirectionToken(text, "forward")
                || text.contains("прям");
        boolean exitRight = containsAnyLower(text, "exit_right", "ramp_right",
                "take_right", "slip_right", "fork_right", "съезд направо");
        boolean exitLeft = containsAnyLower(text, "exit_left", "ramp_left",
                "take_left", "slip_left", "fork_left", "съезд налево");
        boolean hardRight = containsAnyLower(text, "hard_right", "sharp_right");
        boolean hardLeft = containsAnyLower(text, "hard_left", "sharp_left");
        boolean right = exitRight || hardRight || hasDirectionToken(text, "right")
                || hasDirectionToken(text, "right90") || hasDirectionToken(text, "turn_right")
                || text.contains("направо") || text.contains("правее");
        boolean left = exitLeft || hardLeft || hasDirectionToken(text, "left")
                || hasDirectionToken(text, "left90") || hasDirectionToken(text, "turn_left")
                || text.contains("налево") || text.contains("левее");
        StringBuilder out = new StringBuilder();
        appendDirection(out, "разворот налево", uturnLeft);
        appendDirection(out, "разворот направо", uturnRight);
        appendDirection(out, "прямо", straight);
        appendDirection(out, hardLeft ? "резко налево" : exitLeft ? "съезд налево" : "налево",
                left && !uturnLeft);
        appendDirection(out, hardRight ? "резко направо" : exitRight ? "съезд направо" : "направо",
                right && !uturnRight);
        return out.toString();
    }

    private static void appendDirection(StringBuilder out, String value, boolean enabled) {
        if (!enabled || out == null || safe(value).isEmpty()) return;
        if (out.length() > 0) out.append(" + ");
        out.append(value);
    }

    private static String humanDirectionToken(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT).replace('-', '_');
        if (text.isEmpty()) return "";
        if (hasDirectionToken(text, "left180") || hasDirectionToken(text, "uturn_left")
                || hasDirectionToken(text, "turn_back_left")) {
            return "разворот налево";
        }
        if (hasDirectionToken(text, "right180") || hasDirectionToken(text, "uturn_right")
                || hasDirectionToken(text, "turn_back_right")) {
            return "разворот направо";
        }
        if (containsAnyLower(text, "exit_right", "ramp_right", "take_right",
                "slip_right", "fork_right")) {
            return "съезд направо";
        }
        if (containsAnyLower(text, "exit_left", "ramp_left", "take_left",
                "slip_left", "fork_left")) {
            return "съезд налево";
        }
        if (containsAnyLower(text, "hard_right", "sharp_right")) return "резко направо";
        if (containsAnyLower(text, "hard_left", "sharp_left")) return "резко налево";
        if (hasDirectionToken(text, "right") || hasDirectionToken(text, "right90")
                || hasDirectionToken(text, "turn_right") || text.contains("направ")) {
            return "направо";
        }
        if (hasDirectionToken(text, "left") || hasDirectionToken(text, "left90")
                || hasDirectionToken(text, "turn_left") || text.contains("налев")) {
            return "налево";
        }
        if (hasDirectionToken(text, "straight") || hasDirectionToken(text, "straight_ahead")
                || hasDirectionToken(text, "forward") || text.contains("прям")) {
            return "прямо";
        }
        return "";
    }

    private static boolean hasDirectionToken(String value, String token) {
        String text = safe(value).toLowerCase(Locale.ROOT).replace('-', '_');
        String needle = safe(token).toLowerCase(Locale.ROOT).replace('-', '_');
        if (text.isEmpty() || needle.isEmpty()) return false;
        String[] parts = text.split("[^a-z0-9_а-я]+");
        for (String part : parts) {
            if (needle.equals(part)) return true;
        }
        return false;
    }

    private static boolean containsAnyLower(String value, String... tokens) {
        String text = safe(value).toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return false;
        for (String token : tokens) {
            if (!safe(token).isEmpty() && text.contains(token.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String fieldAfter(String value, String marker) {
        String text = safe(value);
        String cleanMarker = safe(marker);
        if (text.isEmpty() || cleanMarker.isEmpty()) return "";
        int start = text.toLowerCase(Locale.ROOT).indexOf(cleanMarker.toLowerCase(Locale.ROOT));
        if (start < 0) return "";
        String tail = text.substring(start + cleanMarker.length()).trim();
        int end = tail.length();
        int pipe = tail.indexOf('|');
        if (pipe >= 0) end = Math.min(end, pipe);
        int semicolon = tail.indexOf(';');
        if (semicolon >= 0) end = Math.min(end, semicolon);
        int comma = tail.indexOf(',');
        if (comma >= 0) end = Math.min(end, comma);
        return safe(tail.substring(0, end));
    }

    private static String firstDistanceToken(String value) {
        Matcher matcher = DISTANCE_TOKEN.matcher(safe(value));
        return matcher.find() ? normalizeClusterDistance(matcher.group()) : "";
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
        String lower = text.toLowerCase(Locale.ROOT);
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
        String p = safe(maneuver).toLowerCase(Locale.ROOT);
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
