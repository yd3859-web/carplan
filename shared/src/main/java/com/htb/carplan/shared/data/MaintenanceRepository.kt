package com.htb.carplan.shared.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.htb.carplan.shared.calendar.MaintenanceRules
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.Event
import com.htb.carplan.shared.model.Maintenance
import com.htb.carplan.shared.model.MaintenanceItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * 15장 차량 관리. Every record is a 차량 관리 event in the owner's solo group; after each record
 * the item's next-due event (`due-{item}`) is rewritten so the calendar and 오늘 헤더 show it.
 */
class MaintenanceRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /** All 차량 관리 events (records and dues) of the solo group, newest first. */
    fun observe(soloGroupId: String): Flow<List<Event>> = callbackFlow {
        val reg = db.collection(FirestorePaths.events(soloGroupId)).whereEqualTo("categoryId", BuiltInCategory.CAR.id)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.toObjects(Event::class.java).orEmpty().filterNot { it.deleted }.sortedByDescending { it.startDate })
            }
        awaitClose { reg.remove() }
    }

    /**
     * Writes one record and the item's next due. [odometerKm] = car reading at the time.
     * Returns the record's event id.
     */
    suspend fun log(
        soloGroupId: String, carId: String, item: MaintenanceItem, date: LocalDate, odometerKm: Double?,
        amountWon: Long? = null, liters: Double? = null, memo: String = "", byUid: String, auto: Boolean = false,
        intervals: Map<String, Map<String, Long>> = emptyMap(), kmPerDay: Double = MaintenanceRules.DEFAULT_KM_PER_DAY,
    ): String {
        val ref = db.collection(FirestorePaths.events(soloGroupId)).document()
        val record = Event(
            categoryId = BuiltInCategory.CAR.id, title = item.label, startDate = date.toString(), endDate = date.toString(), allDay = true,
            memo = memo, createdBy = byUid,
            maintenance = Maintenance(item = item, carId = carId, odometerKm = odometerKm, auto = auto, amountWon = amountWon, liters = liters),
        )
        ref.set(record).await()
        writeDue(soloGroupId, carId, item, date, odometerKm, intervals, kmPerDay, byUid)
        return ref.id
    }

    /** Insurance has no rule: the user types the renewal date directly. */
    suspend fun setDueDate(soloGroupId: String, carId: String, item: MaintenanceItem, date: LocalDate, byUid: String) {
        db.document(FirestorePaths.event(soloGroupId, FirestorePaths.dueEventId(item.name))).set(
            Event(
                categoryId = BuiltInCategory.CAR.id, title = "${item.label} 예정", startDate = date.toString(), endDate = date.toString(), allDay = true,
                createdBy = byUid, maintenance = Maintenance(item = item, carId = carId, auto = true),
            ),
        ).await()
    }

    private suspend fun writeDue(soloGroupId: String, carId: String, item: MaintenanceItem, lastDate: LocalDate, lastKm: Double?, intervals: Map<String, Map<String, Long>>, kmPerDay: Double, byUid: String) {
        val due = MaintenanceRules.nextDue(item, lastDate, lastKm, MaintenanceRules.interval(item, intervals), kmPerDay)
        // No interval (워셔액): done = the car's warning entry on the calendar goes away.
        val d = due?.date ?: run {
            runCatching { db.document(FirestorePaths.event(soloGroupId, FirestorePaths.dueEventId(item.name))).update("deleted", true).await() }
            return
        }
        db.document(FirestorePaths.event(soloGroupId, FirestorePaths.dueEventId(item.name))).set(
            Event(
                categoryId = BuiltInCategory.CAR.id, title = "${item.label} 예정", startDate = d.toString(), endDate = d.toString(), allDay = true,
                createdBy = byUid, maintenance = Maintenance(item = item, carId = carId, auto = true, dueKm = due.dueKm),
                reminderMinutes = 0, // the phone rings at 9:00 on the day (Reminders); Functions push a week before
            ),
            SetOptions.merge(),
        ).await()
    }

    /**
     * The car itself warned (엔진오일·브레이크액·워셔액 부족, 공기압 낮음): the item's due event moves to today with that
     * text, so it is on the calendar and the phone rings. Written once per warning; a record later moves it on again.
     */
    suspend fun warnNow(soloGroupId: String, carId: String, item: MaintenanceItem, text: String, byUid: String) {
        val today = LocalDate.now().toString()
        db.document(FirestorePaths.event(soloGroupId, FirestorePaths.dueEventId(item.name))).set(
            Event(
                categoryId = BuiltInCategory.CAR.id, title = text, startDate = today, endDate = today, allDay = true, createdBy = byUid,
                memo = "차가 알려 줬어요", maintenance = Maintenance(item = item, carId = carId, auto = true), reminderMinutes = 0,
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun updateRecord(soloGroupId: String, eventId: String, amountWon: Long?, liters: Double?, memo: String) {
        db.document(FirestorePaths.event(soloGroupId, eventId)).update(mapOf("maintenance.amountWon" to amountWon, "maintenance.liters" to liters, "memo" to memo)).await()
    }

    suspend fun delete(soloGroupId: String, eventId: String) {
        db.document(FirestorePaths.event(soloGroupId, eventId)).update("deleted", true).await()
    }
}
