package com.htb.carplan.shared.data

/**
 * Every Firestore path in one place so both apps read and write the same documents.
 * Group trips are not copied into each user's calendar; the calendar reads
 * users/{uid}/events plus the trips of the groups the user belongs to.
 */
object FirestorePaths {
    const val USERS = "users"
    const val CARS = "cars"
    const val GROUPS = "groups"

    fun user(uid: String) = "$USERS/$uid"
    fun categories(uid: String) = "${user(uid)}/categories"
    fun events(uid: String) = "${user(uid)}/events"

    fun car(carId: String) = "$CARS/$carId"
    fun maintenance(carId: String) = "${car(carId)}/maintenance"

    fun group(groupId: String) = "$GROUPS/$groupId"
    fun trips(groupId: String) = "${group(groupId)}/trips"
    fun trip(groupId: String, tripId: String) = "${trips(groupId)}/$tripId"
    fun votes(groupId: String) = "${group(groupId)}/votes"

    /** [date] is yyyy-MM-dd. */
    fun day(groupId: String, tripId: String, date: String) = "${trip(groupId, tripId)}/days/$date"
    fun stops(groupId: String, tripId: String, date: String) = "${day(groupId, tripId, date)}/stops"
    fun pickups(groupId: String, tripId: String, date: String) = "${day(groupId, tripId, date)}/pickups"
    fun records(groupId: String, tripId: String, date: String) = "${day(groupId, tripId, date)}/records"
    fun comments(groupId: String, tripId: String, date: String, recordId: String) =
        "${records(groupId, tripId, date)}/$recordId/comments"
}

/**
 * Fixed trip used until login, groups and car linking exist (뼈대 한 줄 연결 test).
 * Remove once 4장 그룹 and 7장 차 연결 are built.
 */
object DevTrip {
    const val GROUP_ID = "dev-group"
    const val TRIP_ID = "dev-trip"
}
