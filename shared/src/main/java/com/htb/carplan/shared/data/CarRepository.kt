package com.htb.carplan.shared.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.htb.carplan.shared.model.Car
import com.htb.carplan.shared.model.CarState
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
                "state.lat" to state.lat,
                "state.lng" to state.lng,
                "state.updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    private fun newCode() = (1..6).joinToString("") { Random.nextInt(10).toString() }
}
