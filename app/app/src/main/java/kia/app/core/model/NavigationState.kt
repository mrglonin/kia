package kia.app.core.model

import java.util.Locale
import java.util.regex.Pattern

class NavigationState
private constructor(
    active: Boolean,
    finishReached: Boolean,
    speedExceeded: Boolean,
    maneuver: String?,
    maneuverText: String?,
    maneuverDistance: String?,
    routeDistance: String?,
    routeTime: String?,
    arrivalTime: String?,
    currentStreet: String?,
    nextStreet: String?,
    finishStreet: String?,
    speedLimit: String?,
    currentSpeed: String?,
    source: String?,
    mainManeuverId: String?,
    routeActionId: String?,
    microManeuverId: String?,
    microDistance: String?,
    microStatus: String?,
    grayRoadId: String?,
    grayRoadScheme: String?,
    laneHint: String?,
    laneSource: String?,
    eventHint: String?,
    eventSource: String?,
    clusterVisual: String?,
    clusterTx: String?,
    laneRaw: String?,
    lanePosition: String?,
    roadSchemeRaw: String?,
    upcomingRaw: String?,
    updatedAt: Long,
) {
    @JvmField val active: Boolean
    @JvmField val finishReached: Boolean
    @JvmField val speedExceeded: Boolean
    @JvmField val maneuver: String
    @JvmField val maneuverText: String
    @JvmField val distance: String?
    @JvmField val eta: String?
    @JvmField val street: String?
    @JvmField val maneuverDistance: String
    @JvmField val routeDistance: String
    @JvmField val routeTime: String
    @JvmField val arrivalTime: String
    @JvmField val currentStreet: String
    @JvmField val nextStreet: String
    @JvmField val finishStreet: String
    @JvmField val speedLimit: String
    @JvmField val currentSpeed: String
    @JvmField val source: String
    @JvmField val mainManeuverId: String
    @JvmField val routeActionId: String
    @JvmField val microManeuverId: String
    @JvmField val microDistance: String
    @JvmField val microStatus: String
    @JvmField val grayRoadId: String
    @JvmField val grayRoadScheme: String
    @JvmField val laneHint: String
    @JvmField val laneSource: String
    @JvmField val eventHint: String
    @JvmField val eventSource: String
    @JvmField val clusterVisual: String
    @JvmField val clusterTx: String
    @JvmField val laneRaw: String
    @JvmField val lanePosition: String
    @JvmField val roadSchemeRaw: String
    @JvmField val upcomingRaw: String
    @JvmField val updatedAt: Long

    constructor(
        active: Boolean,
        maneuver: String?,
        distance: String?,
        eta: String?,
        street: String?,
        speedLimit: String?,
        updatedAt: Long,
    ) : this(
        active,
        false,
        false,
        maneuver,
        "",
        distance,
        eta,
        "",
        "",
        "",
        street,
        "",
        speedLimit,
        "",
        "",
        updatedAt,
    )

    constructor(
        active: Boolean,
        finishReached: Boolean,
        speedExceeded: Boolean,
        maneuver: String?,
        maneuverText: String?,
        maneuverDistance: String?,
        routeDistance: String?,
        routeTime: String?,
        arrivalTime: String?,
        currentStreet: String?,
        nextStreet: String?,
        finishStreet: String?,
        speedLimit: String?,
        currentSpeed: String?,
        source: String?,
        updatedAt: Long,
    ) : this(
        active,
        finishReached,
        speedExceeded,
        maneuver,
        maneuverText,
        maneuverDistance,
        routeDistance,
        routeTime,
        arrivalTime,
        currentStreet,
        nextStreet,
        finishStreet,
        speedLimit,
        currentSpeed,
        source,
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        updatedAt,
    )

    init {
        this.active = active
        this.finishReached = finishReached
        this.speedExceeded = speedExceeded
        this.maneuver = NavigationState.safe(maneuver)
        this.maneuverText = NavigationState.safe(maneuverText)
        this.maneuverDistance = NavigationState.safe(maneuverDistance)
        this.routeDistance = NavigationState.safe(routeDistance)
        this.routeTime = NavigationState.safe(routeTime)
        this.arrivalTime = NavigationState.safe(arrivalTime)
        this.currentStreet = NavigationState.safe(currentStreet)
        this.nextStreet = NavigationState.safe(nextStreet)
        this.finishStreet = NavigationState.safe(finishStreet)
        this.speedLimit = NavigationState.safe(speedLimit)
        this.currentSpeed = NavigationState.safe(currentSpeed)
        this.source = NavigationState.safe(source)
        this.mainManeuverId = NavigationState.safe(mainManeuverId)
        this.routeActionId = NavigationState.safe(routeActionId)
        this.microManeuverId = NavigationState.safe(microManeuverId)
        this.microDistance = NavigationState.safe(microDistance)
        this.microStatus = NavigationState.safe(microStatus)
        this.grayRoadId = NavigationState.safe(grayRoadId)
        this.grayRoadScheme = NavigationState.safe(grayRoadScheme)
        this.laneHint = NavigationState.safe(laneHint)
        this.laneSource = NavigationState.safe(laneSource)
        this.eventHint = NavigationState.safe(eventHint)
        this.eventSource = NavigationState.safe(eventSource)
        this.clusterVisual = NavigationState.safe(clusterVisual)
        this.clusterTx = NavigationState.safe(clusterTx)
        this.laneRaw = NavigationState.safe(laneRaw)
        this.lanePosition = NavigationState.safe(lanePosition)
        this.roadSchemeRaw = NavigationState.safe(roadSchemeRaw)
        this.upcomingRaw = NavigationState.safe(upcomingRaw)
        this.updatedAt = updatedAt
        this.distance = this.maneuverDistance
        this.eta = this.routeDistance
        this.street =
            if (this.active && !this.nextStreet.isEmpty()) this.nextStreet
            else (if (!this.currentStreet.isEmpty()) this.currentStreet else this.nextStreet)
    }

    fun withLaneHint(laneHint: String?, laneSource: String?, updatedAt: Long): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            clusterVisual,
            clusterTx,
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun withEventHint(eventHint: String?, eventSource: String?, updatedAt: Long): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            clusterVisual,
            clusterTx,
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun withManeuverDistance(maneuverDistance: String?, updatedAt: Long): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            clusterVisual,
            clusterTx,
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun withMainManeuver(
        maneuver: String?,
        maneuverText: String?,
        maneuverDistance: String?,
        source: String?,
        updatedAt: Long,
    ): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            clusterVisual,
            clusterTx,
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun withNavigationDebug(
        mainManeuverId: String?,
        routeActionId: String?,
        microManeuverId: String?,
        grayRoadId: String?,
        grayRoadScheme: String?,
        updatedAt: Long,
    ): NavigationState {
        return withNavigationDebug(
            mainManeuverId,
            routeActionId,
            microManeuverId,
            "",
            grayRoadId,
            grayRoadScheme,
            updatedAt,
        )
    }

    fun withNavigationDebug(
        mainManeuverId: String?,
        routeActionId: String?,
        microManeuverId: String?,
        microDistance: String?,
        grayRoadId: String?,
        grayRoadScheme: String?,
        updatedAt: Long,
    ): NavigationState {
        return withNavigationDebug(
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            "",
            grayRoadId,
            grayRoadScheme,
            updatedAt,
        )
    }

    fun withNavigationDebug(
        mainManeuverId: String?,
        routeActionId: String?,
        microManeuverId: String?,
        microDistance: String?,
        microStatus: String?,
        grayRoadId: String?,
        grayRoadScheme: String?,
        updatedAt: Long,
    ): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            clusterVisual,
            clusterTx,
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun withNavigationRaw(
        laneRaw: String?,
        lanePosition: String?,
        roadSchemeRaw: String?,
        upcomingRaw: String?,
        updatedAt: Long,
    ): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            clusterVisual,
            clusterTx,
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun withClusterVisual(
        imageId: String?,
        progressBucket: Int,
        distance: String?,
        updatedAt: Long,
    ): NavigationState {
        var visual: String? = NavigationState.safe(imageId)
        if (!NavigationState.safe(distance).isEmpty())
            visual += " / " + NavigationState.safe(distance)
        visual += " / progress=" + progressBucket
        return withClusterVisualText(visual, updatedAt)
    }

    fun withClusterVisualText(visual: String?, updatedAt: Long): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            NavigationState.safe(visual),
            clusterTx,
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun withClusterTxText(tx: String?, updatedAt: Long): NavigationState {
        return NavigationState(
            active,
            finishReached,
            speedExceeded,
            maneuver,
            maneuverText,
            maneuverDistance,
            routeDistance,
            routeTime,
            arrivalTime,
            currentStreet,
            nextStreet,
            finishStreet,
            speedLimit,
            currentSpeed,
            source,
            mainManeuverId,
            routeActionId,
            microManeuverId,
            microDistance,
            microStatus,
            grayRoadId,
            grayRoadScheme,
            laneHint,
            laneSource,
            eventHint,
            eventSource,
            clusterVisual,
            NavigationState.safe(tx),
            laneRaw,
            lanePosition,
            roadSchemeRaw,
            upcomingRaw,
            updatedAt,
        )
    }

    fun summary(): String {
        if (!active) {
            var out: String = "маршрут выключен"
            if (!currentStreet.isEmpty()) out += " | " + currentStreet
            if (!speedLimit.isEmpty()) out += " | лимит " + speedLimit
            return out
        }
        if (loading()) return "Загрузка маршрута"
        var out: String = if (finishReached) "финиш достигнут" else "маршрут активен"
        if (!routeDistance.isEmpty()) out += " | " + routeDistance
        if (!arrivalTime.isEmpty()) out += " | прибытие " + arrivalTime
        if (!maneuverText.isEmpty()) out += " | " + maneuverText
        if (!maneuverDistance.isEmpty()) out += " через " + maneuverDistance
        if (!street!!.isEmpty()) out += " | " + street
        return out
    }

    fun details(): String {
        val out: StringBuilder = StringBuilder()
        out.append("Маршрут: ").append(statusText()).append('\n')
        out.append("Длина: ").append(NavigationState.orDash(routeDistance)).append('\n')
        out.append("Время: ").append(NavigationState.orDash(routeTime)).append('\n')
        out.append("Прибытие: ").append(NavigationState.orDash(arrivalTime)).append('\n')
        out.append("Манёвр: ").append(NavigationState.orDash(maneuverText)).append('\n')
        out.append("До манёвра: ").append(NavigationState.orDash(maneuverDistance)).append('\n')
        out.append("Улица сейчас: ").append(NavigationState.orDash(currentStreet)).append('\n')
        out.append("Улица после: ").append(NavigationState.orDash(nextStreet)).append('\n')
        out.append("Адрес финиша: ").append(NavigationState.orDash(finishStreet)).append('\n')
        out.append("Скорость сейчас: ").append(NavigationState.orDash(currentSpeed)).append('\n')
        out.append("Лимит скорости: ").append(NavigationState.orDash(speedLimit)).append('\n')
        out.append("Превышение: ").append(if (speedExceeded) "да" else "нет").append('\n')
        out.append("Источник: ").append(NavigationState.orDash(source)).append('\n')
        out.append("Основной ID: ")
            .append(
                NavigationState.orDash(
                    NavigationState.humanManeuverId(NavigationState.first(maneuver, mainManeuverId))
                )
            )
            .append('\n')
        out.append("Route action: ")
            .append(NavigationState.orDash(NavigationState.humanManeuverId(routeActionId)))
            .append('\n')
        out.append("Micro: ").append(NavigationState.orDash(microDebugLine())).append('\n')
        out.append("TX-схема: ").append(NavigationState.orDash(txGrayRoadText())).append('\n')
        out.append("Полосы Яндекса: ")
            .append(NavigationState.orDash(yandexLaneDirectionsText()))
            .append('\n')
        out.append("Подсветка: ").append(NavigationState.orDash(laneHighlightText())).append('\n')
        out.append("До подсказки: ")
            .append(NavigationState.orDash(laneDistanceDebugText()))
            .append('\n')
        out.append("Ближайшие подсказки: ")
            .append(NavigationState.orDash(upcomingLaneHintsText()))
            .append('\n')
        out.append("События: ")
            .append(
                NavigationState.orDash(NavigationState.first(eventHintText(), upcomingEventText()))
            )
        return out.toString()
    }

    fun overlayDetails(): String {
        val out: StringBuilder = StringBuilder()
        val routeMetricsVisible: Boolean = active && !finishReached
        out.append("Статус: ").append(statusText()).append('\n')
        out.append("Сейчас: ").append(NavigationState.orDash(currentStreet)).append('\n')
        out.append("После: ").append(NavigationState.orDash(nextStreet)).append('\n')
        out.append("Финиш: ").append(NavigationState.orDash(finishStreet)).append('\n')
        out.append("Осталось: ")
            .append(NavigationState.orDash(if (routeMetricsVisible) routeDistance else ""))
            .append("  ETA: ")
            .append(NavigationState.orDash(if (routeMetricsVisible) arrivalTime else ""))
            .append('\n')
        out.append("Маршрут всего: ")
            .append(
                NavigationState.orDash(if (routeMetricsVisible) routeTotalDistanceText() else "")
            )
            .append('\n')

        out.append("Манёвр").append('\n')
        out.append("Показ: ")
            .append(NavigationState.orDash(maneuverText))
            .append("  До: ")
            .append(NavigationState.orDash(maneuverDistance))
            .append('\n')
        val roundabout: String = roundaboutText()
        if (!roundabout.isEmpty()) {
            out.append("Круг: ").append(roundabout).append('\n')
        }

        out.append("Микра").append('\n')
        out.append("Показ: ")
            .append(NavigationState.orDash(microLabel()))
            .append("  До: ")
            .append(NavigationState.orDash(microDistance))
            .append('\n')
        out.append("Статус: ")
            .append(
                NavigationState.orDash(NavigationState.first(microStatus, inferredMicroStatus()))
            )
            .append('\n')

        out.append("Серая дорога").append('\n')
        out.append("TX-схема: ").append(NavigationState.orDash(txGrayRoadText())).append('\n')
        out.append("Полосы Яндекса: ")
            .append(NavigationState.orDash(yandexLaneDirectionsText()))
            .append('\n')
        out.append("Подсветка: ").append(NavigationState.orDash(laneHighlightText())).append('\n')
        out.append("До подсказки: ")
            .append(NavigationState.orDash(laneDistanceDebugText()))
            .append('\n')
        out.append("Ближайшие: ")
            .append(NavigationState.orDash(upcomingLaneHintsText()))
            .append('\n')

        out.append("События").append('\n')
        out.append("Событие: ")
            .append(
                NavigationState.orDash(NavigationState.first(eventHintText(), upcomingEventText()))
            )
            .append('\n')
        out.append("Ближайшие: ").append(NavigationState.orDash(upcomingEventText())).append('\n')
        out.append("Скорость: ")
            .append(NavigationState.orDash(currentSpeed))
            .append("  Лимит: ")
            .append(NavigationState.orDash(speedLimit))
            .append("  Превышение: ")
            .append(if (speedExceeded) "да" else "нет")
        return out.toString()
    }

    fun clusterTxDetails(): String {
        return clusterTxDetails(false, false)
    }

    fun clusterTxDetails(tbtMode: Boolean, finishDirectionMode: Boolean): String {
        val mode: ClusterTxMode = clusterTxMode(tbtMode, finishDirectionMode)
        val out: StringBuilder = StringBuilder()
        out.append("Режим TX: ").append(mode.name).append('\n')
        when (mode) {
            ClusterTxMode.FINISH_DIRECTION -> out.append(clusterCompassTxDetails(true))
            ClusterTxMode.NAV_TBT -> out.append(clusterTbtTxDetails())
            ClusterTxMode.NAV_NORMAL -> out.append(clusterManeuverTxDetails())
            ClusterTxMode.COMPASS -> out.append(clusterCompassTxDetails(false))
            else -> out.append(clusterCompassTxDetails(false))
        }
        return out.toString()
    }

    private fun clusterTxMode(tbtMode: Boolean, finishDirectionMode: Boolean): ClusterTxMode {
        if (!active) return ClusterTxMode.COMPASS
        if (finishReached) return ClusterTxMode.NAV_NORMAL
        if (finishDirectionMode) return ClusterTxMode.FINISH_DIRECTION
        if (tbtMode) return ClusterTxMode.NAV_TBT
        return ClusterTxMode.NAV_NORMAL
    }

    private enum class ClusterTxMode {
        COMPASS,
        NAV_NORMAL,
        NAV_TBT,
        FINISH_DIRECTION,
    }

    private fun clusterManeuverTxDetails(): String {
        val out: StringBuilder = StringBuilder()
        val maneuverFrame = NavigationState.first(lastClusterManeuverLine(), clusterVisual)
        out.append("Манёвр: ")
            .append(NavigationState.orDash(NavigationState.clusterYellowText(maneuverFrame)))
            .append('\n')
        out.append("Дорога: ")
            .append(NavigationState.orDash(NavigationState.clusterGrayRoadText(maneuverFrame)))
            .append('\n')
        out.append("До: ")
            .append(NavigationState.orDash(NavigationState.clusterDistanceText(maneuverFrame)))
            .append("  Прогресс: ")
            .append(NavigationState.orDash(NavigationState.clusterProgressText(maneuverFrame)))
            .append('\n')
        out.append("Улица: ")
            .append(
                NavigationState.orDash(
                    NavigationState.first(
                        clusterTextValue(),
                        NavigationState.first(
                            currentStreet,
                            NavigationState.first(nextStreet, finishStreet),
                        ),
                    )
                )
            )
            .append('\n')
        out.append("Осталось: ")
            .append(
                NavigationState.orDash(
                    NavigationState.first(clusterEtaDistanceValue(), routeDistance)
                )
            )
            .append("  Прибытие: ")
            .append(
                NavigationState.orDash(NavigationState.first(clusterEtaTimeValue(), arrivalTime))
            )
            .append('\n')
        out.append("Маршрут всего: ")
            .append(NavigationState.orDash(routeTotalDistanceText()))
            .append('\n')
        out.append("Лимит: ")
            .append(
                NavigationState.orDash(NavigationState.first(clusterSpeedLimitValue(), speedLimit))
            )
            .append("  Скорость: ")
            .append(NavigationState.orDash(currentSpeed))
            .append('\n')
        out.append("Кадр манёвра: ")
            .append(NavigationState.orDash(NavigationState.humanClusterLine(maneuverFrame)))
        return out.toString()
    }

    private fun clusterTbtTxDetails(): String {
        val out: StringBuilder = StringBuilder()
        val textLine: String = lastClusterLineStarting("text=")
        val maneuverFrame: String = lastClusterManeuverLine()
        out.append("TBT: ").append(NavigationState.orDash(clusterTextAnyValue())).append('\n')
        out.append("Манёвр: ")
            .append(NavigationState.orDash(NavigationState.clusterYellowText(maneuverFrame)))
            .append('\n')
        out.append("До: ")
            .append(NavigationState.orDash(NavigationState.clusterDistanceText(maneuverFrame)))
            .append("  Прогресс: ")
            .append(NavigationState.orDash(NavigationState.clusterProgressText(maneuverFrame)))
            .append('\n')
        out.append("Осталось: ")
            .append(
                NavigationState.orDash(
                    NavigationState.first(clusterEtaDistanceValue(), routeDistance)
                )
            )
            .append("  Прибытие: ")
            .append(
                NavigationState.orDash(NavigationState.first(clusterEtaTimeValue(), arrivalTime))
            )
            .append('\n')
        out.append("Маршрут всего: ")
            .append(NavigationState.orDash(routeTotalDistanceText()))
            .append('\n')
        out.append("Лимит: ")
            .append(
                NavigationState.orDash(NavigationState.first(clusterSpeedLimitValue(), speedLimit))
            )
            .append("  Скорость: ")
            .append(NavigationState.orDash(currentSpeed))
            .append('\n')
        out.append("Кадр TBT: ")
            .append(
                NavigationState.orDash(
                    NavigationState.humanClusterLine(NavigationState.first(textLine, maneuverFrame))
                )
            )
        return out.toString()
    }

    private fun clusterCompassTxDetails(finishDirectionMode: Boolean): String {
        val out: StringBuilder = StringBuilder()
        val compassFrame: String =
            if (finishDirectionMode) lastClusterLineStarting("finish direction ", "compass step=")
            else lastClusterLineStarting("compass step=")
        out.append("Стрелка: ")
            .append(NavigationState.orDash(NavigationState.clusterCompassStepText(compassFrame)))
            .append('\n')
        if (finishDirectionMode) {
            out.append("До финиша: ")
                .append(NavigationState.orDash(NavigationState.clusterDistanceText(compassFrame)))
                .append('\n')
        }
        out.append("Улица: ")
            .append(
                NavigationState.orDash(
                    NavigationState.first(
                        clusterTextValue(),
                        NavigationState.first(
                            currentStreet,
                            NavigationState.first(nextStreet, finishStreet),
                        ),
                    )
                )
            )
            .append('\n')
        if (finishDirectionMode) {
            out.append("Осталось: ")
                .append(
                    NavigationState.orDash(
                        NavigationState.first(clusterEtaDistanceValue(), routeDistance)
                    )
                )
                .append("  Прибытие: ")
                .append(
                    NavigationState.orDash(
                        NavigationState.first(clusterEtaTimeValue(), arrivalTime)
                    )
                )
                .append('\n')
        }
        out.append("Лимит: ")
            .append(
                NavigationState.orDash(NavigationState.first(clusterSpeedLimitValue(), speedLimit))
            )
            .append("  Скорость: ")
            .append(NavigationState.orDash(currentSpeed))
            .append('\n')
        out.append(if (finishDirectionMode) "Кадр стрелки: " else "Кадр компаса: ")
            .append(NavigationState.orDash(NavigationState.humanClusterLine(compassFrame)))
        return out.toString()
    }

    fun loading(): Boolean {
        val hasManeuver: Boolean =
            !maneuverText.isEmpty() || !maneuverDistance.isEmpty() || !maneuver.isEmpty()
        val hasRouteMetrics: Boolean =
            !routeDistance.isEmpty() || !routeTime.isEmpty() || !arrivalTime.isEmpty()
        if (active && hasRouteMetrics) return false
        val notificationOnly: Boolean = source.lowercase(Locale.ROOT).contains("2gis")
        if (notificationOnly && hasManeuver) return false
        return active && !finishReached && !hasManeuver
    }

    private fun statusText(): String {
        if (!active) return "маршрут выключен"
        if (finishReached) return "финиш достигнут"
        if (loading()) return "Загрузка маршрута"
        return "маршрут активен"
    }

    private fun providerLabel(): String {
        val all: String? = (source + " " + laneSource).lowercase(Locale.ROOT)
        if (all!!.contains("2gis") || all.contains("2гис")) return "2GIS"
        if (all.contains("yandex") || all.contains("яндекс")) return "Yandex"
        if (all.contains("gps")) return "GPS"
        return "-"
    }

    private fun roundaboutText(): String {
        val value =
            NavigationState.first(maneuver, NavigationState.first(routeActionId, mainManeuverId))
        val p: String = NavigationState.safe(value).lowercase(Locale.ROOT)
        if (!(p.contains("roundabout") || p.contains("circular") || p.contains("круг"))) return ""
        if (p.contains("exit_1")) return "1-й съезд"
        if (p.contains("exit_2")) return "2-й съезд"
        if (p.contains("exit_3")) return "3-й съезд"
        if (p.contains("exit_4")) return "4-й съезд"
        return "круг без номера съезда"
    }

    private fun microLabel(): String {
        val id: String = NavigationState.safe(microManeuverId)
        if (id.isEmpty()) return ""
        val label: String = NavigationState.maneuverLabel(id)
        return if (label.isEmpty()) id else label
    }

    private fun microDebugLine(): String {
        val label: String = microLabel()
        if (label.isEmpty()) return ""
        val out: StringBuilder = StringBuilder(label)
        if (!NavigationState.safe(microDistance).isEmpty()) out.append(", ").append(microDistance)
        val status = NavigationState.first(microStatus, inferredMicroStatus())
        if (!status.isEmpty()) out.append(", ").append(status)
        return out.toString()
    }

    private fun txGrayRoadText(): String {
        return NavigationState.first(grayRoadScheme, NavigationState.grayRoadLabel(grayRoadId))
    }

    private fun yandexLaneDirectionsText(): String {
        return NavigationState.first(
            NavigationState.humanDirectionsText(roadSchemeRaw),
            NavigationState.humanDirectionsText(laneRaw),
        )
    }

    private fun laneHighlightText(): String {
        var token: String? =
            NavigationState.first(
                NavigationState.fieldAfter(lanePosition, "highlight="),
                NavigationState.fieldAfter(laneRaw, "highlight="),
            )
        token =
            NavigationState.first(
                token,
                NavigationState.fieldAfter(laneRaw, "highlighted_direction="),
            )
        token = NavigationState.first(token, NavigationState.fieldAfter(laneRaw, "lane_highlight="))
        token =
            NavigationState.first(token, NavigationState.fieldAfter(roadSchemeRaw, "highlight="))
        return NavigationState.humanDirectionToken(token)
    }

    private fun laneDistanceDebugText(): String {
        return NavigationState.first(
            microDistance,
            NavigationState.first(
                NavigationState.fieldAfter(lanePosition, "dist="),
                NavigationState.firstDistanceToken(NavigationState.first(lanePosition, laneRaw)),
            ),
        )
    }

    private fun upcomingLaneHintsText(): String {
        return upcomingText(false)
    }

    private fun upcomingEventText(): String {
        return upcomingText(true)
    }

    private fun upcomingText(eventsOnly: Boolean): String {
        val raw: String = NavigationState.safe(upcomingRaw)
        if (raw.isEmpty()) return ""
        val normalized = raw.replace('\n', '|')
        val parts = java.util.regex.Pattern.compile("\\|\\||;").split(normalized)
        val out: StringBuilder = StringBuilder()
        var count: Int = 0
        for (part: String? in parts) {
            val item: String = NavigationState.humanUpcomingItem(part, eventsOnly)
            if (item.isEmpty()) continue
            if (out.length > 0) out.append("; ")
            out.append(item)
            count++
            if (count >= 3) break
        }
        return out.toString()
    }

    private fun inferredMicroStatus(): String {
        if (NavigationState.safe(microManeuverId).isEmpty()) return "нет данных"
        if (NavigationState.safe(microDistance).isEmpty()) return "есть подсказка, но без дистанции"
        return "есть подсказка"
    }

    private fun eventHintText(): String {
        val txEvent = clusterEventTextValue()
        if (!txEvent.isEmpty()) return txEvent
        val explicit = NavigationState.humanClusterLine(eventHint)
        if (!explicit.isEmpty()) return explicit
        val source: String? =
            (NavigationState.safe(laneSource) + " " + NavigationState.safe(eventSource)).lowercase(
                Locale.ROOT
            )
        val hint = NavigationState.humanClusterLine(laneHint)
        if (NavigationState.eventLikeText(hint) || hint.lowercase(Locale.ROOT).contains("знак"))
            return hint
        if (
            !(source!!.contains("direction_sign") ||
                source.contains("road_sign") ||
                source.contains("roadsign") ||
                source.contains("event") ||
                source.contains("camera") ||
                source.contains("warning"))
        ) {
            return ""
        }
        return hint
    }

    private fun humanClusterVisual(): String {
        return NavigationState.humanClusterLine(
            NavigationState.first(clusterVisual, lastClusterTxLine())
        )
    }

    private fun lastClusterTxLine(): String {
        val text: String = NavigationState.safe(clusterTx)
        if (text.isEmpty()) return ""
        val lines = java.util.regex.Pattern.compile("\\n").split(text)
        for (i in lines.indices.reversed()) {
            val line: String = NavigationState.safe(lines.get(i))
            if (!line.isEmpty()) return line
        }
        return ""
    }

    private fun lastClusterManeuverLine(): String {
        return lastClusterLineStarting("maneuver+gray ", "maneuver ", "finish direction ")
    }

    private fun clusterTextValue(): String {
        val text = NavigationState.clusterValueAfter(lastClusterLineStarting("text="), "text=")
        return if (NavigationState.eventLikeText(text)) "" else text
    }

    private fun clusterTextAnyValue(): String {
        return NavigationState.clusterValueAfter(lastClusterLineStarting("text="), "text=")
    }

    private fun clusterEventTextValue(): String {
        var text: String? =
            NavigationState.clusterValueAfter(lastClusterLineStarting("text="), "text=")
        if (text!!.isEmpty()) {
            text = NavigationState.clusterValueAfter(lastClusterLineContaining(" text="), "text=")
        }
        return if (NavigationState.eventLikeText(text)) text else ""
    }

    private fun clusterEtaDistanceValue(): String {
        return NavigationState.normalizeClusterDistance(
            NavigationState.clusterValueAfter(
                lastClusterLineStarting("eta distance="),
                "eta distance=",
            )
        )
    }

    private fun clusterEtaTimeValue(): String {
        return NavigationState.clusterValueAfter(lastClusterLineStarting("eta time="), "eta time=")
    }

    private fun clusterSpeedLimitValue(): String {
        return NavigationState.clusterValueAfter(
            lastClusterLineStarting("speedLimit="),
            "speedLimit=",
        )
    }

    private fun routeTotalDistanceText(): String {
        return NavigationState.cleanRouteTotalDistance(
            NavigationState.clusterValueAfter(
                lastClusterLineStarting("route total="),
                "route total=",
            )
        )
    }

    private fun lastClusterLineStarting(vararg prefixes: String?): String {
        val text: String = NavigationState.safe(clusterTx)
        if (text.isEmpty()) return ""
        val lines = java.util.regex.Pattern.compile("\\n").split(text)
        for (i in lines.indices.reversed()) {
            val line: String = NavigationState.safe(lines.get(i))
            if (line.isEmpty()) continue
            for (prefix: String? in prefixes) {
                if (line.startsWith(prefix!!)) return line
            }
        }
        return ""
    }

    private fun lastClusterLineContaining(marker: String): String {
        val text: String = NavigationState.safe(clusterTx)
        if (text.isEmpty()) return ""
        val lines = java.util.regex.Pattern.compile("\\n").split(text)
        for (i in lines.indices.reversed()) {
            val line: String = NavigationState.safe(lines.get(i))
            if (!line.isEmpty() && line.contains(marker)) return line
        }
        return ""
    }

    companion object {
        private val DISTANCE_TOKEN: Pattern? =
            Pattern.compile(
                "[-+]?\\d+(?:[\\.,]\\d+)?\\s*(?:км|km|м|m)",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )

        @JvmStatic
        fun empty(): NavigationState {
            return NavigationState(
                false,
                false,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                0L,
            )
        }

        private fun orDash(value: String?): String {
            return if (NavigationState.safe(value).isEmpty()) "-" else NavigationState.safe(value)
        }

        private fun humanUpcomingItem(value: String?, eventMode: Boolean): String {
            val text: String = NavigationState.safe(value)
            if (text.isEmpty()) return ""
            val event: Boolean =
                NavigationState.eventLikeText(text) ||
                    NavigationState.containsAnyLower(
                        text,
                        "camera",
                        "камера",
                        "accident",
                        "дтп",
                        "road_work",
                        "works",
                        "ремонт",
                        "warning",
                        "event",
                        "police",
                        "полиция",
                        "дпс",
                    )
            if (eventMode && !event) return ""
            if (!eventMode && event) return ""
            val distance = NavigationState.firstDistanceToken(text)
            var label: String? =
                if (event) NavigationState.humanEventLabel(text)
                else
                    NavigationState.first(
                        NavigationState.humanDirectionToken(
                            NavigationState.fieldAfter(text, "highlight=")
                        ),
                        NavigationState.humanDirectionsText(text),
                    )
            if (label!!.isEmpty()) label = if (event) "событие" else "подсказка"
            return if (distance.isEmpty()) label else label + ", " + distance
        }

        private fun humanEventLabel(value: String?): String {
            val text: String = NavigationState.safe(value).lowercase(Locale.ROOT)
            if (text.contains("lane") || text.contains("полос")) return "камера на полосу"
            if (text.contains("speed") || text.contains("limit") || text.contains("скор")) {
                return "камера скорости"
            }
            if (text.contains("camera") || text.contains("камера")) return "камера"
            if (text.contains("accident") || text.contains("дтп")) return "ДТП"
            if (text.contains("road_work") || text.contains("works") || text.contains("ремонт")) {
                return "ремонт"
            }
            if (text.contains("police") || text.contains("дпс") || text.contains("полиция")) {
                return "пост ДПС"
            }
            return "событие"
        }

        private fun clusterYellowText(value: String?): String {
            val id = NavigationState.clusterYellowId(value)
            if (id.isEmpty()) return ""
            if (id.startsWith("finish direction")) return "стрелка к финишу"
            val label: String = NavigationState.maneuverLabel(id)
            return if (label.isEmpty()) NavigationState.humanClusterLine(id) else label
        }

        private fun clusterGrayRoadText(value: String?): String {
            val id = NavigationState.clusterGrayRoadId(value)
            if (id.isEmpty()) return ""
            val label: String = NavigationState.grayRoadLabel(id)
            return if (label.isEmpty()) NavigationState.humanClusterLine(id) else label
        }

        private fun clusterDistanceText(value: String?): String {
            val text: String = NavigationState.safe(value)
            if (text.isEmpty()) return ""
            if (text.contains(" dist=")) {
                return NavigationState.normalizeClusterDistance(
                    NavigationState.tokenAfter(text, " dist=")
                )
            }
            val parts = java.util.regex.Pattern.compile("/").split(text)
            return if (parts.size >= 2) NavigationState.normalizeClusterDistance(parts.get(1))
            else ""
        }

        private fun clusterProgressText(value: String?): String {
            val text: String = NavigationState.safe(value)
            if (text.isEmpty()) return ""
            if (text.contains(" progress=")) return NavigationState.tokenAfter(text, " progress=")
            if (text.contains("progress=")) return NavigationState.tokenAfter(text, "progress=")
            return ""
        }

        private fun clusterCompassStepText(value: String?): String {
            val text: String = NavigationState.safe(value)
            if (text.isEmpty()) return ""
            if (text.startsWith("compass step="))
                return NavigationState.tokenAfter(text, "compass step=")
            if (text.contains(" step=")) return NavigationState.tokenAfter(text, " step=")
            return ""
        }

        private fun clusterYellowId(value: String?): String {
            val text: String = NavigationState.safe(value)
            if (text.isEmpty()) return ""
            if (text.startsWith("finish direction")) return "finish direction"
            if (text.startsWith("maneuver+gray ")) {
                return NavigationState.before(
                    NavigationState.tokenAfter(text, "maneuver+gray "),
                    " gray=",
                )
            }
            if (text.startsWith("maneuver ")) {
                return NavigationState.before(
                    NavigationState.tokenAfter(text, "maneuver "),
                    " dist=",
                )
            }
            val slash: Int = text.indexOf('/')
            val visual = if (slash >= 0) text.substring(0, slash) else text
            val plus: Int = visual.indexOf(" + ")
            return NavigationState.safe(if (plus >= 0) visual.substring(0, plus) else visual)
        }

        private fun clusterGrayRoadId(value: String?): String {
            val text: String = NavigationState.safe(value)
            if (text.isEmpty()) return ""
            if (text.contains(" gray="))
                return NavigationState.before(NavigationState.tokenAfter(text, " gray="), " dist=")
            val slash: Int = text.indexOf('/')
            val visual = if (slash >= 0) text.substring(0, slash) else text
            val plus: Int = visual.indexOf(" + ")
            return if (plus >= 0) NavigationState.safe(visual.substring(plus + 3)) else ""
        }

        private fun clusterValueAfter(line: String?, marker: String): String {
            val text: String = NavigationState.safe(line)
            if (text.isEmpty()) return ""
            val start: Int = text.indexOf(marker)
            if (start < 0) return ""
            return NavigationState.before(text.substring(start + marker.length), " bytes=")
        }

        private fun cleanRouteTotalDistance(value: String?): String {
            val text: String = NavigationState.safe(value)
            if (text.isEmpty()) return ""
            val matcher = NavigationState.DISTANCE_TOKEN!!.matcher(text)
            return if (matcher.find()) NavigationState.normalizeClusterDistance(matcher.group())
            else ""
        }

        private fun eventLikeText(value: String?): Boolean {
            val text: String = NavigationState.safe(value).lowercase(Locale.ROOT)
            return text.contains("превыш") ||
                text.contains("overspeed") ||
                text.contains("камера") ||
                text.contains("camera") ||
                text.contains("warning") ||
                text.contains("event")
        }

        private fun humanManeuverId(value: String?): String {
            val id: String = NavigationState.safe(value)
            if (id.isEmpty()) return ""
            val label: String = NavigationState.maneuverLabel(id)
            return if (label.isEmpty()) NavigationState.humanClusterLine(id) else label
        }

        private fun humanDirectionsText(value: String?): String {
            val text: String = NavigationState.safe(value).lowercase(Locale.ROOT).replace('-', '_')
            if (text.isEmpty()) return ""
            val uturnLeft: Boolean =
                NavigationState.hasDirectionToken(text, "left180") ||
                    NavigationState.hasDirectionToken(text, "uturn_left") ||
                    NavigationState.hasDirectionToken(text, "turn_back_left")
            val uturnRight: Boolean =
                NavigationState.hasDirectionToken(text, "right180") ||
                    NavigationState.hasDirectionToken(text, "uturn_right") ||
                    NavigationState.hasDirectionToken(text, "turn_back_right")
            val straight: Boolean =
                NavigationState.hasDirectionToken(text, "straight") ||
                    NavigationState.hasDirectionToken(text, "straight_ahead") ||
                    NavigationState.hasDirectionToken(text, "forward") ||
                    text.contains("прям")
            val exitRight: Boolean =
                NavigationState.containsAnyLower(
                    text,
                    "exit_right",
                    "ramp_right",
                    "take_right",
                    "slip_right",
                    "fork_right",
                    "съезд направо",
                )
            val exitLeft: Boolean =
                NavigationState.containsAnyLower(
                    text,
                    "exit_left",
                    "ramp_left",
                    "take_left",
                    "slip_left",
                    "fork_left",
                    "съезд налево",
                )
            val hardRight: Boolean =
                NavigationState.containsAnyLower(text, "hard_right", "sharp_right")
            val hardLeft: Boolean =
                NavigationState.containsAnyLower(text, "hard_left", "sharp_left")
            val right: Boolean =
                exitRight ||
                    hardRight ||
                    NavigationState.hasDirectionToken(text, "right") ||
                    NavigationState.hasDirectionToken(text, "right90") ||
                    NavigationState.hasDirectionToken(text, "turn_right") ||
                    text.contains("направо") ||
                    text.contains("правее")
            val left: Boolean =
                exitLeft ||
                    hardLeft ||
                    NavigationState.hasDirectionToken(text, "left") ||
                    NavigationState.hasDirectionToken(text, "left90") ||
                    NavigationState.hasDirectionToken(text, "turn_left") ||
                    text.contains("налево") ||
                    text.contains("левее")
            val out: StringBuilder = StringBuilder()
            NavigationState.appendDirection(out, "разворот налево", uturnLeft)
            NavigationState.appendDirection(out, "разворот направо", uturnRight)
            NavigationState.appendDirection(out, "прямо", straight)
            NavigationState.appendDirection(
                out,
                if (hardLeft) "резко налево" else if (exitLeft) "съезд налево" else "налево",
                left && !uturnLeft,
            )
            NavigationState.appendDirection(
                out,
                if (hardRight) "резко направо" else if (exitRight) "съезд направо" else "направо",
                right && !uturnRight,
            )
            return out.toString()
        }

        private fun appendDirection(out: StringBuilder?, value: String?, enabled: Boolean) {
            if (!enabled || out == null || NavigationState.safe(value).isEmpty()) return
            if (out.length > 0) out.append(" + ")
            out.append(value)
        }

        private fun humanDirectionToken(value: String?): String {
            val text: String = NavigationState.safe(value).lowercase(Locale.ROOT).replace('-', '_')
            if (text.isEmpty()) return ""
            if (
                NavigationState.hasDirectionToken(text, "left180") ||
                    NavigationState.hasDirectionToken(text, "uturn_left") ||
                    NavigationState.hasDirectionToken(text, "turn_back_left")
            ) {
                return "разворот налево"
            }
            if (
                NavigationState.hasDirectionToken(text, "right180") ||
                    NavigationState.hasDirectionToken(text, "uturn_right") ||
                    NavigationState.hasDirectionToken(text, "turn_back_right")
            ) {
                return "разворот направо"
            }
            if (
                NavigationState.containsAnyLower(
                    text,
                    "exit_right",
                    "ramp_right",
                    "take_right",
                    "slip_right",
                    "fork_right",
                )
            ) {
                return "съезд направо"
            }
            if (
                NavigationState.containsAnyLower(
                    text,
                    "exit_left",
                    "ramp_left",
                    "take_left",
                    "slip_left",
                    "fork_left",
                )
            ) {
                return "съезд налево"
            }
            if (NavigationState.containsAnyLower(text, "hard_right", "sharp_right"))
                return "резко направо"
            if (NavigationState.containsAnyLower(text, "hard_left", "sharp_left"))
                return "резко налево"
            if (
                NavigationState.hasDirectionToken(text, "right") ||
                    NavigationState.hasDirectionToken(text, "right90") ||
                    NavigationState.hasDirectionToken(text, "turn_right") ||
                    text.contains("направ")
            ) {
                return "направо"
            }
            if (
                NavigationState.hasDirectionToken(text, "left") ||
                    NavigationState.hasDirectionToken(text, "left90") ||
                    NavigationState.hasDirectionToken(text, "turn_left") ||
                    text.contains("налев")
            ) {
                return "налево"
            }
            if (
                NavigationState.hasDirectionToken(text, "straight") ||
                    NavigationState.hasDirectionToken(text, "straight_ahead") ||
                    NavigationState.hasDirectionToken(text, "forward") ||
                    text.contains("прям")
            ) {
                return "прямо"
            }
            return ""
        }

        private fun hasDirectionToken(value: String?, token: String?): Boolean {
            val text: String = NavigationState.safe(value).lowercase(Locale.ROOT).replace('-', '_')
            val needle: String =
                NavigationState.safe(token).lowercase(Locale.ROOT).replace('-', '_')
            if (text.isEmpty() || needle.isEmpty()) return false
            val parts = java.util.regex.Pattern.compile("[^a-z0-9_а-я]+").split(text)
            for (part: String? in parts) {
                if (needle.equals(part)) return true
            }
            return false
        }

        private fun containsAnyLower(value: String?, vararg tokens: String?): Boolean {
            val text: String = NavigationState.safe(value).lowercase(Locale.ROOT)
            if (text.isEmpty()) return false
            for (token: String? in tokens) {
                if (
                    !NavigationState.safe(token).isEmpty() &&
                        text.contains(token!!.lowercase(Locale.ROOT))
                )
                    return true
            }
            return false
        }

        private fun fieldAfter(value: String?, marker: String?): String {
            val text: String = NavigationState.safe(value)
            val cleanMarker: String = NavigationState.safe(marker)
            if (text.isEmpty() || cleanMarker.isEmpty()) return ""
            val start: Int = text.lowercase(Locale.ROOT).indexOf(cleanMarker.lowercase(Locale.ROOT))
            if (start < 0) return ""
            val tail = text.substring(start + cleanMarker.length).trim()
            var end: Int = tail.length
            val pipe: Int = tail.indexOf('|')
            if (pipe >= 0) end = Math.min(end, pipe)
            val semicolon: Int = tail.indexOf(';')
            if (semicolon >= 0) end = Math.min(end, semicolon)
            val comma: Int = tail.indexOf(',')
            if (comma >= 0) end = Math.min(end, comma)
            return NavigationState.safe(tail.substring(0, end))
        }

        private fun firstDistanceToken(value: String?): String {
            val matcher = NavigationState.DISTANCE_TOKEN!!.matcher(NavigationState.safe(value))
            return if (matcher.find()) NavigationState.normalizeClusterDistance(matcher.group())
            else ""
        }

        private fun tokenAfter(value: String?, marker: String): String {
            val text: String = NavigationState.safe(value)
            val start: Int = text.indexOf(marker)
            if (start < 0) return ""
            val tail = text.substring(start + marker.length).trim()
            val space: Int = tail.indexOf(' ')
            return NavigationState.safe(if (space >= 0) tail.substring(0, space) else tail)
        }

        private fun before(value: String?, marker: String?): String {
            val text: String = NavigationState.safe(value)
            val index: Int = text.indexOf(marker!!)
            return NavigationState.safe(if (index >= 0) text.substring(0, index) else text)
        }

        private fun normalizeClusterDistance(value: String?): String {
            var text: String? = NavigationState.safe(value)
            if (text!!.isEmpty()) return ""
            text = text.replace(',', '.')
            val lower = text.lowercase(Locale.ROOT)
            if (lower.endsWith("km")) {
                return NavigationState.trimClusterNumber(text.substring(0, text.length - 2)) + " км"
            }
            if (lower.endsWith("m")) {
                return NavigationState.trimClusterNumber(text.substring(0, text.length - 1)) + " м"
            }
            return NavigationState.safe(text.replace('.', ','))
        }

        private fun trimClusterNumber(value: String?): String {
            var text: String? = NavigationState.safe(value)
            if (text!!.endsWith(".0")) text = text.substring(0, text.length - 2)
            return text.replace('.', ',')
        }

        private fun grayRoadLabel(grayRoad: String?): String {
            val value: String = NavigationState.safe(grayRoad)
            if ("context_ra_gray_straight_left_right".equals(value))
                return "прямо + налево + направо"
            if ("context_ra_gray_left_right".equals(value)) return "налево + направо"
            if ("context_ra_gray_straight_left_exit_right".equals(value))
                return "прямо + налево + съезд направо"
            if ("context_ra_gray_straight_exit_left_right".equals(value))
                return "прямо + съезд налево + направо"
            if ("context_ra_gray_left_exit_right".equals(value)) return "налево + съезд направо"
            if ("context_ra_gray_exit_left_right".equals(value)) return "съезд налево + направо"
            if ("context_ra_gray_straight_right".equals(value)) return "прямо + направо"
            if ("context_ra_gray_straight_left".equals(value)) return "прямо + налево"
            if ("context_ra_gray_exit_right".equals(value)) return "прямо + съезд направо"
            if ("context_ra_gray_only_exit_right".equals(value)) return "съезд направо"
            if ("context_ra_gray_straight_exit_left".equals(value)) return "прямо + съезд налево"
            if ("context_ra_gray_exit_left".equals(value)) return "съезд налево"
            if ("context_ra_gray_hard_right".equals(value)) return "резко направо"
            if ("context_ra_gray_hard_left".equals(value)) return "резко налево"
            if ("context_ra_gray_right".equals(value)) return "направо"
            if ("context_ra_gray_left".equals(value)) return "налево"
            if ("context_ra_gray_straight".equals(value)) return "прямо"
            return ""
        }

        private fun humanClusterLine(value: String?): String {
            var text: String? = NavigationState.safe(value)
            if (text!!.isEmpty()) return ""
            val bytes: Int = text.indexOf(" bytes=")
            if (bytes >= 0) text = text.substring(0, bytes)
            text = text.replace("maneuver+gray", "манёвр + серая")
            text = text.replace("maneuver", "манёвр")
            text = text.replace("finish direction", "стрелка к финишу")
            text = text.replace("compass step=", "компас шаг ")
            text = text.replace("speedLimit=", "лимит ")
            text = text.replace("text=", "текст ")
            text = text.replace("eta distance=", "остаток ")
            text = text.replace("eta time=", "прибытие ")
            text = text.replace("gray=", "серая ")
            text = text.replace("dist=", "до ")
            text = text.replace("progress=", "прогресс ")
            text = text.replace("context_ra_roundabout_exit_1", "круговое, 1-й съезд")
            text = text.replace("context_ra_roundabout_exit_2", "круговое, 2-й съезд")
            text = text.replace("context_ra_roundabout_exit_3", "круговое, 3-й съезд")
            text = text.replace("context_ra_roundabout_exit_4", "круговое, 4-й съезд")
            text = text.replace("context_ra_in_circular_movement", "круговое")
            text = text.replace("context_ra_turn_back_right", "разворот направо")
            text = text.replace("context_ra_turn_back_left", "разворот налево")
            text = text.replace("context_ra_turn_back", "разворот")
            text = text.replace("context_ra_take_right", "держитесь правее")
            text = text.replace("context_ra_take_left", "держитесь левее")
            text = text.replace("context_ra_exit_right", "съезд направо")
            text = text.replace("context_ra_exit_left", "съезд налево")
            text = text.replace("context_ra_hard_turn_right", "резко направо")
            text = text.replace("context_ra_hard_turn_left", "резко налево")
            text = text.replace("context_ra_turn_right", "направо")
            text = text.replace("context_ra_turn_left", "налево")
            text = text.replace("context_ra_forward", "прямо")
            text = text.replace("context_ra_finish", "финиш")
            text = text.replace("context_ra_direction_to_finish", "стрелка к финишу")
            text =
                text.replace(
                    "context_ra_gray_straight_left_right",
                    "серая прямо + налево + направо",
                )
            text = text.replace("context_ra_gray_left_right", "серая налево + направо")
            text =
                text.replace(
                    "context_ra_gray_straight_left_exit_right",
                    "серая прямо + налево + съезд направо",
                )
            text =
                text.replace(
                    "context_ra_gray_straight_exit_left_right",
                    "серая прямо + съезд налево + направо",
                )
            text = text.replace("context_ra_gray_left_exit_right", "серая налево + съезд направо")
            text = text.replace("context_ra_gray_exit_left_right", "серая съезд налево + направо")
            text = text.replace("context_ra_gray_straight_right", "серая прямо + направо")
            text = text.replace("context_ra_gray_straight_left", "серая прямо + налево")
            text = text.replace("context_ra_gray_exit_right", "серая прямо + съезд направо")
            text = text.replace("context_ra_gray_only_exit_right", "серая съезд направо")
            text = text.replace("context_ra_gray_straight_exit_left", "серая прямо + съезд налево")
            text = text.replace("context_ra_gray_exit_left", "серая съезд налево")
            text = text.replace("context_ra_gray_hard_right", "серая резко направо")
            text = text.replace("context_ra_gray_hard_left", "серая резко налево")
            text = text.replace("context_ra_gray_right", "серая направо")
            text = text.replace("context_ra_gray_left", "серая налево")
            text = text.replace("context_ra_gray_straight", "серая прямо")
            return text.trim()
        }

        private fun maneuverLabel(maneuver: String?): String {
            val p: String = NavigationState.safe(maneuver).lowercase(Locale.ROOT)
            if (p.contains("turn_back"))
                return if (p.contains("right")) "разворот направо" else "разворот налево"
            if (p.contains("take_left")) return "держитесь левее"
            if (p.contains("take_right")) return "держитесь правее"
            if (p.contains("exit_left")) return "съезд налево"
            if (p.contains("exit_right")) return "съезд направо"
            if (p.contains("hard_turn_left")) return "резко налево"
            if (p.contains("hard_turn_right")) return "резко направо"
            if (p.contains("turn_left") || p.contains("left")) return "налево"
            if (p.contains("turn_right") || p.contains("right")) return "направо"
            if (p.contains("forward") || p.contains("straight")) return "прямо"
            return ""
        }

        private fun first(a: String?, b: String?): String {
            return if (!NavigationState.safe(a).isEmpty()) NavigationState.safe(a)
            else NavigationState.safe(b)
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
