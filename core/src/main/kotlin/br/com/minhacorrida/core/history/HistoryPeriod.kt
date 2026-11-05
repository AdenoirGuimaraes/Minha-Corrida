package br.com.minhacorrida.core.history

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Filtro do histórico. A semana vai de segunda a domingo. */
enum class HistoryPeriod { DAY, WEEK, MONTH }

/** Intervalo de datas [start, endExclusive) de um período. */
data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
    /** Último dia incluído no intervalo. */
    val endInclusive: LocalDate get() = endExclusive.minusDays(1)

    fun startMillis(zone: ZoneId): Long = start.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endMillis(zone: ZoneId): Long = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Intervalo do período que contém [reference]. */
fun HistoryPeriod.rangeOf(reference: LocalDate): DateRange = when (this) {
    HistoryPeriod.DAY -> DateRange(reference, reference.plusDays(1))
    HistoryPeriod.WEEK -> {
        val monday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        DateRange(monday, monday.plusWeeks(1))
    }
    HistoryPeriod.MONTH -> {
        val first = reference.withDayOfMonth(1)
        DateRange(first, first.plusMonths(1))
    }
}

/** Mesma data de referência deslocada um período para trás (-1) ou para frente (+1). */
fun HistoryPeriod.shift(reference: LocalDate, steps: Long): LocalDate = when (this) {
    HistoryPeriod.DAY -> reference.plusDays(steps)
    HistoryPeriod.WEEK -> reference.plusWeeks(steps)
    HistoryPeriod.MONTH -> reference.plusMonths(steps)
}
