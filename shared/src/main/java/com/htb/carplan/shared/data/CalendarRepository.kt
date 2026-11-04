package com.htb.carplan.shared.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.CustomCategory
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Group
import com.htb.carplan.shared.model.User
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
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
                // A group that appeared a moment ago (just made by an invite, or just joined) can be refused while the
                // server catches up: try again a few times, then leave only that group empty — never stop the calendar.
                .retryWhen { cause, attempt ->
                    val denied = (cause as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    if (denied && attempt < 3) { delay(1_000L * (attempt + 1)); true } else false
                }
                .catch { emit(emptyList()) }
                .mapEvents(gid, from)
        }
        return combine(perGroup) { lists -> lists.flatMap { it } }
    }

    /** Adds an event and returns its id (a trip then opens its first day in 3장). */
    suspend fun addEvent(groupId: String, event: Event): String =
        db.collection(FirestorePaths.events(groupId)).add(event).await().id

    /**
     * 일정 고치기 (정원 2026-11-04: 캘린더에 등록한 일정을 고칠 방법이 없었음): title, category, dates, time and place of
     * an existing event. Fields not named stay as they are.
     */
    suspend fun updateEvent(groupId: String, eventId: String, title: String, categoryId: String, startDate: String, endDate: String, allDay: Boolean, startTime: String?, place: com.htb.carplan.shared.model.Place?) {
        db.document(FirestorePaths.event(groupId, eventId)).update(mapOf(
            "title" to title.trim(), "categoryId" to categoryId, "startDate" to startDate, "endDate" to maxOf(startDate, endDate),
            "allDay" to allDay, "startTime" to if (allDay) null else startTime, "place" to place,
        )).await()
    }

    /**
     * An ordinary event becomes a trip (정원 2026-11-04: "바다여행"을 다른 일정으로 등록해 경유지를 못 넣었음): category 여행,
     * all-day, and its place — if it had one — becomes the first stop of the first day, so more places can follow.
     */
    suspend fun convertToTrip(groupId: String, event: com.htb.carplan.shared.model.Event, byUid: String) {
        db.document(FirestorePaths.event(groupId, event.id)).update(mapOf(
            "categoryId" to com.htb.carplan.shared.model.BuiltInCategory.TRIP.id, "allDay" to true, "startTime" to null,
            "endDate" to maxOf(event.startDate, event.endDate.ifBlank { event.startDate }),
        )).await()
        event.place?.takeIf { it.name.isNotBlank() || it.lat != 0.0 }?.let { p ->
            TripRepository(db).addStop(groupId, event.id, event.startDate, p, event.startTime, "", byUid)
        }
    }

    /** Soft delete (the calendar hides `deleted` events; nothing is erased). */
    suspend fun setEventDeleted(groupId: String, eventId: String, deleted: Boolean) {
        db.document(FirestorePaths.event(groupId, eventId)).update("deleted", deleted).await()
    }

    fun observeEvent(groupId: String, eventId: String): Flow<Event?> =
        db.document(FirestorePaths.event(groupId, eventId)).observeDoc(Event::class.java)

    fun observeCustomCategories(uid: String): Flow<List<CustomCategory>> =
        db.collection(FirestorePaths.customCategories(uid)).observe(CustomCategory::class.java)

    suspend fun addCustomCategory(uid: String, name: String, color: Long): String =
        db.collection(FirestorePaths.customCategories(uid)).add(CustomCategory(name = name, color = color)).await().id

    fun observeUser(uid: String): Flow<User?> = db.document(FirestorePaths.user(uid)).observeDoc(User::class.java)

    fun observeCar(carId: String): Flow<Car?> = db.document(FirestorePaths.car(carId)).observeDoc(Car::class.java)

    private fun <T : Any> com.google.firebase.firestore.DocumentReference.observeDoc(type: Class<T>): Flow<T?> = callbackFlow {
        val reg = addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toObject(type))
        }
        awaitClose { reg.remove() }
    }

    private fun Flow<List<Event>>.mapEvents(groupId: String, from: String): Flow<List<CalendarEvent>> =
        // Repeating events (16장) stay in the window no matter how long ago they started.
        map { events -> events.filter { !it.deleted && (it.endDate >= from || it.repeat != null) }.map { CalendarEvent(groupId, it) } }

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
