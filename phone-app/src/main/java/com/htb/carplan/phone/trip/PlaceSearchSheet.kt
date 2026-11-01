package com.htb.carplan.phone.trip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import androidx.compose.material3.HorizontalDivider
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.net.KakaoCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val RECOMMEND = listOf(KakaoCategory.CAFE, KakaoCategory.FOOD, KakaoCategory.SIGHT)

/**
 * 장소 고르기 (3장 2·4): Kakao keyword search plus 근처 추천 chips (카페·맛집·관광지).
 * Without a Kakao key the sheet lets the user type a name and saves it without coordinates;
 * the place can be replaced by a real search later.
 */
@OptIn(ExperimentalMaterial3Api::class)
/** What the place sheet searches with: a trip day (biased to its stops) or nothing around it (빠른 일정 입력). */
interface PlaceSource {
    val kakaoAvailable: Boolean
    suspend fun search(query: String): Result<List<Place>>
    suspend fun recommend(category: KakaoCategory, near: Pair<Double, Double>?): Result<List<Place>>
    /** A place of the plan that searches lean on; null = use the phone's location. */
    fun anchor(): Place?
    /** Q58: what my groups think of places (또 갈래요 · 가 봄) — results are ordered and labelled by it. */
    suspend fun memos(): Map<String, com.htb.carplan.shared.model.PlaceMemo> = emptyMap()
}

/** No itinerary yet: search plainly, recommend around the phone. */
object FreePlaces : PlaceSource {
    override val kakaoAvailable: Boolean get() = kakao.available
    override suspend fun search(query: String): Result<List<Place>> = runCatching { kakao.searchKeyword(query, near = null) }
    override suspend fun recommend(category: KakaoCategory, near: Pair<Double, Double>?): Result<List<Place>> = runCatching {
        val (lat, lng) = near ?: throw IllegalStateException("위치를 켜면 근처를 찾아 드려요")
        kakao.searchCategory(category, lat, lng)
    }
    override fun anchor(): Place? = null
}

@Composable
fun PlaceSearchSheet(
    vm: PlaceSource,
    initialQuery: String = "",
    onPick: (Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var activeCategory by remember { mutableStateOf<KakaoCategory?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val hasKey = vm.kakaoAvailable
    var memos by remember { mutableStateOf<Map<String, com.htb.carplan.shared.model.PlaceMemo>>(emptyMap()) }
    LaunchedEffect(Unit) { memos = runCatching { vm.memos() }.getOrDefault(emptyMap()) }

    fun runSearch() {
        activeCategory = null
        if (!hasKey) return
        scope.launch {
            loading = true
            vm.search(query).onSuccess { results = it; message = if (it.isEmpty()) "검색 결과가 없어요" else null }
                .onFailure { message = it.message }
            loading = false
        }
    }

    fun runRecommend(c: KakaoCategory) {
        activeCategory = c
        scope.launch {
            loading = true
            val here = if (hasLocationPermission(context)) currentLocation(context) else null
            vm.recommend(c, here?.let { it.latitude to it.longitude })
                .onSuccess { results = it; message = if (it.isEmpty()) "근처에 ${c.label}이(가) 없어요" else null }
                .onFailure { message = it.message }
            loading = false
        }
    }

    var pendingCategory by remember { mutableStateOf<KakaoCategory?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingCategory?.let(::runRecommend)
        pendingCategory = null
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    CenterSheet(onDismiss = onDismiss) {
        Column(Modifier.imePadding().height(520.dp)) {
            Text("장소 고르기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong, modifier = Modifier.padding(bottom = 12.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (hasKey) "장소 이름으로 찾기" else "장소 이름", fontSize = 17.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                shape = Corner,
                colors = TextFieldDefaults.colors(focusedContainerColor = Palette.Background, unfocusedContainerColor = Palette.Background, focusedIndicatorColor = Palette.Navy, unfocusedIndicatorColor = Palette.SheetEdge),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("근처 추천", fontSize = 16.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 12.dp))
                RECOMMEND.forEach { c ->
                    FilterChip(
                        selected = activeCategory == c,
                        onClick = {
                            if (!hasKey) {
                                message = "지금은 근처 추천을 쓸 수 없어요"
                            } else if (hasLocationPermission(context) || vm.anchor() != null) {
                                runRecommend(c)
                            } else {
                                pendingCategory = c
                                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        },
                        label = { Text(c.label, fontSize = 17.sp) },
                        shape = Corner, colors = com.htb.carplan.phone.ui.inkChipColors(),
                    )
                }
            }
            if (!hasKey) {
                Text(
                    "지금은 장소 찾기가 안 돼요. 이름을 쓰고 아래 버튼을 누르면 이름만 저장되고, 갈 때 내비가 찾아 줘요.",
                    fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp),
                )
            }
            message?.let { Text(it, fontSize = 15.sp, color = Palette.Sunday, modifier = Modifier.padding(top = 8.dp)) }
            if (loading) Text("찾는 중…", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 8.dp))

            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(com.htb.carplan.shared.calendar.PlaceRank.rank(results, memos), key = { it.kakaoId ?: it.name + it.lat }) { p ->
                    PlaceRow(p, com.htb.carplan.shared.calendar.PlaceRank.badge(p, memos)) { onPick(p) }
                }
            }

            if (query.isNotBlank()) {
                TextButton(onClick = { onPick(Place(name = query.trim())) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasKey) "\"${query.trim()}\" 이름만으로 넣기" else "\"${query.trim()}\" 넣기", color = Palette.Navy, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: Place, badge: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong, modifier = Modifier.weight(1f, fill = false))
            // Q58: my groups' memory of this place, small and quiet.
            badge?.let { Text(it, fontSize = 14.sp, color = Palette.TextSoft, modifier = Modifier.padding(start = 8.dp).border(1.dp, Palette.Line, Corner).padding(horizontal = 6.dp, vertical = 1.dp)) }
        }
        if (p.address.isNotBlank()) Text(p.address, fontSize = 15.sp, color = Palette.TextSoft)
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 10.dp))
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** One fix from the platform LocationManager (no Play Services dependency); null after 8s. */
@SuppressLint("MissingPermission")
internal suspend fun currentLocation(context: Context): Location? = withContext(Dispatchers.Main) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
        ?: return@withContext null
    lm.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time < 5 * 60_000 }?.let { return@withContext it }
    withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { loc -> if (cont.isActive) cont.resume(loc) }, null)
            }
        }
    }
}
