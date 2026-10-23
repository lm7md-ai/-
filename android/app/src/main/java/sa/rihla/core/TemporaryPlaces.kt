package sa.rihla.core

import sa.rihla.data.SavedPlace

data class TemporaryPlaceInfo(
    val home: Boolean,
    val created: Long,
    val expires: Long,
    val name: String,
)

fun temporaryPlaceInfo(type: String): TemporaryPlaceInfo? {
    val parts = type.split(':', limit = 4)
    if (parts.size != 4 || parts[0] !in listOf("TEMP_HOME", "TEMP_REST")) return null
    val created = parts[1].toLongOrNull() ?: return null
    val expires = parts[2].toLongOrNull() ?: return null
    if (created <= 0 || expires <= created || parts[3].isBlank()) return null
    return TemporaryPlaceInfo(parts[0] == "TEMP_HOME", created, expires, parts[3])
}

fun temporaryPlaceType(home: Boolean, created: Long, expires: Long, name: String): String {
    require(created > 0 && expires > created && name.trim().length in 1..60)
    return "${if(home)"TEMP_HOME" else "TEMP_REST"}:$created:$expires:${name.trim()}"
}

fun placeDisplayName(type: String): String =
    temporaryPlaceInfo(type)?.let { (if (it.home) "منزل مؤقت: " else "استراحة مؤقتة: ") + it.name }
        ?: when {
            type == "HOME" -> "المنزل الدائم"
            type == "UNIVERSITY" -> "الجامعة"
            type.startsWith("PIN:") -> type.removePrefix("PIN:")
            else -> "مكان محفوظ"
        }

/** Temporary rests suspend automatic home ending without changing the trip origin or its points. */
fun insideActiveTemporaryPlace(places: List<SavedPlace>, fix: Fix): Boolean = places.any { place ->
    val info = temporaryPlaceInfo(place.type)
    info != null &&
        fix.time in info.created until info.expires &&
        distance(place.latitude, place.longitude, fix.lat, fix.lon) + fix.accuracy <= place.radius
}
