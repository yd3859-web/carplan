package com.htb.carplan.phone.vote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htb.carplan.shared.data.DartRepository
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.data.Regions
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.data.VoteRepository
import com.htb.carplan.shared.model.Dart
import com.htb.carplan.shared.model.DartScope
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Vote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 5장 투표 + 6장 다트 for one group (and, optionally, the trip day they belong to). */
class VoteDartViewModel(private val groupId: String, private val eventId: String?, private val date: String?) : ViewModel() {
    private val votes = VoteRepository()
    private val darts = DartRepository()
    private val groups = GroupRepository()
    private val trips = TripRepository()
    private val uid = MutableStateFlow<String?>(null)
    val me: StateFlow<String?> = uid.asStateFlow()
    val message = MutableStateFlow<String?>(null)

    val group: StateFlow<Group?> = groups.observeGroup(groupId).catch { emit(null) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val list: StateFlow<List<Vote>> = votes.observe(groupId).catch { message.value = it.message; emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dart: StateFlow<Dart?> = darts.observeLatest(groupId).catch { emit(null) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { viewModelScope.launch { runCatching { SessionRepository().ensureSession() }.onSuccess { uid.value = it.uid } } }

    fun create(question: String, options: List<String>, places: List<Place?>) = act {
        votes.create(groupId, question.ifBlank { "점심 뭐 먹지?" }, options, places, eventId, date, uid.value.orEmpty())
    }

    fun cast(voteId: String, option: Int) = act {
        val members = group.value?.memberIds?.size ?: 1
        votes.cast(groupId, voteId, uid.value ?: return@act, option, members)
    }

    fun close(voteId: String) = act { votes.close(groupId, voteId) }

    /** 5장 4: the winning place becomes a stop of the trip day. */
    fun addWinnerAsStop(place: Place) = act {
        val e = eventId ?: return@act; val d = date ?: return@act
        trips.addStop(groupId, e, d, place, null, "투표로 정함", uid.value.orEmpty())
        message.value = "${place.name}을(를) 칸으로 넣었어요"
    }

    /** 6장: one throw. NEAR needs the phone's position; REGION a 시·도 name. */
    fun throwDart(context: Context, scope: DartScope, centerLat: Double?, centerLng: Double?, region: String?) = act {
        loadRegionsOnce(context)
        val current = dart.value
        val sameRoom = current != null && current.scope == scope && current.region == region && current.eventId == eventId
        val pick = Regions.pick(scope, centerLat, centerLng, region) ?: run { message.value = "그 범위엔 동네가 없어요"; return@act }
        darts.throwDart(groupId, if (sameRoom) current!!.id else null, scope, centerLat, centerLng, region, pick, if (sameRoom) current!!.throwCount + 1 else 1, uid.value.orEmpty(), eventId, date)
    }

    fun addDartAsStop(d: Dart) = addWinnerAsStop(Place(name = d.resultName, lat = d.resultLat, lng = d.resultLng))

    private var regionsLoaded = false
    private fun loadRegionsOnce(context: Context) {
        if (regionsLoaded) return
        regionsLoaded = true
        runCatching { context.assets.open("regions.csv").bufferedReader().readLines() }.onSuccess { Regions.load(it) }
    }

    private fun act(block: suspend () -> Unit) { viewModelScope.launch { runCatching { block() }.onFailure { message.value = it.message } } }
}
