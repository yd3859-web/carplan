package com.htb.carplan.shared.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

// Firestore data model shared by car-app and phone-app (작동시나리오 18장).
// Calendar-centric: every calendar item is one Event inside a group; a solo user
// has an automatic one-person group (Q01). Categories only change what extra
// fields an Event uses. Every field has a default so Firestore can deserialize.
// Change this file only together with 18장.

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
    /** The automatic one-person group created at sign-up (Q01). */
    val soloGroupId: String = "",
    val carId: String? = null,
)

/** Built-in categories; users can add their own (users/{uid}/categories). */
enum class BuiltInCategory(val id: String, val label: String) {
    TRIP("trip", "여행"),
    CAR("car", "차량 관리"),
    OTHER("other", "다른 일정"),
}

/** users/{uid}/categories/{id} — a category the user added (출퇴근, 여가…). */
data class CustomCategory(
    @DocumentId val id: String = "",
    val name: String = "",
    val color: Long = 0xFF5B8DEF,
)

enum class Gear { P, R, N, D, UNKNOWN }

/** Live state the car app uploads. */
data class CarState(
    val gear: Gear = Gear.UNKNOWN,
    val drivingRestricted: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    /** 0–100, from android.car FUEL_LEVEL or EV battery. */
    val fuelPercent: Double? = null,
    val rangeKm: Double? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

/** cars/{carId} */
data class Car(
    @DocumentId val id: String = "",
    val ownerUid: String? = null,
    /** Short code shown on the car screen to link the owner's account (7장). */
    val linkCode: String = "",
    /** The trip event this car is driving today. */
    val activeGroupId: String? = null,
    val activeEventId: String? = null,
    val state: CarState = CarState(),
)

// ---------- Groups & calendar events ----------

/** groups/{gid} — a solo group or a shared one. */
data class Group(
    @DocumentId val id: String = "",
    val name: String = "",
    val memberIds: List<String> = emptyList(),
    val ownerUid: String = "",
    val inviteCode: String = "",
    val solo: Boolean = false,
)

enum class MaintenanceItem(val label: String) {
    FUEL("주유"),
    INSPECTION("차량 점검"),
    TIRE_PRESSURE("공기압"),
    ENGINE_OIL("엔진오일"),
    TIRE_CHANGE("타이어 교체"),
    CAR_WASH("세차"),
    INSURANCE("보험 갱신"),
}

/** Extra fields of a 차량 관리 event. */
data class Maintenance(
    val item: MaintenanceItem = MaintenanceItem.FUEL,
    val carId: String = "",
    val odometerKm: Double? = null,
    val auto: Boolean = false,
    val amountWon: Long? = null,
    val liters: Double? = null,
)

/**
 * groups/{gid}/events/{eid} — every calendar item.
 * [startDate]/[endDate] are yyyy-MM-dd so a multi-day trip shows as one span (Q04).
 */
data class Event(
    @DocumentId val id: String = "",
    /** A [BuiltInCategory.id] or a [CustomCategory.id]. */
    val categoryId: String = BuiltInCategory.OTHER.id,
    val title: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val allDay: Boolean = true,
    /** "HH:mm" when not all-day. */
    val startTime: String? = null,
    val endTime: String? = null,
    val place: Place? = null,
    /** Repeat rule for commutes, e.g. "WEEKLY:MO,TU,WE,TH,FR". */
    val repeat: String? = null,
    /** Minutes before start to remind; null = no reminder. */
    val reminderMinutes: Int? = null,
    val memo: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val createdBy: String = "",
    val deleted: Boolean = false,
    // 여행 only
    val carId: String? = null,
    val driverUid: String? = null,
    // 차량 관리 only
    val maintenance: Maintenance? = null,
)

data class ChecklistItem(
    val text: String = "",
    val done: Boolean = false,
)

enum class DayStatus { READY, DRIVING, DONE }

/** .../events/{eid}/days/{yyyy-MM-dd} — one day of a trip. */
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
    /** Planned arrival time "HH:mm" (Q06: arrival only). */
    val time: String? = null,
    val memo: String = "",
    val createdBy: String = "",
    /** Who changed this slot last and when (Q07: last write wins, shown as "○○님이 방금 수정"). */
    val updatedBy: String? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
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
