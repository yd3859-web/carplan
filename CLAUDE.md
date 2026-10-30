# carplan — Claude Code 작업 규칙

캘린더가 메인인 차량용 앱. 현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작.
캘린더에 쓴 일정이 차 내비를 움직이고, 차를 세우면 기록 칸이 생긴다.

## 문서 (`docs/README.md`에 목록)
- `docs/`가 원본이다. 결정이 바뀌면 여기 HTML을 고친다(바탕화면 `현대` 폴더는 예전 사본).
- **기능 명세 = `docs/작동시나리오.html`** (0~22장). 기능을 만들 때 해당 장을 먼저 읽는다.
- 기술 확인 결과·외부 API·대회 규정 = `docs/기획정리본.html` 09·10장.
- `❓ Qxx` 표시는 아직 안 정한 것. **그 기능을 만들기 전에 사용자에게 추천과 함께 묻는다.**
  답을 받으면 HTML의 그 ❓를 결정으로 바꾸고, 22장 질문 모음의 "✓ 정함"에 한 줄 남긴다.
- "Claude 제안" 표시는 사용자 확인 전 초안. 크게 바꾸기 전에 묻는다.
- 프로토/나중 구분 없음. 문서의 기능은 전부 만든다(iOS만 제외).

## 구조
| 모듈 | 패키지 | 내용 |
|---|---|---|
| `car-app` | `com.htb.carplan.car` | Pleos Connect 차 앱. 일정표, "다음 목적지로 갈까요?", 내비 연동, P단 기록, 음성 |
| `phone-app` | `com.htb.carplan.phone` | 폰 앱. 일정 편집, 그룹·초대, 투표·다트, 픽업 지도, 기록 사진 |
| `shared` | `com.htb.carplan.shared` | 두 앱이 같이 쓰는 Firestore 데이터 형식(`model/`)과 경로(`data/FirestorePaths`) |

- 연결: 폰 앱 ⇄ Firestore ⇄ 차 앱 → 현대 내비(NaviHelper). 차 앱은 폰을 거치지 않고 서버에 직접 붙는다.
- 차 앱은 `TripSyncService`(포그라운드 서비스)로 살아 있어야 일정 변경을 받는다.
- **`shared`는 두 앱의 약속이다.** 바꿀 때는 PR로만, 두 사람 다 확인 후 머지. 바꾸면 `docs/작동시나리오.html` 18장 표도 같이 고친다.
- Firestore 경로를 문자열로 직접 쓰지 말고 `FirestorePaths`를 쓴다.

## 기술 스택
Kotlin + Jetpack Compose, AGP 9 (내장 Kotlin), minSdk 28, compileSdk 37.
Firebase: Auth, Firestore, Storage, FCM, Cloud Functions, Hosting(초대 링크 페이지). Supabase·Vercel 안 씀.
Pleos SDK: Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech·SpeechToText 3.3.1 (`gradle/libs.versions.toml`).

## Pleos 에뮬레이터에서 확인된 것 (docs/기획정리본.html 09장)
- 된다: 경로 없이 현재 위치, 경유지 넣기(SDK 번호는 첫째·둘째뿐 → 경유지 2개 + 목적지, 도착할 때마다 채움), 기어 P/D 이벤트, 연료량은 android.car 표준 `FUEL_LEVEL`(Pleos `getFuelPercent`는 실패), 주행 제한(UX restriction), 앱이 뒤에 있어도 내비 경로 변경.
- 안 된다: 시동 상태, 경로선 좌표, 타이어 공기압(시스템 권한), 내비 검색 결과를 데이터로 받기, 에뮬 GPS로 차 이동, TTS와 STT 동시 사용(말하기 끝난 뒤 듣기).
- Pleos 권한이 manifest에 빠지면 SDK 호출 순간 앱이 죽는다. 새 SDK 기능을 쓰면 권한부터 확인.
- 에뮬레이터는 반드시 Cold boot. Virtual Console: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088
- Virtual Console을 curl로 조작하려면 1초마다 `POST /browser/session?client=x` 하트비트를 보내야 켜진다(브라우저 흉내). 그 뒤 `PUT /vehicle/gear?gear=Park|Drive`, 문은 `PUT /vehicle/body?door_fl=true`, 연료·주행거리는 `/vehicle/ev-battery`, `/vehicle/odometer`. 목록은 `GET /vehicle/catalog`. **벨트 신호는 콘솔에 없다**(13장은 P + 운전석 문으로).
- 에뮬 온디바이스 TTS는 `onDone`이 안 온다 → `VoicePrompt`는 문장 길이만큼 기다렸다 STT로 넘어간다. STT는 마이크 입력이 없어 결과를 못 본다(버튼으로 대신).

## 비밀값 — 절대 git에 올리지 않는다
- API 키는 `local.properties`에 넣고 Gradle에서 읽는다. 코드에 직접 쓰지 않는다.
- `google-services.json`, `keystore.properties`, `*.jks`, `.env`는 `.gitignore`에 있다. 커밋 전에 `git status`로 확인한다.
- release 서명 키는 저장소 밖에 보관한다. 잃어버리면 같은 앱으로 업데이트할 수 없다.

## 작업 방식
- 지금은 정원 혼자 만든다: **`main`에 바로 커밋하고 push**. 커밋 메시지에 장 번호를 쓴다(예: `phone 2장: 캘린더 월 보기`).
- 나중에 작업을 나눌 때부터는 기능 하나 = 브랜치 하나 = PR 하나(`car/09-next-destination`)로 바꾼다.
- 로그인(구글·카카오)은 맨 뒤에 만든다. 그 전까지는 Firebase **익명 로그인**으로 uid를 얻고, 첫 실행 때 그 uid의 나만 그룹을 만든다. 나중에 구글 계정을 익명 계정에 연결하면 uid와 데이터가 그대로 유지된다.
- 사용자는 한국어, 쉬운 말과 표·단계로 설명받기를 원한다.
- 빌드 확인: `./gradlew assembleDebug`. 제출 APK: `./gradlew assembleRelease`.

## 사용자(정원)와 일하는 법
- 2인 팀(대학생, 현대자동차그룹 차량용 앱 공모전 대학생 부문)이지만 지금은 **정원이 혼자 전부 만든다**(분담 없음). 바이브코딩.
- 짧은 구어체로 지시한다. 답은 **쉬운 한국어, 표·단계**로. 결정·확인 결과는 docs HTML에 반영한다.
- **지금은 기능 구현이 우선.** 단, 새 화면은 아래 "디자인 규칙"을 그대로 따른다(2026-10-30 정함).

## 디자인 규칙 — 현대(Pleos) 기본 UI에 녹아들게
- **흰 바탕, 검정에 가까운 글자**(`Palette.TextStrong` #111), 보조 글자 회색(`TextSoft`). 어두운 카드·그라데이션 없음.
- **섹션은 둥근 카드로 나누지 않는다.** 얇은 구분선(`HorizontalDivider(color = Palette.Line)`)과 여백으로 나눈다. 색 바탕 칸 금지(입력 필드만 `InputBg`).
- 모서리는 전부 **4dp**(`Corner`). 원형 점·배지 대신 숫자·짧은 글자.
- 버튼: 주요 = 검정 채움 흰 글자(`Palette.Navy`), 보조 = 흰 바탕 + 1dp 회색 테두리. 칩도 테두리형.
- 강조색은 하나: **파랑 `Palette.Teal`(#0066B3)** = "차가 가는 곳"(장소·다음 목적지·경로)에만.
- **이모지 쓰지 않는다**(🚗🔧🔔▶ 등). 필요하면 Material 아이콘(`material-icons-core`)이나 글자("이동", "정비", "다음").
- 차 앱도 같은 규칙(`car/MainActivity.kt` 상단 색 상수). 세 칸은 세로 구분선으로, 질문은 검정 띠.
- 폰 하단 바는 시스템식 평면 바(아이콘+글자, 위에 구분선). 떠 있는 둥근 바 아님.
- **하루 일정은 레일 타임라인**(`phone/ui/Rail.kt`: `railLine`·`RailNode`·`speechBubble`): 노드 = 장소, 지난 칸 ✓ 회색, 다음 칸 파랑, 계획 칸 빈 고리, 코멘트는 노드 아래 말풍선. 여행 편집·그날 동선·차 화면이 같은 그림. 애니메이션은 "무슨 일이 생겼는지" 알릴 때만(도착 → 레일 채움, 코멘트 등장, 순서 끌기), 차에서는 레일 채움만.
- **입력·선택 창은 전부 화면 가운데 대화창**(`ui/CenterSheet.kt`): 연회색 바탕(`Palette.Sheet`) + 1dp 테두리(`SheetEdge`) + 그림자, 안의 카드·입력칸은 흰색. 아래에서 올라오는 바텀 시트 금지(차 화면에서 운전자 손이 안 닿음). 글자는 폰 기준보다 한 단계 크게(본문 17sp, 제목 22sp, 캘린더 날짜 17sp).
- 정원이 예시 이미지를 주면 **참고용**이다. 그대로 베끼지 말고 우리 앱(일정이 차를 움직임)에 맞게 다시 설계하고, 어디를 참고할지 먼저 묻는다.
- API 키는 정원이 필요한 순간에 준다. 받으면 `local.properties`에만 넣는다.
- `docs/Pleos_시작가이드.txt`는 수정하지 않는다.

## 대회 일정·제출 (docs/기획정리본.html 10장)
- 기획서 마감 **11/15 23:59** (이메일, 접수양식 DOCX, 팀원 전원 CC). 1차에 시연 영상·GitHub·APK 내면 가산점.
- 1차 결과 11/23 → 2차 오프라인 PT·시연 **12/3–4** (PT 이후엔 버그 수정만 인정) → 최종 발표 12/18.
- APK는 **release** 빌드, 이름 `팀명_carplan_release.apk`, 저장소 `apk/` 또는 GitHub Releases. 비공개 저장소면 심사 계정 Collaborator 초대(계정명 공지 예정).

## 정한 것 요약 (자세한 건 작동시나리오 22장 "✓ 정함")
- 캘린더가 메인. 카테고리 여행·차량 관리·다른 일정 + 사용자 추가(8색). 모든 일정 = `groups/{gid}/events` 하나, 혼자면 나만 그룹.
- 여러 날 여행 = 이어진 일정 하나 + 날짜별 장소 칸. 내비 경로는 하루 단위.
- **차주가 호스트**: 차는 차주 계정 하나에 연결(차 화면 6자리 코드를 폰 차량 탭에 입력). 차주가 만든 그룹의 일행은 그날 일정을 차주와 같은 권한으로 편집.
- 차 화면 QR = 동승자가 탔을 때 그룹 가입용. 차에서도 일정 추가 가능(주차 중만).
- 기록 칸은 시동 대신 **P단 + 운전석 벨트/문 신호** 제안(에뮬 확인 전).
- 프로토/나중 구분 없음, iOS만 제외. 로그인(구글·카카오)은 맨 마지막.

## 진행 상황 (2026-10-30)
- 완료: 뼈대·Firebase(`carplan-htb`), shared 데이터 구조, 폰 캘린더 메인(오늘 카드+월 보기+그날 동선, 디자인은 추후), 빠른 일정 입력, 카테고리 추가, 폰 차량 탭(차 연결), 차 첫 화면(상태 띠·오늘·이번 주·큰 버튼), 차에서 일정 추가, 차 상태 Firestore 공유, 이름만 있는 장소는 현대 내비 검색으로, **3장 여행 일정**(여러 날 여행 → 날짜별 장소 칸: 추가·시간·메모·빼기·되살리기·끌어서 순서·직선거리 정렬, 매 변경마다 그날 `version +1`; `docs/다음작업_3장_여행일정.md`는 끝난 메모).
- 카카오 키는 아직 없음. `local.properties`에 `KAKAO_REST_KEY=`를 넣고 다시 빌드하면 장소 검색·근처 추천·길찾기가 카카오로 바뀐다(없으면 이름 직접 입력·직선거리).
- 3장 타임라인 완료: 레일 화면, 칸별 코멘트 글타래(`days/{date}/comments`), 예상 도착 시각(`shared/calendar/Schedule.kt`), 시간 그 자리에서 확정.
- 9장 "다음 목적지로 갈까요?" 완료(차: `TripNavigator`·`VoicePrompt`, 앱 켤 때·P→D 때 묻기, 경유지 2+목적지 → 도착마다 채움, 시연용 도착 버튼). 10장(일정 변경 → 내비 갱신)은 `TripNavigator`가 `TripDay.version`을 보고 묻는 것부터.
- 10장 완료(차 `TripNavigator`: version 변경 5초 모아 묻기, 바꾸기/그대로, `routeSyncedVersion`·`holdByDriver`·`etaNext`; 폰: 차주 폰 주행 중 편집 잠금, 상태 줄).
- 4장·7장 완료(폰 `group/`: 그룹 탭·화면·초대 3종·딥링크/QR 참여·친구, 여행 그룹 칩, "오늘 이 차로 가요"; 차: 초대 QR·착석 자동 QR; `hosting/public` 초대 페이지 배포됨, `firebase deploy --only hosting`).
- 다음: 13·14장 기록(P단 → 기록 칸, 사진, 타임라인 레일에 채우기) → 20장 디버그 메뉴 → 12장 주유 → 9·10장 "다음 목적지로 갈까요?"·일정 변경 → 내비 갱신 → 13·14장 기록 → 15·16장 차량 관리·출퇴근 → 나머지.
- Firestore 규칙은 아직 dev(로그인만 하면 읽기·쓰기). 1차 제출 전 18장 규칙으로 바꾼다.

## 에뮬레이터 테스트 요령
- AVD `Pleos_Connect_v2_1_0`, 반드시 Cold boot: `emulator -avd Pleos_Connect_v2_1_0 -no-snapshot-load`.
- 터치 입력은 디스플레이 지정: `adb shell input -d 0 tap X Y`, 글자는 `input -d 0 text` (영어만 됨, 띄어쓰기는 `%s`).
- 이미 떠 있는 앱을 앞으로 올릴 땐 `adb shell am start --activity-single-top -n …`. 옵션 없이 `am start`하면 새 화면이 열려 편집 상태가 사라진다. uiautomator dump는 무한 애니메이션(후광)이 있는 화면에서 실패하니 스크린샷 좌표로 탭한다.
- `adb shell input keyevent 111`(ESC)은 Compose 대화창을 닫는다. 쓰지 말 것.
- 좌표는 `uiautomator dump` 후 bounds로 찾는다. 스크린샷 `adb exec-out screencap -p`.
- 폰 앱도 이 에뮬레이터에 깔아서 같이 시험할 수 있다(앱마다 익명 계정이 따로 생김).
- Pleos 주행가능거리는 m 단위. 에뮬레이터는 전기차 설정이라 연료=배터리.
