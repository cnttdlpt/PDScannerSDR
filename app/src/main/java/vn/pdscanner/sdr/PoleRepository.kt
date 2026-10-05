package vn.pdscanner.sdr

import android.content.Context
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compact offline copy of the user's GIS pole layer.
 * Source fields are extracted from npc_tt_cotdien_2.dbf and coordinates are WGS84.
 */
data class Pole(
    val objectId: String,
    val code: String,
    val voltage: String,
    val managementUnit: String,
    val address: String,
    val feeder: String,
    val longitude: Double,
    val latitude: Double,
    val displayName: String,
    val note: String,
    val assetOwner: String
)

data class NearestPole(
    val pole: Pole,
    val distanceMeters: Double
)

class PoleRepository(private val context: Context) {
    private val poles = ArrayList<Pole>(8000)

    val count: Int
        get() = poles.size

    fun load(): Int {
        if (poles.isNotEmpty()) return poles.size

        context.assets.open("poles.tsv").bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLine() // header
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val p = line.split('\t')
                if (p.size < 11) return@forEachLine

                val lon = p[6].toDoubleOrNull() ?: return@forEachLine
                val lat = p[7].toDoubleOrNull() ?: return@forEachLine

                poles.add(
                    Pole(
                        objectId = p[0],
                        code = p[1],
                        voltage = p[2],
                        managementUnit = p[3],
                        address = p[4],
                        feeder = p[5],
                        longitude = lon,
                        latitude = lat,
                        displayName = p[8],
                        note = p[9],
                        assetOwner = p[10]
                    )
                )
            }
        }
        return poles.size
    }

    /**
     * Finds the nearest pole. A cheap local-plane pass picks the candidate,
     * then Haversine gives the displayed distance.
     */
    fun nearest(latitude: Double, longitude: Double): NearestPole? {
        if (poles.isEmpty()) return null

        val lonScale = cos(Math.toRadians(latitude)).coerceAtLeast(0.1)
        var best: Pole? = null
        var bestD2 = Double.POSITIVE_INFINITY

        for (pole in poles) {
            val dx = (pole.longitude - longitude) * lonScale
            val dy = pole.latitude - latitude
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) {
                bestD2 = d2
                best = pole
            }
        }

        val p = best ?: return null
        return NearestPole(
            pole = p,
            distanceMeters = haversineMeters(latitude, longitude, p.latitude, p.longitude)
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2.0) * sin(dPhi / 2.0) +
                cos(phi1) * cos(phi2) * sin(dLambda / 2.0) * sin(dLambda / 2.0)
        return 2.0 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
