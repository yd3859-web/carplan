package com.htb.carplan.phone.trip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.MapMarker
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.SimpleMap
import com.htb.carplan.shared.model.Place
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 픽업 자리 지도에서 찍기 (팀장 2026-11-04: "지도 켜서 내 위치 실시간으로, 찍어서"). The map opens on where I am, the dot
 * follows me (GPS/network every 2 s), drag to move the map, tap to put the pickup pin; [내 위치로] puts it where I
 * stand. The pin's address comes from Kakao once it settles (one call), and [여기로 정하기] makes it the pickup.
 * [이름으로 찾기] is still there for a known place.
 */
@Composable
fun MapPickSheet(start: Place?, onPick: (Place) -> Unit, onSearch: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var me by remember { mutableStateOf(lastKnownLocation(context)) }
    var center by remember { mutableStateOf(start?.takeIf { it.lat != 0.0 }?.let { it.lat to it.lng } ?: me ?: (37.5665 to 126.9780)) }
    var pin by remember { mutableStateOf(start?.takeIf { it.lat != 0.0 }?.let { it.lat to it.lng }) }
    var followed by remember { mutableStateOf(start?.lat?.let { it != 0.0 } == true) } // once the map has moved to me, it stays where the user puts it
    var address by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.any { it } }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    // Live position while the window is open.
    DisposableEffect(granted) {
        val stop = if (granted) listenLocation(context) { la, lo ->
            me = la to lo
            if (!followed) { center = la to lo; followed = true }
        } else ({})
        onDispose { stop() }
    }
    // The pin's address, after it stops moving.
    LaunchedEffect(pin) {
        address = null
        val p = pin ?: return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        address = if (kakao.available) runCatching { kakao.address(p.first, p.second) }.getOrNull() else null
    }
    val zoom = 16
    val density = LocalDensity.current
    val tilePx = with(density) { 256.dp.toPx() }
    var w by remember { mutableIntStateOf(1) }
    var h by remember { mutableIntStateOf(1) }

    CenterSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("픽업 자리 정하기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
            Text(if (granted) "지도를 끌어서 옮기고, 태울 곳을 누르세요" else "위치를 켜면 내 자리가 보여요 · 지도는 그대로 쓸 수 있어요",
                fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            Box(
                Modifier.fillMaxWidth().height(360.dp).clip(Corner)
                    .onSizeChanged { w = it.width; h = it.height }
                    .pointerInput(Unit) {
                        detectTapGestures { o -> pin = screenToGeo(center, zoom, tilePx, o.x - w / 2f, o.y - h / 2f) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { c, d ->
                            c.consume()
                            center = screenToGeo(center, zoom, tilePx, -d.x, -d.y)
                        }
                    },
            ) {
                SimpleMap(
                    centerLat = center.first, centerLng = center.second, zoom = zoom,
                    markers = listOfNotNull(
                        me?.let { MapMarker(it.first, it.second, "나", MapMarker.Kind.ME) },
                        pin?.let { MapMarker(it.first, it.second, "픽업", MapMarker.Kind.PICKUP) },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                when {
                    pin == null -> "아직 안 골랐어요"
                    address != null -> address!!
                    kakao.available -> "주소 찾는 중…"
                    else -> "%.5f, %.5f".format(pin!!.first, pin!!.second)
                },
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (pin == null) Palette.TextSoft else Palette.TextStrong,
                modifier = Modifier.padding(top = 10.dp), maxLines = 2,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { me?.let { pin = it; center = it } ?: run { if (!granted) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } },
                    shape = Corner, border = BorderStroke(1.dp, Palette.Line), modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (me != null) "내 위치로" else "위치 찾는 중", fontSize = 16.sp, color = Palette.TextStrong, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = {
                        val p = pin ?: return@Button
                        val name = address?.split(" ")?.takeLast(2)?.joinToString(" ")?.let { "$it 앞" } ?: "지도에서 고른 곳"
                        onPick(Place(name = name, lat = p.first, lng = p.second, address = address.orEmpty()))
                    },
                    enabled = pin != null, shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("여기로 정하기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSearch) { Text("이름으로 찾기", fontSize = 15.sp, color = Palette.Navy) }
                TextButton(onClick = onDismiss) { Text("닫기", fontSize = 15.sp, color = Palette.TextSoft) }
            }
        }
    }
}

/** The point [dx],[dy] screen pixels from [center] on a Web-Mercator map at [zoom] (tiles of [tilePx]). */
private fun screenToGeo(center: Pair<Double, Double>, zoom: Int, tilePx: Float, dx: Float, dy: Float): Pair<Double, Double> {
    val world = 2.0.pow(zoom) * tilePx
    val cx = (center.second + 180) / 360 * world
    val cy = (1 - ln(tan(Math.toRadians(center.first)) + 1 / Math.cos(Math.toRadians(center.first))) / PI) / 2 * world
    val x = cx + dx; val y = cy + dy
    val lng = x / world * 360 - 180
    val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / world))))
    return lat.coerceIn(-85.0, 85.0) to lng
}

/** GPS and network updates every 2 s; returns the way to stop. */
@SuppressLint("MissingPermission")
private fun listenLocation(context: Context, onFix: (Double, Double) -> Unit): () -> Unit {
    val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!ok) return {}
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = LocationListener { onFix(it.latitude, it.longitude) }
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
        runCatching { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 2_000L, 0f, listener, context.mainLooper) }
    }
    return { runCatching { lm.removeUpdates(listener) } }
}

private fun lastKnownLocation(context: Context) = com.htb.carplan.phone.vote.lastKnownLocation(context)
private fun hasLocationPermission(context: Context) = com.htb.carplan.phone.vote.hasLocationPermission(context)
