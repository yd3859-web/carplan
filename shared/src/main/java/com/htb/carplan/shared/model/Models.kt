package com.htb.carplan.shared.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

// Firestore data model shared by car-app and phone-app.
// Mirrors 작동시나리오 18장. Every field has a default so Firestore can deserialize.
// Change this file only through a PR both apps agree on.

/** A place picked from Kakao Local search. */
data class Place(
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val address: String = "",
    val kakaoId: String? = null,
)

// ---------- People & cars ----------

/** users/{uid} */
data class User(
    @DocumentId val uid: String = "",
    val name: String = "",
    val photoUrl: String? = null,
    val fcmTokens: List<String> = emptyList(),
    val carId: String? = null,
    /** The automatic one-person group (Q01). */
    val soloGroupId: String? = null,
)

/** users/{uid}/categories/{id} */
data class Category(
    @DocumentId val id: String = "",
    val name: String = "",
    val color: Long = 0xFF5B8DEF,
    val isDefault: Boolean = false,
)

/** users/{uid}/events/{id} — a plain (non-trip) event. */
data class Event(
    @DocumentId val id: String = "",
    val categoryId: String = "",
    val title: String = "",
    val startAt: Timestamp? = null,
    val endAt: Timestamp? = null,
    val place: Place? = null,
    /** RRULE-like string for commute repeats, e.g. "WEEKLY:MO,TU,WE,TH,FR". */
    val repeat: String? = null,
)

enum class Gear { P, R, N, D, UNKNOWN }

/** Live state the car app uploads. */
data class CarState(
    val gear: Gear = Gear.UNKNOWN,
    val drivingRestricted: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

/** cars/{carId} */
data class Car(
    @DocumentId val id: String = "",
    val ownerUid: String? = null,
    /** Short code shown on the car screen to link the owner's account. */
    val linkCode: String = "",
    /** groups/{groupId}/trips/{tripId} currently using this car. */
    val activeGroupId: String? = null,
    val activeTripId: String? = null,
    val state: CarState = CarState(),
)

enum class MaintenanceItem { FUEL, INSPECTION, TIRE_PRESSURE, ENGINE_OIL, TIRE_CHANGE, CAR_WASH, INSURANCE }

/** cars/{carId}/maintenance/{id} */
data class MaintenanceRecord(
    @DocumentId val id: String = "",
    val item: MaintenanceItem = MaintenanceItem.FUEL,
    val date: Timestamp? = null,
    val odometerKm: Double? = null,
    val auto: Boolean = false,
    val amountWon: Long? = null,
    val liters: Double? = null,
)

// ---------- Groups & trips ----------

/** groups/{gid} */
data class Group(
    @DocumentId val id: String = "",
    val name: String = "",
    val memberIds: List<String> = emptyList(),
    val ownerUid: String = "",
    val inviteCode: String = "",
    val solo: Boolean = false,
)

/** groups/{gid}/trips/{tripId} — may span several days (Q04: routes are per day). */
data class Trip(
    @DocumentId val id: String = "",
    val name: String = "",
    /** yyyy-MM-dd, ascending. */
    val dates: List<String> = emptyList(),
    val carId: String? = null,
    val driverUid: String? = null,
)

enum class DayStatus { READY, DRIVING, DONE }

/** .../trips/{tripId}/days/{yyyy-MM-dd} */
data class TripDay(
    @DocumentId val date: String = "",
    /** Bumped on every stop change so the car can tell what is new. */
    val version: Long = 0,
    val currentStopIndex: Int = 0,
    val status: DayStatus = DayStatus.READY,
    val lastChangedBy: String? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

/** .../days/{date}/stops/{id} — one slot of the day: place + time + memo. */
data class Stop(
    @DocumentId val id: String = "",
    val order: Int = 0,
    val place: Place = Place(),
    /** Planned arrival time "HH:mm" (Q06). */
    val time: String? = null,
    val memo: String = "",
    val createdBy: String = "",
    /** Soft delete: kept for undo and "○○님이 뺐어요" notices. */
    val deleted: Boolean = false,
    val visited: Boolean = false,
)

enum class PickupStatus { WAITING, ON_BOARD, DONE }

/** .../days/{date}/pickups/{uid} */
data class Pickup(
    @DocumentId val uid: String = "",
    val place: Place = Place(),
    val order: Int = 0,
    val status: PickupStatus = PickupStatus.WAITING,
    val etaSeconds: Long? = null,
)

/** .../days/{date}/records/{id} — created when the car parks (13장). */
data class Record(
    @DocumentId val id: String = "",
    val place: Place = Place(),
    val stopId: String? = null,
    val unplanned: Boolean = false,
    val arrivedAt: Timestamp? = null,
    val leftAt: Timestamp? = null,
    val photoUrls: List<String> = emptyList(),
)

/** .../records/{id}/comments/{id} */
data class Comment(
    @DocumentId val id: String = "",
    val uid: String = "",
    val text: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
)

enum class VoteStatus { OPEN, CLOSED }

/** groups/{gid}/votes/{id} */
data class Vote(
    @DocumentId val id: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    /** uid -> option index */
    val ballots: Map<String, Int> = emptyMap(),
    val status: VoteStatus = VoteStatus.OPEN,
)
