package com.htb.carplan.shared.net

import com.htb.carplan.shared.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thrown when no Kakao REST key is configured; callers fall back to offline behaviour. */
class KakaoKeyMissing : Exception("KAKAO_REST_KEY가 local.properties에 없어요")

/** Kakao Local category group codes used by 근처 추천 (3장) and later chapters. */
enum class KakaoCategory(val code: String, val label: String) {
    CAFE("CE7", "카페"),
    FOOD("FD6", "맛집"),
    SIGHT("AT4", "관광지"),
    PARKING("PK6", "주차장"),
    GAS("OL7", "주유소"),
}

/** One leg between two places: how long the car drives, by road. */
data class Leg(val seconds: Int, val meters: Int)

/** A drivable route: its summary plus the road geometry as (lat, lng) points (11장 4: the line on the map). */
data class Route(val leg: Leg, val path: List<Pair<Double, Double>>)

/**
 * Kakao Local (place search) + Kakao Mobility (directions) over the REST API.
 * The key comes from each app's BuildConfig (read from local.properties); with an empty key
 * every call fails fast with [KakaoKeyMissing] so screens can show their offline fallback.
 */
class KakaoApi(private val restKey: String) {
    val available: Boolean get() = restKey.isNotBlank()

    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    /** Keyword search; [near] biases results toward a point (lat/lng) when given. */
    suspend fun searchKeyword(query: String, near: Pair<Double, Double>? = null, size: Int = 10): List<Place> {
        if (query.isBlank()) return emptyList()
        val url = "https://dapi.kakao.com/v2/local/search/keyword.json".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("size", size.toString())
            .apply { near?.let { (lat, lng) -> addQueryParameter("y", lat.toString()); addQueryParameter("x", lng.toString()) } }
            .build()
        return get(url.toString()).documents().map { it.toPlace() }
    }

    /** Places of one category around a point, closest first. */
    suspend fun searchCategory(category: KakaoCategory, lat: Double, lng: Double, radiusMeters: Int = 3000, size: Int = 15): List<Place> {
        val url = "https://dapi.kakao.com/v2/local/search/category.json".toHttpUrl().newBuilder()
            .addQueryParameter("category_group_code", category.code)
            .addQueryParameter("y", lat.toString())
            .addQueryParameter("x", lng.toString())
            .addQueryParameter("radius", radiusMeters.coerceIn(0, 20000).toString())
            .addQueryParameter("sort", "distance")
            .addQueryParameter("size", size.toString())
            .build()
        return get(url.toString()).documents().map { it.toPlace() }
    }

    /** The nearest place name to a coordinate (13장: what is this parking spot called?). */
    suspend fun nearestPlace(lat: Double, lng: Double): Place? =
        KakaoCategory.entries.firstNotNullOfOrNull { c -> searchCategory(c, lat, lng, radiusMeters = 100, size = 1).firstOrNull() }

    /**
     * The address of a point (Kakao 좌표→주소, one call): road address if there is one, else the lot address, e.g.
     * "경기 성남시 분당구 판교역로 166". For a spot picked on a map (픽업 자리).
     */
    suspend fun address(lat: Double, lng: Double): String? {
        val url = "https://dapi.kakao.com/v2/local/geo/coord2address.json".toHttpUrl().newBuilder()
            .addQueryParameter("x", lng.toString()).addQueryParameter("y", lat.toString()).build()
        val doc = get(url.toString()).optJSONArray("documents")?.optJSONObject(0) ?: return null
        return doc.optJSONObject("road_address")?.optString("address_name")?.takeIf { it.isNotBlank() }
            ?: doc.optJSONObject("address")?.optString("address_name")?.takeIf { it.isNotBlank() }
    }

    /** Driving time and distance from [from] to [to] (Kakao Mobility directions). */
    suspend fun directions(from: Place, to: Place): Leg {
        val url = "https://apis-navi.kakaomobility.com/v1/directions".toHttpUrl().newBuilder()
            .addQueryParameter("origin", "${from.lng},${from.lat}")
            .addQueryParameter("destination", "${to.lng},${to.lat}")
            .addQueryParameter("summary", "true")
            .build()
        val summary = get(url.toString()).getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
        return Leg(seconds = summary.getInt("duration"), meters = summary.getInt("distance"))
    }

    /** Like [directions] but also returns the road geometry (Kakao Mobility, summary=false). */
    suspend fun route(from: Place, to: Place): Route {
        val url = "https://apis-navi.kakaomobility.com/v1/directions".toHttpUrl().newBuilder()
            .addQueryParameter("origin", "${from.lng},${from.lat}")
            .addQueryParameter("destination", "${to.lng},${to.lat}")
            .build()
        val r = get(url.toString()).getJSONArray("routes").getJSONObject(0)
        val summary = r.getJSONObject("summary")
        val path = ArrayList<Pair<Double, Double>>()
        val sections = r.optJSONArray("sections")
        for (i in 0 until (sections?.length() ?: 0)) {
            val roads = sections!!.getJSONObject(i).optJSONArray("roads") ?: continue
            for (j in 0 until roads.length()) {
                val v = roads.getJSONObject(j).getJSONArray("vertexes")
                var k = 0
                while (k + 1 < v.length()) { path += v.getDouble(k + 1) to v.getDouble(k); k += 2 } // x=lng, y=lat
            }
        }
        return Route(Leg(seconds = summary.getInt("duration"), meters = summary.getInt("distance")), path)
    }

    private suspend fun get(url: String): JSONObject {
        if (!available) throw KakaoKeyMissing()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("Authorization", "KakaoAK $restKey").build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("Kakao ${res.code}: ${body.take(200)}")
                JSONObject(body)
            }
        }
    }

    private fun JSONObject.documents(): List<JSONObject> {
        val arr = getJSONArray("documents")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun JSONObject.toPlace() = Place(
        name = getString("place_name"),
        lat = getString("y").toDouble(),
        lng = getString("x").toDouble(),
        address = optString("road_address_name").ifBlank { optString("address_name") },
        kakaoId = optString("id").ifBlank { null },
        category = optString("category_name"),
        phone = optString("phone"),
    )
}
