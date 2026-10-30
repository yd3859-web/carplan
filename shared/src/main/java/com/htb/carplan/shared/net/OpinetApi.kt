package com.htb.carplan.shared.net

import com.htb.carplan.shared.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One gas station from Opinet: price per litre of the asked product, distance from the search point. */
data class GasStation(val id: String, val name: String, val brand: String, val price: Int, val meters: Int, val place: Place)

class OpinetKeyMissing : IllegalStateException("오피넷 키가 없어요. local.properties에 OPINET_KEY=를 넣으면 최저가 주유소가 나와요")

/**
 * 12장: cheapest gas stations around a point (오피넷 "반경 내 주유소"). Opinet speaks KATEC,
 * so coordinates go through [Katec] both ways. Product codes: B027 휘발유, D047 경유, K015 LPG.
 */
class OpinetApi(private val key: String, private val client: OkHttpClient = OkHttpClient()) {
    val available: Boolean get() = key.isNotBlank()

    /** Stations within [radiusMeters] (≤ 5000), cheapest first. */
    suspend fun around(lat: Double, lng: Double, product: String = "B027", radiusMeters: Int = 5000): List<GasStation> = withContext(Dispatchers.IO) {
        if (!available) throw OpinetKeyMissing()
        val xy = Katec.fromWgs(lat, lng)
        val url = "https://www.opinet.co.kr/api/aroundAll.do?code=$key&x=${"%.0f".format(xy.x)}&y=${"%.0f".format(xy.y)}&radius=${radiusMeters.coerceIn(0, 5000)}&sort=2&prodcd=$product&out=json"
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        val arr = JSONObject(body).optJSONObject("RESULT")?.optJSONArray("OIL") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val (wLat, wLng) = Katec.toWgs(o.optDouble("GIS_X_COOR"), o.optDouble("GIS_Y_COOR"))
            GasStation(
                id = o.optString("UNI_ID"),
                name = o.optString("OS_NM"),
                brand = brandName(o.optString("POLL_DIV_CD")),
                price = o.optInt("PRICE"),
                meters = o.optDouble("DISTANCE").toInt(),
                place = Place(name = o.optString("OS_NM"), lat = wLat, lng = wLng),
            )
        }.filter { it.price > 0 }.sortedBy { it.price }
    }

    private fun brandName(code: String) = when (code) {
        "SKE" -> "SK에너지"; "GSC" -> "GS칼텍스"; "HDO" -> "현대오일뱅크"; "SOL" -> "S-OIL"; "RTO" -> "자영알뜰"; "RTX" -> "고속도로알뜰"; "NHO" -> "농협알뜰"; "ETC" -> "자가상표"; else -> code
    }
}
