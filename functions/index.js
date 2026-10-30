// carplan 17장 — 누구에게 언제 (작동시나리오 17장 표). Blaze 요금제에서 `firebase deploy --only functions`.
// 푸시는 users/{uid}.fcmTokens[] 로 보내고, 보낸 사람 본인은 뺀다.
const { onDocumentWritten, onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
setGlobalOptions({ region: "asia-northeast3", maxInstances: 5 });
const db = admin.firestore();

/** 그룹 멤버(제외 uid 빼고)의 모든 토큰. */
async function groupTokens(groupId, excludeUid) {
  const group = await db.doc(`groups/${groupId}`).get();
  const members = (group.get("memberIds") || []).filter((u) => u !== excludeUid);
  return userTokens(members);
}

async function userTokens(uids) {
  if (!uids.length) return [];
  const snaps = await db.getAll(...uids.map((u) => db.doc(`users/${u}`)));
  return snaps.flatMap((s) => s.get("fcmTokens") || []);
}

async function nameOf(uid) {
  if (!uid) return "일행";
  const u = await db.doc(`users/${uid}`).get();
  return u.get("name") || "일행";
}

/** 보내고, 죽은 토큰은 지운다. data는 앱이 열 화면(groupId·eventId·date). */
async function push(tokens, title, body, data = {}) {
  const unique = [...new Set(tokens)];
  if (!unique.length) return;
  const res = await admin.messaging().sendEachForMulticast({
    tokens: unique,
    notification: { title, body },
    data: Object.fromEntries(Object.entries(data).filter(([, v]) => v != null).map(([k, v]) => [k, String(v)])),
    android: { priority: "high", notification: { channelId: "carplan" } },
  });
  const dead = [];
  res.responses.forEach((r, i) => { if (!r.success && /registration-token-not-registered|invalid-argument/.test(r.error?.code || "")) dead.push(unique[i]); });
  if (dead.length) {
    const users = await db.collection("users").where("fcmTokens", "array-contains-any", dead.slice(0, 10)).get();
    await Promise.all(users.docs.map((d) => d.ref.update({ fcmTokens: admin.firestore.FieldValue.arrayRemove(...dead) })));
  }
}

// 일정 칸 뺌 → 그룹 전원 (Q08: 빼기만 푸시)
exports.onStopRemoved = onDocumentWritten("groups/{gid}/events/{eid}/days/{date}/stops/{sid}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!after || !after.deleted || before?.deleted) return;
  const who = await nameOf(after.updatedBy);
  const tokens = await groupTokens(event.params.gid, after.updatedBy);
  await push(tokens, "일정이 바뀌었어요", `${who}님이 ${after.place?.name || "장소"}를 뺐어요`, { groupId: event.params.gid, eventId: event.params.eid, date: event.params.date });
});

// 투표 열림 / 마감 → 그룹 전원
exports.onVoteWritten = onDocumentWritten("groups/{gid}/votes/{vid}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!after) return;
  if (!before) {
    const tokens = await groupTokens(event.params.gid, after.createdBy);
    await push(tokens, "투표가 열렸어요", after.question, { groupId: event.params.gid, eventId: after.eventId, date: after.date });
  } else if (before.status === "OPEN" && after.status === "CLOSED") {
    const winners = (after.winners || []).map((i) => after.options?.[i]).filter(Boolean).join(", ");
    const tokens = await groupTokens(event.params.gid, null);
    await push(tokens, "투표 마감", `${after.question} → ${winners || "결과 없음"}`, { groupId: event.params.gid, eventId: after.eventId, date: after.date });
  }
});

// 도착 · 기록 칸 열림 → 그룹 전원
exports.onRecordCreated = onDocumentCreated("groups/{gid}/events/{eid}/days/{date}/records/{rid}", async (event) => {
  const r = event.data.data();
  const tokens = await groupTokens(event.params.gid, null);
  await push(tokens, `${r.place?.name || "어딘가"} 도착!`, "사진이랑 한 줄 남겨요", { groupId: event.params.gid, eventId: event.params.eid, date: event.params.date });
});

// 픽업: 차가 도착 → 그 동승자
exports.onPickupArrived = onDocumentWritten("groups/{gid}/events/{eid}/days/{date}/pickups/{uid}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!after || !after.arrivedAt || before?.arrivedAt) return;
  const tokens = await userTokens([event.params.uid]);
  await push(tokens, "차가 도착했어요", `${after.place?.name || "픽업 장소"}에서 기다려요. 타면 '탔어요'를 눌러 주세요`, { groupId: event.params.gid, eventId: event.params.eid, date: event.params.date });
});

// 차량 관리 시기 → 차 주인 (매일 09:00 KST, 7일 안 예정)
exports.maintenanceDue = onSchedule({ schedule: "0 9 * * *", timeZone: "Asia/Seoul" }, async () => {
  const today = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  const until = new Date(Date.now() + (9 + 7 * 24) * 3600 * 1000).toISOString().slice(0, 10);
  const dues = await db.collectionGroup("events").where("categoryId", "==", "car").where("maintenance.auto", "==", true)
    .where("startDate", ">=", today).where("startDate", "<=", until).get();
  for (const d of dues.docs) {
    const groupId = d.ref.parent.parent.id;
    const group = await db.doc(`groups/${groupId}`).get();
    if (!group.get("solo")) continue;
    const tokens = await userTokens(group.get("memberIds") || []);
    const days = Math.round((Date.parse(d.get("startDate")) - Date.parse(today)) / 86400000);
    await push(tokens, "차량 관리", `${d.get("title")} ${days <= 0 ? "오늘" : `${days}일 남음`}`, { groupId });
  }
});
