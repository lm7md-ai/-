package sa.rihla.core

import java.time.LocalDate
import sa.rihla.data.*
import sa.rihla.ui.day

data class DaySpending(val date: LocalDate, val halala: Long)

fun spendingDays(expenses: List<Expense>, today: LocalDate, count: Int = 7): List<DaySpending> {
    require(count in 1..31)
    val amounts =
        expenses
            .groupBy { day(it.createdAt) }
            .mapValues { (_, values) -> values.sumOf { it.amountHalala } }
    return (count - 1 downTo 0).map { offset ->
        today.minusDays(offset.toLong()).let { DaySpending(it, amounts[it] ?: 0) }
    }
}

fun filterOutings(
    outings: List<Outing>,
    details: List<TripDetails>,
    query: String,
    favorites: Boolean,
    from: Long = 0,
    sort: String = "RECENT",
): List<Outing> {
    val annotations = details.associateBy { it.outingId }
    val text = normalizeSms(query).trim()
    val list = outings.filter { outing ->
        val info = annotations[outing.id]
        outing.startedAt >= from &&
            (!favorites || info?.favorite == true) &&
            (text.isEmpty() ||
                listOf(
                        info?.title ?: "",
                        info?.note ?: "",
                        outing.classification,
                        day(outing.startedAt).toString(),
                    )
                    .any { normalizeSms(it).contains(text, ignoreCase = true) })
    }
    return when (sort) {
        "DISTANCE" -> list.sortedByDescending { it.state.meters }
        "DURATION" -> list.sortedByDescending { (it.endedAt ?: it.state.lastTime) - it.startedAt }
        else -> list.sortedByDescending { it.startedAt }
    }
}

fun budgetProgress(spent: Long, budget: Long): Float =
    if (budget <= 0) 0f else (spent.toDouble() / budget).coerceIn(0.0, 1.0).toFloat()
