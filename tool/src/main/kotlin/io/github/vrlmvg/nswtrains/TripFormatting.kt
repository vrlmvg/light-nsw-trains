package io.github.vrlmvg.nswtrains

import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** 8:05am / 12:40pm, in Sydney time. */
fun formatClock(instant: Instant): String {
    val local = instant.toLocalDateTime(SYDNEY)
    val hour12 = when (val h = local.hour % 12) {
        0 -> 12
        else -> h
    }
    val suffix = if (local.hour < 12) "am" else "pm"
    return "%d:%02d%s".format(hour12, local.minute, suffix)
}

fun formatDuration(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}

/** Countdown to departure: "now", "in 6 min", "in 1 hr 5 min", or "departed". */
fun formatCountdown(departure: Instant, now: Instant): String {
    val minutes = (departure - now).inWholeMinutes
    return when {
        departure < now && minutes <= -1 -> "departed"
        minutes < 1 -> "now"
        else -> "in ${formatDuration(minutes)}"
    }
}

fun formatDelay(leg: Leg): String {
    if (!leg.isRealtime) return "Scheduled"
    val delay = leg.departureDelayMinutes ?: return "Scheduled"
    return when {
        delay <= 0 -> "On time"
        else -> "$delay min late"
    }
}

fun formatChanges(changes: Int): String = when (changes) {
    0 -> "Direct"
    1 -> "1 change"
    else -> "$changes changes"
}

/** One-line summary of a journey: "T1 · Platform 18 · Direct · On time". */
fun journeySummary(journey: Journey): String {
    val first = journey.firstVehicleLeg ?: return "Walk"
    return listOfNotNull(
        first.line,
        first.originPlatform,
        formatChanges(journey.changes),
        formatDelay(first),
    ).joinToString(" · ")
}

fun modeLabel(mode: TransportMode): String = when (mode) {
    TransportMode.Train -> "Train"
    TransportMode.Metro -> "Metro"
    TransportMode.LightRail -> "Light rail"
    TransportMode.Bus -> "Bus"
    TransportMode.Coach -> "Coach"
    TransportMode.Ferry -> "Ferry"
    TransportMode.SchoolBus -> "School bus"
    TransportMode.Walk -> "Walk"
    TransportMode.Other -> "Service"
}
