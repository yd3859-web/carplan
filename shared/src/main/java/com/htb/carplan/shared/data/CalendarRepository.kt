package com.htb.carplan.shared.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.htb.carplan.shared.model.CustomCategory
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Group
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** An event together with the group it lives in. */
data class CalendarEvent(val groupId: String, val event: Event)

/** The calendar = events of every group the user belongs to (18장). */
class CalendarRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    fun observeGroups(uid: String): Flow<List<Group>> =
        db.collection(FirestorePaths.GROUPS).whereArrayContains("memberIds", uid).observe(Group::class.java)

    /** Events overlapping [from]..[to] (yyyy-MM-dd, inclusive) across [groupIds]. */
    fun observeEvents(groupIds: List<String>, from: String, to: String): Flow<List<CalendarEvent>> {
        if (groupIds.isEmpty()) return flowOf(emptyList())
        val perGroup = groupIds.map { gid ->
            db.collection(FirestorePaths.events(gid))
                .whereLessThanOrEqualTo("startDate", to)
                .observe(Event::class.java)
                .mapEvents(gid, from)
        }
        return combine(perGroup) { lists -> lists.flatMap { it } }
    }

    suspend fun addEvent(groupId: String, event: Event) {
        db.collection(FirestorePaths.events(groupId)).add(event).await()
    }

    fun observeCustomCategories(uid: String): Flow<List<CustomCategory>> =
        db.collection(FirestorePaths.customCategories(uid)).observe(CustomCategory::class.java)

    suspend fun addCustomCategory(uid: String, name: String, color: Long): String =
        db.collection(FirestorePaths.customCategories(uid)).add(CustomCategory(name = name, color = color)).await().id

    private fun Flow<List<Event>>.mapEvents(groupId: String, from: String): Flow<List<CalendarEvent>> =
        map { events -> events.filter { !it.deleted && it.endDate >= from }.map { CalendarEvent(groupId, it) } }

    private fun <T : Any> Query.observe(type: Class<T>): Flow<List<T>> = callbackFlow {
        val reg = addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toObjects(type).orEmpty())
        }
        awaitClose { reg.remove() }
    }
}
