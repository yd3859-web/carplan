package com.htb.carplan.shared.data

/**
 * Every Firestore path in one place so both apps read and write the same documents.
 * The calendar is "the events of every group I belong to"; nothing is copied per user.
 */
object FirestorePaths {
    const val USERS = "users"
    const val CARS = "cars"
    const val GROUPS = "groups"
    const val EVENTS = "events"

    fun user(uid: String) = "$USERS/$uid"
    fun customCategories(uid: String) = "${user(uid)}/categories"

    fun car(carId: String) = "$CARS/$carId"

    fun group(groupId: String) = "$GROUPS/$groupId"
    fun events(groupId: String) = "${group(groupId)}/$EVENTS"
    fun event(groupId: String, eventId: String) = "${events(groupId)}/$eventId"
    fun votes(groupId: String) = "${group(groupId)}/votes"

    /** One day of a trip event. [date] is yyyy-MM-dd. */
    fun day(groupId: String, eventId: String, date: String) = "${event(groupId, eventId)}/days/$date"
    fun stops(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/stops"
    fun pickups(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/pickups"
    fun records(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/records"
    fun comments(groupId: String, eventId: String, date: String, recordId: String) =
        "${records(groupId, eventId, date)}/$recordId/comments"
}

