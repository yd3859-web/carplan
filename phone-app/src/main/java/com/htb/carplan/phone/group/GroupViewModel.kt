package com.htb.carplan.phone.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htb.carplan.shared.data.CalendarEvent
import com.htb.carplan.shared.data.CalendarRepository
import com.htb.carplan.shared.data.GroupRepository
import com.htb.carplan.shared.data.Session
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 4장 그룹·초대. My groups, one selected group with its members and trips, friends (people
 * from any shared group + ones added by code), and the join/leave/invite actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModel : ViewModel() {
    private val sessions = SessionRepository()
    private val calendar = CalendarRepository()
    private val groups = GroupRepository()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val me: StateFlow<User?> = _session.filterNotNull()
        .flatMapLatest { calendar.observeUser(it.uid) }
        .catch { _message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Shared groups first, the solo group last. */
    val myGroups: StateFlow<List<Group>> = _session.filterNotNull()
        .flatMapLatest { calendar.observeGroups(it.uid) }
        .map { list -> list.sortedWith(compareBy({ it.solo }, { it.name })) }
        .catch { _message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Group?> = _selectedId
        .flatMapLatest { id -> id?.let { groups.observeGroup(it) } ?: flowOf(null) }
        .catch { _message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Trips of the selected group, newest first (지난 여행 + 다가오는 여행). */
    val trips: StateFlow<List<CalendarEvent>> = _selectedId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else calendar.observeEvents(listOf(id), "2000-01-01", "2100-12-31") }
        .map { list -> list.filter { it.event.categoryId == BuiltInCategory.TRIP.id && !it.event.deleted }.sortedByDescending { it.event.startDate } }
        .catch { _message.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** uid → name for everyone we have seen (members, friends). */
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Friends (Q10): people from any of my shared groups plus ones added by code; never me. */
    val friends: StateFlow<List<String>> = combine(
        myGroups,
        _session.filterNotNull().flatMapLatest { s -> groups.observeFriends(s.uid).catch { emit(emptyList()) } },
        _session.filterNotNull(),
    ) { gs, fs, s -> (gs.filterNot { it.solo }.flatMap { it.memberIds } + fs.map { it.uid }).distinct().filterNot { it == s.uid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { sessions.ensureSession() }.onSuccess { _session.value = it }.onFailure { _message.value = it.message }
        }
        viewModelScope.launch { myGroups.collect { gs -> gs.flatMap { it.memberIds }.distinct().forEach(::loadName) } }
        viewModelScope.launch { friends.collect { it.forEach(::loadName) } }
    }

    fun nameOf(uid: String): String = when {
        uid == _session.value?.uid -> "나"
        else -> _names.value[uid]?.takeIf { it.isNotBlank() } ?: "일행"
    }

    private fun loadName(uid: String) {
        if (_names.value.containsKey(uid)) return
        _names.value = _names.value + (uid to "")
        viewModelScope.launch {
            calendar.observeUser(uid).catch { }.collect { u -> _names.value = _names.value + (uid to u?.name.orEmpty()) }
        }
    }

    fun open(groupId: String?) { _selectedId.value = groupId }

    fun createGroup(name: String, onCreated: (String) -> Unit = {}) = act {
        val s = _session.value ?: return@act
        val id = groups.createGroup(name.ifBlank { "새 그룹" }, s.uid)
        onCreated(id)
    }

    /** Join by code (typed, from a link, or from the car's QR). */
    fun joinByCode(code: String, onJoined: (String) -> Unit = {}) = act {
        val s = _session.value ?: return@act
        val id = groups.joinByCode(code, s.uid)
        if (id == null) _message.value = "그 코드의 그룹을 찾지 못했어요" else onJoined(id)
    }

    suspend fun groupForCode(code: String): Group? = runCatching { groups.findByCode(code) }.getOrNull()

    fun leave(groupId: String) = act {
        val s = _session.value ?: return@act
        groups.removeMember(groupId, s.uid)
        if (_selectedId.value == groupId) _selectedId.value = null
    }

    fun removeMember(groupId: String, uid: String) = act { groups.removeMember(groupId, uid) }

    fun inviteFriend(groupId: String, uid: String) = act { groups.invite(groupId, uid) }

    fun addFriendByCode(code: String) = act {
        val s = _session.value ?: return@act
        val uid = groups.addFriendByCode(code, s.uid)
        _message.value = if (uid == null) "그 코드의 사람을 찾지 못했어요" else "친구로 추가했어요"
    }

    fun rename(groupId: String, name: String) = act { groups.rename(groupId, name) }

    fun clearMessage() { _message.value = null }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure { _message.value = it.message } }
    }
}
