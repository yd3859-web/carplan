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
    fun friends(uid: String) = "${user(uid)}/friends"

    fun car(carId: String) = "$CARS/$carId"

    fun group(groupId: String) = "$GROUPS/$groupId"
    fun events(groupId: String) = "${group(groupId)}/$EVENTS"
    fun event(groupId: String, eventId: String) = "${events(groupId)}/$eventId"
    fun votes(groupId: String) = "${group(groupId)}/votes"
    fun darts(groupId: String) = "${group(groupId)}/darts"
    /** 여행별 채팅방 (A안 2026-11-03): one room per group. */
    fun chat(groupId: String) = "${group(groupId)}/chat"
    /** Q58: the group's memo per Kakao place (또 갈래요 · 가 봄). */
    fun places(groupId: String) = "${group(groupId)}/places"

    /** One day of a trip event. [date] is yyyy-MM-dd. */
    fun day(groupId: String, eventId: String, date: String) = "${event(groupId, eventId)}/days/$date"
    fun stops(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/stops"
    fun pickups(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/pickups"
    /** Member comments hanging under the day's stops (3장 말풍선). */
    fun dayComments(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/comments"
    fun records(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/records"
    /** A1: the roads the car actually drove that day, one document per drive (D → P). */
    fun tracks(groupId: String, eventId: String, date: String) = "${day(groupId, eventId, date)}/tracks"
    /** The next-due event of one maintenance item in the owner's solo group (15장). */
    fun dueEventId(item: String) = "due-$item"
    /** Auto event in the solo group that holds a non-trip day's records (Q37 "일상 기록"). */
    fun dailyLogEventId(date: String) = "log-$date"
    /** Storage path of one record photo. */
    fun recordPhoto(groupId: String, eventId: String, date: String, recordId: String, fileName: String) =
        "records/$groupId/$eventId/$date/$recordId/$fileName"
    fun comments(groupId: String, eventId: String, date: String, recordId: String) =
        "${records(groupId, eventId, date)}/$recordId/comments"
}

