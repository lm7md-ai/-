package sa.rihla.core

import java.time.Instant
import sa.rihla.data.*

private fun csvCell(value: String): String {
    // Prevent spreadsheet formulas when opening a user-authored merchant or title.
    val safe =
        if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + value else value
    return "\"" + safe.replace("\"", "\"\"") + "\""
}

fun expensesCsv(expenses: List<Expense>): String =
    "\uFEFF" +
        "date,category,amount_sar,merchant,source,outing_id\r\n" +
        expenses.joinToString("\r\n") { expense ->
            listOf(
                    Instant.ofEpochMilli(expense.createdAt).toString(),
                    expense.category,
                    java.math.BigDecimal(expense.amountHalala).movePointLeft(2).toPlainString(),
                    expense.merchant,
                    expense.source,
                    expense.outingId?.toString() ?: "",
                )
                .joinToString(",") { csvCell(it) }
        }

fun routeGpx(points: List<RoutePoint>): String = buildString {
    append(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"Rihla\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
    )
    points.forEachIndexed { index, point ->
        if (index > 0 && point.breakBefore) append("</trkseg><trkseg>")
        append(
            "<trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\"><ele>${point.altitude}</ele><time>${Instant.ofEpochMilli(point.recordedAt)}</time></trkpt>"
        )
    }
    append("</trkseg></trk></gpx>")
}
