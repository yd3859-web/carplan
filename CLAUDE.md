# carplan — Claude Code 작업 규칙

캘린더가 메인인 차량용 앱. 현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작.
캘린더에 쓴 일정이 차 내비를 움직이고, 차를 세우면 기록 칸이 생긴다.

## 문서
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

## 비밀값 — 절대 git에 올리지 않는다
- API 키는 `local.properties`에 넣고 Gradle에서 읽는다. 코드에 직접 쓰지 않는다.
- `google-services.json`, `keystore.properties`, `*.jks`, `.env`는 `.gitignore`에 있다. 커밋 전에 `git status`로 확인한다.
- release 서명 키는 저장소 밖에 보관한다. 잃어버리면 같은 앱으로 업데이트할 수 없다.

## 작업 방식
- 기능 하나 = 브랜치 하나 = PR 하나. 브랜치 이름에 장 번호: `car/09-next-destination`, `phone/03-create-stop`.
- `main`에 직접 커밋하지 않는다.
- 사용자는 한국어, 쉬운 말과 표·단계로 설명받기를 원한다.
- 빌드 확인: `./gradlew assembleDebug`. 제출 APK: `./gradlew assembleRelease`.
