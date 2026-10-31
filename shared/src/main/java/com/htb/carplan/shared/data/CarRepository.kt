package com.htb.carplan.shared.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.CarState
import com.htb.carplan.shared.model.MaintenanceItem
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

/**
 * 7장 차 연결. The car app signs in on its own and owns cars/{its uid}; the owner's
 * phone types the car's short code to become the host (Q17).
 */
class CarRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /** Creates cars/{carUid} with a 6-digit link code the first time. */
    suspend fun ensureCar(carUid: String): Car {
        val ref = db.document(FirestorePaths.car(carUid))
        ref.get().await().toObject(Car::class.java)?.let { return it }
        val car = Car(linkCode = newCode())
        ref.set(car).await()
        return car.copy(id = carUid)
    }

    /** Phone side: links the car showing [code] to [ownerUid]. Returns the car id, or null if no car matches. */
    suspend fun linkByCode(code: String, ownerUid: String): String? {
        val car = db.collection(FirestorePaths.CARS).whereEqualTo("linkCode", code.trim()).limit(1)
            .get().await().documents.firstOrNull() ?: return null
        db.runBatch { batch ->
            batch.update(car.reference, "ownerUid", ownerUid)
            batch.set(db.document(FirestorePaths.user(ownerUid)), mapOf("carId" to car.id), SetOptions.merge())
        }.await()
        return car.id
    }

    suspend fun updateState(carId: String, state: CarState) {
        db.document(FirestorePaths.car(carId)).update(
            mapOf(
                "state.gear" to state.gear.name,
                "state.drivingRestricted" to state.drivingRestricted,
                "state.fuelPercent" to state.fuelPercent,
                "state.rangeKm" to state.rangeKm,
                "state.isEv" to state.isEv,
                "state.odometerKm" to state.odometerKm,
                // Position is written only by updateLocation (11장 P1): the monitor never has one, and writing null
                // here wiped the shared position every time fuel or gear changed.
                "state.updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /** 11장: the car's position while pickup sharing is on; null clears it (no history is kept). */
    suspend fun updateLocation(carId: String, lat: Double?, lng: Double?) {
        db.document(FirestorePaths.car(carId)).update(mapOf("state.lat" to lat, "state.lng" to lng, "state.updatedAt" to FieldValue.serverTimestamp())).await()
    }

    /** Which trip this car is driving today (9장); null clears it. */
    suspend fun setActiveTrip(carId: String, groupId: String?, eventId: String?) {
        db.document(FirestorePaths.car(carId)).update(mapOf("activeGroupId" to groupId, "activeEventId" to eventId)).await()
    }

    /** 15장 Q42: change one item's interval (null = back to default). */
    suspend fun setInterval(carId: String, item: MaintenanceItem, km: Long?, days: Long?) {
        val value: Any = if (km == null && days == null) FieldValue.delete() else mapOf("km" to (km ?: 0L), "days" to (days ?: 0L))
        db.document(FirestorePaths.car(carId)).update("intervals.${item.name}", value).await()
    }

    /** 12장 설정: the owner's low-fuel threshold (%). */
    suspend fun setFuelAlert(carId: String, percent: Int) {
        db.document(FirestorePaths.car(carId)).set(mapOf("fuelAlertPercent" to percent), com.google.firebase.firestore.SetOptions.merge()).await()
    }

    private fun newCode() = (1..6).joinToString("") { Random.nextInt(10).toString() }
}
