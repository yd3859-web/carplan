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
exports.onPickupWritten = onDocumentWritten("groups/{gid}/events/{eid}/days/{date}/pickups/{uid}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!after) return;
  const data = { groupId: event.params.gid, eventId: event.params.eid, date: event.params.date };
  // 11장 6: "곧 도착해요" once, when the car's ETA (written by the car every 30 s) drops under 3 minutes.
  const SOON = 180;
  if (!after.arrivedAt && after.etaSeconds != null && after.etaSeconds <= SOON && !(before?.etaSeconds != null && before.etaSeconds <= SOON)) {
    const tokens = await userTokens([event.params.uid]);
    await push(tokens, "곧 도착해요", `차가 약 ${Math.max(1, Math.round(after.etaSeconds / 60))}분 뒤 ${after.place?.name || "픽업 장소"}에 와요`, data);
  }
  if (after.arrivedAt && !before?.arrivedAt) {
    const tokens = await userTokens([event.params.uid]);
    await push(tokens, "차가 도착했어요", `${after.place?.name || "픽업 장소"}에서 기다려요. 타면 '탔어요'를 눌러 주세요`, data);
  }
});

// 12장: 다음 칸 5분 전 → 운전자 빼고 "주차장 찾아봐 주세요" (차가 days/{date}.parkingHintStopId를 씀)
exports.onParkingHint = onDocumentWritten("groups/{gid}/events/{eid}/days/{date}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  const stopId = after?.parkingHintStopId;
  if (!stopId || before?.parkingHintStopId === stopId) return;
  const { gid, eid, date } = event.params;
  const [ev, stop] = await Promise.all([db.doc(`groups/${gid}/events/${eid}`).get(), db.doc(`groups/${gid}/events/${eid}/days/${date}/stops/${stopId}`).get()]);
  const tokens = await groupTokens(gid, ev.get("driverUid"));
  // kind/stopId: tapping it opens the phone's 주차장 찾기 around that stop, nearest first (정원 2026-11-04).
  await push(tokens, "주차장 찾아봐 주세요", `${stop.get("place")?.name || "다음 장소"} 도착 5분 전이에요 · 눌러서 근처 주차장 보기`, { groupId: gid, eventId: eid, date, kind: "parking", stopId });
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

// 여행별 채팅방 (A안 2026-11-03): 새 말·다트 카드 → 보낸 사람 빼고 그룹 전원. 투표 카드는 onVoteWritten이 이미 알려서 뺀다.
exports.onChatCreated = onDocumentCreated("groups/{gid}/chat/{mid}", async (event) => {
  const m = event.data.data();
  // 사람이 보낸 것만 알림: 말·다트·장소·칸 코멘트. 나머지 카드(투표·칸 변경·픽업·도착·하루 끝)는 원래 푸시가 있거나 조용히.
  if (!m || !["TEXT", "DART", "PLACE", "COMMENT"].includes(m.kind)) return;
  const gid = event.params.gid;
  const group = await db.doc(`groups/${gid}`).get();
  // 누르면 그 여행 화면으로: 날짜가 없으면 여행 첫날
  const eventId = m.eventId || group.get("forEvent") || null;
  let date = m.date || null;
  if (eventId && !date) date = (await db.doc(`groups/${gid}/events/${eventId}`).get()).get("startDate") || null;
  const who = await nameOf(m.uid);
  const title = group.get("name") || "채팅";
  const body = m.kind === "DART" ? `${who}님이 다트 결과를 올렸어요 · ${m.text}`
    : m.kind === "PLACE" ? `${who}: ${(m.lines || [])[0] || "장소"} · ${m.text}`
    : m.kind === "COMMENT" ? `${who} · ${(m.lines || [])[0] || "칸"}: ${m.text}`
    : `${who}: ${m.text}`;
  const tokens = await groupTokens(gid, m.uid);
  await push(tokens, title, body, eventId && date ? { groupId: gid, eventId, date } : { groupId: gid });
});

// ---------- 채팅 자동 카드 (A안 2026-11-03): 앱에서 생긴 일을 그 여행 채팅방에. 같이 가는 그룹(2명 이상)만 ----------
const { FieldValue } = require("firebase-admin/firestore");

async function sharedGroup(gid) {
  const g = await db.doc(`groups/${gid}`).get();
  return (g.get("memberIds") || []).length > 1 ? g : null;
}

/** id가 같은 카드는 덮어씀(merge) — 같은 일이 두 번 와도 카드는 하나. */
async function chatCard(gid, id, card, merge = false) {
  await db.doc(`groups/${gid}/chat/${id}`).set({ ...card, createdAt: FieldValue.serverTimestamp() }, { merge });
}

// 칸 넣음 · 뺌 · 되살림 → 한 사람이 5분 안에 한 것은 카드 한 장으로 묶음
exports.chatOnStopChanged = onDocumentWritten("groups/{gid}/events/{eid}/days/{date}/stops/{sid}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!after) return;
  let what = null;
  if (!before && !after.deleted) what = "넣음";
  else if (before && !before.deleted && after.deleted) what = "뺌";
  else if (before && before.deleted && !after.deleted) what = "되살림";
  if (!what) return;
  // 첫 초대 때 일정이 전용 그룹으로 옮겨지며 칸이 복사됨 → 예전에 만든 칸은 "넣음"이 아님
  if (what === "넣음" && after.updatedAt?.toMillis && Date.now() - after.updatedAt.toMillis() > 2 * 60 * 1000) return;
  const { gid, eid, date } = event.params;
  if (!(await sharedGroup(gid))) return;
  const who = after.updatedBy || after.createdBy || "";
  const bucket = Math.floor(Date.now() / (5 * 60 * 1000));
  await chatCard(gid, `change-${date}-${who}-${bucket}`, {
    uid: who, kind: "CHANGE", text: "", eventId: eid, date,
    lines: FieldValue.arrayUnion(`${after.place?.name || "장소"} ${what}`),
  }, true);
});

// 픽업: 차가 도착 · 탔어요
exports.chatOnPickup = onDocumentWritten("groups/{gid}/events/{eid}/days/{date}/pickups/{uid}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!after) return;
  const { gid, eid, date, uid } = event.params;
  const name = after.name || (await nameOf(uid));
  const place = after.place?.name || "픽업 장소";
  let card = null;
  if (after.arrivedAt && !before?.arrivedAt) card = { id: `pickup-${date}-${uid}-arrived`, text: `차가 ${place}에 도착했어요`, lines: [`${name}님 태우러`] };
  else if (after.status === "ON_BOARD" && before?.status !== "ON_BOARD") card = { id: `pickup-${date}-${uid}-board`, text: `${name}님이 탔어요`, lines: [place] };
  if (!card || !(await sharedGroup(gid))) return;
  await chatCard(gid, card.id, { uid, kind: "PICKUP", text: card.text, lines: card.lines, eventId: eid, date });
});

// 도착해 기록 칸이 열림 → "○○ 도착" [사진·한 줄 남기기]
exports.chatOnArrived = onDocumentCreated("groups/{gid}/events/{eid}/days/{date}/records/{rid}", async (event) => {
  const r = event.data.data();
  const { gid, eid, date, rid } = event.params;
  if (!(await sharedGroup(gid))) return;
  const name = r.place?.name || "어딘가";
  await chatCard(gid, `arrived-${date}-${rid}`, { uid: "", kind: "ARRIVED", text: `${name} 도착`, place: r.place || null, refId: rid, lines: r.unplanned ? ["계획 밖에 들른 곳"] : [], eventId: eid, date });
});

// 하루 끝(그날 상태 DONE) → "오늘 n곳 다녀왔어요" [하루 카드 만들기]
exports.chatOnDayDone = onDocumentWritten("groups/{gid}/events/{eid}/days/{date}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!after || after.status !== "DONE" || before?.status === "DONE") return;
  const { gid, eid, date } = event.params;
  if (!(await sharedGroup(gid))) return;
  const records = await db.collection(`groups/${gid}/events/${eid}/days/${date}/records`).get();
  const names = records.docs.map((d) => d.get("place")?.name).filter(Boolean);
  await chatCard(gid, `daydone-${date}`, { uid: "", kind: "DAY_DONE", text: `오늘 ${names.length}곳 다녀왔어요`, lines: names.length ? [names.join(" → ")] : [], eventId: eid, date });
});

// 칸 코멘트 → 채팅에도 한 줄 (대화가 두 곳으로 갈라지지 않게)
exports.chatOnComment = onDocumentCreated("groups/{gid}/events/{eid}/days/{date}/comments/{cid}", async (event) => {
  const c = event.data.data();
  const { gid, eid, date, cid } = event.params;
  if (!c?.text || !(await sharedGroup(gid))) return;
  const stop = c.stopId ? await db.doc(`groups/${gid}/events/${eid}/days/${date}/stops/${c.stopId}`).get() : null;
  await chatCard(gid, `comment-${cid}`, { uid: c.uid || "", kind: "COMMENT", text: c.text, refId: c.stopId || null, lines: [stop?.get("place")?.name || "칸"], eventId: eid, date });
});

// ---------- D4 다음 여행 추천 (정원 2026-11-06) ----------
// 폰이 보낸 취향 요약(장소 이름·지역·숫자만, 개인정보 없음)으로 Claude가 다음 여행 동네와 하루 동선을 고른다.
// 키는 서버 비밀값(ANTHROPIC_API_KEY)에만: `firebase functions:secrets:set ANTHROPIC_API_KEY` 후 재배포.
// 폰은 받은 장소를 카카오로 실제 있는지 확인한 뒤에만 보여 준다. 하루 10번(uid 기준).
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");

exports.suggestTrip = onCall({ secrets: [ANTHROPIC_API_KEY], timeoutSeconds: 60 }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "로그인이 필요해요");
  const taste = String(req.data?.taste || "").slice(0, 2000);
  if (!taste) throw new HttpsError("invalid-argument", "취향 요약이 비었어요");
  const key = ANTHROPIC_API_KEY.value();
  if (!key) throw new HttpsError("failed-precondition", "서버에 AI 키가 아직 없어요");

  // 하루 10번: suggest/{uid} 문서에 날짜·횟수 (클라이언트는 못 읽는 컬렉션).
  const today = new Date().toISOString().slice(0, 10);
  const ref = db.doc(`suggest/${req.auth.uid}`);
  const count = await db.runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data();
    const n = d?.date === today ? (d.count || 0) + 1 : 1;
    tx.set(ref, { date: today, count: n });
    return n;
  });
  if (count > 10) throw new HttpsError("resource-exhausted", "오늘 추천을 다 썼어요 · 내일 다시");

  const avoid = String(req.data?.avoid || "").slice(0, 300); // 이미 추천했던 동네 (다른 곳 버튼)
  const prompt = `${taste}

이 사람이 좋아할, 아직 안 가 본 대한민국 동네 한 곳과 차로 가는 하루 동선을 골라 줘.
규칙:
- "가 본 지역"과 그 바로 옆 동네는 피하되, 취향(종류)은 그대로 살릴 것${avoid ? `\n- 이 동네도 피할 것: ${avoid}` : ""}
- 하루 이동 거리를 크게 넘지 않는 곳
- 장소는 실제로 있는 유명한 곳만 3~4곳, 차로 도는 순서대로
- reason은 기록을 근거로 한 문장 (예: "포항 바다에 또 갈래요를 눌러서 → 이번엔 영덕 바다")
JSON만 답할 것:
{"region":"영덕군","title":"영덕 바다 하루","reason":"…","stops":[{"name":"강구항","query":"영덕 강구항"}]}`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({
      model: "claude-sonnet-5-5",
      max_tokens: 700,
      messages: [{ role: "user", content: prompt }],
    }),
  });
  if (!res.ok) throw new HttpsError("unavailable", `AI 응답 실패 ${res.status}`);
  const body = await res.json();
  const text = (body.content || []).map((c) => c.text || "").join("");
  const m = text.match(/\{[\s\S]*\}/);
  if (!m) throw new HttpsError("internal", "AI 답을 읽지 못했어요");
  let out;
  try { out = JSON.parse(m[0]); } catch { throw new HttpsError("internal", "AI 답이 JSON이 아니에요"); }
  const stops = Array.isArray(out.stops) ? out.stops.slice(0, 5).map((s) => ({ name: String(s.name || ""), query: String(s.query || s.name || "") })).filter((s) => s.name) : [];
  if (!out.region || stops.length < 2) throw new HttpsError("internal", "AI 추천이 비었어요");
  return { region: String(out.region), title: String(out.title || `${out.region} 하루`), reason: String(out.reason || ""), stops };
});
