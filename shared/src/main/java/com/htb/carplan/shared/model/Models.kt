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
    /** 6-character code friends type to add me (Q10). */
    val friendCode: String = "",
    /** 1장: "google" / "kakao" once the anonymous account was linked; null while anonymous. */
    val provider: String? = null,
)

/** users/{uid}/friends/{friendUid} — added by code; people from shared groups show up without this (Q10). */
data class Friend(
    @DocumentId val uid: String = "",
    @ServerTimestamp val addedAt: Timestamp? = null,
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
    /** true = electric (charging stations), false = fuel (gas stations), null = unknown (12장). */
    val isEv: Boolean? = null,
    /** Odometer reading in km (15장), when the car exposes it. */
    val odometerKm: Double? = null,
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
    /** 15장 Q42: per-item overrides of the default service intervals, item name → {km, days}. */
    val intervals: Map<String, Map<String, Long>> = emptyMap(),
    /** 12장: "주유소 들를까요?" when the tank/battery is at or under this (%), set in the phone's 설정 tab. */
    val fuelAlertPercent: Int = 20,
)

// ---------- Groups & calendar events ----------

/** groups/{gid} — a solo group or a shared one. [ownerUid] is the 그룹장 (Q11): can remove members. */
data class Group(
    @DocumentId val id: String = "",
    val name: String = "",
    val memberIds: List<String> = emptyList(),
    val ownerUid: String = "",
    /** 6-character code for joining by code, link or QR (4장·7장). */
    val inviteCode: String = "",
    val solo: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
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

/**
 * Extra fields of a 차량 관리 event. A record (past, [auto] false unless the car made it) or the
 * next due date (future, event id `due-{item}`, [auto] true, rewritten after every record).
 */
data class Maintenance(
    val item: MaintenanceItem = MaintenanceItem.FUEL,
    val carId: String = "",
    val odometerKm: Double? = null,
    val auto: Boolean = false,
    val amountWon: Long? = null,
    val liters: Double? = null,
    /** For a due event: the km reading it is due at, when the rule is km-based. */
    val dueKm: Double? = null,
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
    /** The [version] the car's navigation route reflects (10장). Phones show "경로 바뀜" when it catches up. */
    val routeSyncedVersion: Long = 0,
    /** The driver answered "그대로" to the last change (Q23): phones show "운전자가 보류함". */
    val holdByDriver: Boolean = false,
    /** "HH:mm" the navigation expects to reach the next stop; written by the car after routing. */
    val etaNext: String? = null,
    /** 11장 Q27: the driver agreed to share the car's position while picking people up. */
    val pickupSharing: Boolean = false,
    /** 12장: the stop the car is about 5 minutes from; Functions push "주차장 찾아봐 주세요" to the passengers once per stop. */
    val parkingHintStopId: String? = null,
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
    /** When the car reached this pickup; sharing for this person ends 10 min later (11장 7). */
    val arrivedAt: Timestamp? = null,
    val name: String = "",
)

/** .../days/{date}/records/{id} — created when the car parks (13장). */
/**
 * .../days/{date}/records/{id} — one stop the car actually made (13장): created by the car at
 * P + driver door (or P 5 min), linked to a planned stop when within 300 m of it, otherwise unplanned.
 */
data class Record(
    @DocumentId val id: String = "",
    val place: Place = Place(),
    val stopId: String? = null,
    val unplanned: Boolean = false,
    val arrivedAt: Timestamp? = null,
    val leftAt: Timestamp? = null,
    /** Up to 10 (Q40), long edge 1600 px. */
    val photoUrls: List<String> = emptyList(),
    /** 짧은 글 (14장); shown under the place in the timeline and the export image. */
    val note: String = "",
    /** The car id that created it, or a user uid for a manual record. */
    val createdBy: String = "",
)

/**
 * A short note by a member. Lives in two places:
 * `.../days/{date}/comments/{id}` with [stopId] = the planned stop it hangs under (3장 말풍선, written
 * while planning or on the road), and `.../records/{id}/comments/{id}` for 14장 photo records.
 */
data class Comment(
    @DocumentId val id: String = "",
    val uid: String = "",
    val text: String = "",
    /** The stop this comment belongs to (day comments only). */
    val stopId: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
)

enum class VoteStatus { OPEN, CLOSED }

/** groups/{gid}/votes/{id} — 5장. [places] has an entry per option (null when the option is not a place). */
data class Vote(
    @DocumentId val id: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val places: List<Place?> = emptyList(),
    /** uid -> option index */
    val ballots: Map<String, Int> = emptyMap(),
    val status: VoteStatus = VoteStatus.OPEN,
    /** Indices that won when closed (two on a tie, Q12). */
    val winners: List<Int> = emptyList(),
    /** Trip this vote belongs to, so the winning place can become a stop. */
    val eventId: String? = null,
    val date: String? = null,
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
)

enum class DartScope { NATIONAL, NEAR, REGION }

/** groups/{gid}/darts/{id} — 6장 다트 방: everyone in the group watches the same throw. */
data class Dart(
    @DocumentId val id: String = "",
    val scope: DartScope = DartScope.NATIONAL,
    /** For NEAR: the centre; for REGION: the 시·도 name. */
    val centerLat: Double? = null,
    val centerLng: Double? = null,
    val region: String? = null,
    val resultName: String = "",
    val resultLat: Double = 0.0,
    val resultLng: Double = 0.0,
    val throwCount: Int = 0,
    val thrownBy: String = "",
    val eventId: String? = null,
    val date: String? = null,
    @ServerTimestamp val thrownAt: Timestamp? = null,
)
