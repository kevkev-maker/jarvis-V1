package com.example.myapp.jarvis

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Nearby places from OpenStreetMap (free, no key, community map data).
 * It knows names, locations and some details (outdoor seating, Wi-Fi...),
 * but NOT reviews or ratings, and some new places may be missing.
 */
object PlacesFinder {

    data class Category(val plural: String, val key: String, val value: String, val words: List<String>)

    data class Place(
        val name: String,
        val lat: Double,
        val lon: Double,
        val distanceM: Int,
        val tags: Map<String, String>
    ) {
        val mapsLink: String get() = "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon"

        fun features(): List<String> = buildList {
            if (tags["outdoor_seating"] == "yes") add("outdoor seating")
            if (tags["internet_access"] in listOf("wlan", "yes", "wifi")) add("Wi-Fi")
            if (tags["air_conditioning"] == "yes") add("air conditioning")
            if (tags.containsKey("contact:instagram") || tags.containsKey("instagram")) add("an Instagram page")
            if (tags.containsKey("website") || tags.containsKey("contact:website")) add("a website")
        }
    }

    data class Found(val places: List<Place>, val radiusM: Int)

    private val CATEGORIES = listOf(
        Category("cafes", "amenity", "cafe", listOf("cafe", "cafes", "caf", "coffee shop", "coffee shops")),
        Category("restaurants", "amenity", "restaurant", listOf("restaurant", "restaurants", "place to eat", "places to eat")),
        Category("pharmacies", "amenity", "pharmacy", listOf("pharmacy", "pharmacies", "medical store", "medical shop", "chemist")),
        Category("ATMs", "amenity", "atm", listOf("atm", "atms")),
        Category("petrol pumps", "amenity", "fuel", listOf("petrol pump", "petrol pumps", "petrol bunk", "gas station", "fuel station")),
        Category("hospitals", "amenity", "hospital", listOf("hospital", "hospitals")),
        Category("bakeries", "shop", "bakery", listOf("bakery", "bakeries")),
        Category("ice cream shops", "amenity", "ice_cream", listOf("ice cream"))
    )

    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun detect(text: String): Category? =
        CATEGORIES.firstOrNull { c ->
            c.words.any { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(text) }
        }

    /** Searches 1.5 km, widening to 4 km if fewer than 3 results. null = map service unreachable. */
    suspend fun nearby(lat: Double, lon: Double, cat: Category): Found? = withContext(Dispatchers.IO) {
        for (radius in listOf(1500, 4000)) {
            val raw = query(lat, lon, cat, radius) ?: return@withContext null
            val places = try { parse(raw, lat, lon) } catch (e: Exception) { return@withContext null }
            if (places.size >= 3 || radius == 4000) return@withContext Found(places, radius)
        }
        null
    }

    /** Picks one: mostly the closest, with a bonus for listed amenities. */
    fun pick(places: List<Place>): Place =
        places.take(8).maxByOrNull { p -> -p.distanceM / 150.0 + p.features().size * 1.5 } ?: places.first()

    fun distanceText(m: Int): String =
        if (m < 1000) "${(m / 10) * 10} metres" else String.format(java.util.Locale.ENGLISH, "%.1f kilometres", m / 1000.0)

    private fun query(lat: Double, lon: Double, cat: Category, radius: Int): String? {
        val ql = "[out:json][timeout:20];" +
            "nwr[\"${cat.key}\"=\"${cat.value}\"](around:$radius,$lat,$lon);" +
            "out center tags 80;"
        for (url in ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "JarvisPersonalAssistant/1.0 (personal Android app)")
                    .post(FormBody.Builder().add("data", ql).build())
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string()
                        if (body != null) return body
                    }
                }
            } catch (e: Exception) {
                Log.w("JARVIS_PLACES", "$url failed: ${e.message}")
            }
        }
        return null
    }

    private fun parse(json: String, lat: Double, lon: Double): List<Place> {
        val arr = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Place>()
        val dist = FloatArray(1)
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val t = e.optJSONObject("tags") ?: continue
            val name = t.optString("name").trim()
            if (name.isEmpty()) continue
            val plat: Double
            val plon: Double
            if (e.has("lat")) {
                plat = e.getDouble("lat"); plon = e.getDouble("lon")
            } else if (e.has("center")) {
                val c = e.getJSONObject("center")
                plat = c.getDouble("lat"); plon = c.getDouble("lon")
            } else continue
            val tags = HashMap<String, String>()
            val keys = t.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                tags[k] = t.optString(k)
            }
            Location.distanceBetween(lat, lon, plat, plon, dist)
            out.add(Place(name, plat, plon, dist[0].toInt(), tags))
        }
        return out
            .distinctBy { it.name.lowercase() + "|" + (it.lat * 1000).toInt() + "|" + (it.lon * 1000).toInt() }
            .sortedBy { it.distanceM }
    }
}
