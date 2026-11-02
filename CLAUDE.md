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
- 된다(2026-11-02): 내비 즐겨찾기·최근 목적지(이름·주소·좌표), **내비에서 고른 목적지 받아오기**(`NaviRouter.pickInNavi` — 내비 검색을 열고 1초마다 `getDestinationInfo`, 고르면 원래 경로 복원·우리 앱 앞으로). 장소 고르기는 `CarPlacePicker`(내비 먼저, 카카오 [목록으로]는 보조). 남은 시간은 길안내 중 내비 값(`onDrivingInfo`)이 먼저.
- 안 된다: 시동 상태, 경로선 좌표, 타이어 공기압(시스템 권한), 내비 검색 결과를 데이터로 받기, 에뮬 GPS로 차 이동, TTS와 STT 동시 사용(말하기 끝난 뒤 듣기).
- Pleos 권한이 manifest에 빠지면 SDK 호출 순간 앱이 죽는다. 새 SDK 기능을 쓰면 권한부터 확인.
- 에뮬레이터는 반드시 Cold boot. Virtual Console: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088
- Virtual Console을 curl로 조작하려면 1초마다 `POST /browser/session?client=x` 하트비트를 보내야 켜진다(브라우저 흉내). 그 뒤 `PUT /vehicle/gear?gear=Park|Drive`, 문은 `PUT /vehicle/body?door_fl=true`, 연료·주행거리는 `/vehicle/ev-battery`, `/vehicle/odometer`. 목록은 `GET /vehicle/catalog`. **벨트 신호는 콘솔에 없다**(13장은 P + 운전석 문으로). 좌석 착석(`seat-occupancy`, 좌석별 Occupied/Empty)·문 잠금(`door-lock`)·하이패스 시나리오는 **있다**(2026-10-30 확인, 브라우저 UI의 BODY 칸 / `GET /vehicle/catalog`). "Seat Belt"는 경고등(telltale)일 뿐 버클 신호 아님. 위치 이동(GPS)은 여전히 없음.
- 에뮬 온디바이스 TTS는 `onDone`이 안 온다 → `VoicePrompt`는 문장 길이만큼 기다렸다 STT로 넘어간다. STT는 마이크 입력이 없어 결과를 못 본다(버튼으로 대신).

## 비밀값 — 절대 git에 올리지 않는다
- API 키는 `local.properties`에 넣고 Gradle에서 읽는다. 코드에 직접 쓰지 않는다.
- `google-services.json`, `keystore.properties`, `*.jks`, `.env`는 `.gitignore`에 있다. 커밋 전에 `git status`로 확인한다.
- release 서명 키는 저장소 밖에 보관한다. 잃어버리면 같은 앱으로 업데이트할 수 없다.

## 작업 방식
- 지금은 정원 혼자 만든다: **`main`에 바로 커밋하고 push**. 커밋 메시지에 장 번호를 쓴다(예: `phone 2장: 캘린더 월 보기`).
- 나중에 작업을 나눌 때부터는 기능 하나 = 브랜치 하나 = PR 하나(`car/09-next-destination`)로 바꾼다.
- 로그인(1장) 코드 완료(`phone/login/Login.kt`): 첫 실행은 익명 → 첫 화면에서 구글(Credential Manager, 익명 계정에 link → uid 유지)·나중에. **카카오 로그인은 안 만든다**(2026-10-30 정원: 시연만 하면 되니 구글만). 구글 SHA-1(debug·release)은 Firebase에 이미 등록됨. Pleos 에뮬레이터엔 구글 서비스가 없어 실제 폰에서 확인.
- 사용자는 한국어, 쉬운 말과 표·단계로 설명받기를 원한다.
- 빌드 확인: `./gradlew assembleDebug`. 제출 APK: `./gradlew assembleRelease`.

## 사용자(정원)와 일하는 법
- 2인 팀(대학생, 현대자동차그룹 차량용 앱 공모전 대학생 부문)이지만 지금은 **정원이 혼자 전부 만든다**(분담 없음). 바이브코딩.
- 짧은 구어체로 지시한다. 답은 **쉬운 한국어, 표·단계**로. 결정·확인 결과는 docs HTML에 반영한다.
- **지금은 기능 구현이 우선.** 단, 새 화면은 아래 "디자인 규칙"을 그대로 따른다(2026-10-30 정함).

## 디자인 규칙 — 현대(Pleos) 기본 UI에 녹아들게
- **흰 바탕, 검정에 가까운 글자**(`Palette.TextStrong` #111), 보조 글자 회색(`TextSoft`). 어두운 카드·그라데이션 없음.
- **차 화면은 현대(Pleos) 기준을 따른다**(2026-10-31 정원: 구글 기준 아님). 출처 document.pleos.ai 「Pleos Connect UI Design Guide」·「Typography」·「주행 안전을 고려한 설계」. 글자 단계(px = 에뮬 160dpi에서 dp/sp): Headline 48·56·60 / Title 32·36·40 / Body 26·28·30 / Label 20·24, **최소 20**. 대비 4.5:1 이상, 한 덩어리 3줄 이하, 1~2초 안에 읽히게, 입력 뒤 0.25초 안에 반응(늦으면 로딩 표시), 주간/야간 모드 지원, 터치는 진동·음성 피드백 권장, 주행 중 동영상·애니메이션·자동 스크롤 금지, 음성 우선. 버튼 크기 수치는 문서에 없음 → 우리는 76dp 이상 유지. 차 화면은 가로·세로(반반) 둘 다 맞아야 함 — 반반이면 `LocalCompact`가 true(창 폭 < `CompactBelow` 1100dp), 새 화면은 좁은 모양을 같이 만든다. **색·글꼴도 현대 디자인 에셋(Figma 변수 Light/Dark)에서 가져옴**: 글자 #131417(밤 #FFFFFF), 보조 64%, 구분선 10%, 파랑 informative/positive #0064FF(밤 #0082FF), 경고 #FE3D16, 밤 바탕 #131417·팝업 #313236 — `car/CarTheme.kt`. 글꼴은 차 시스템 글꼴 Asta Sans(차에 "pretendard" 이름으로 등록, `DeviceFontFamilyName`). 폰 앱의 파랑 #0066B3 규칙은 폰에만.
- **차 앱 = 주차 중엔 폰 앱 수준**(2026-10-31 정원): 주행 중(`drivingRestricted`)엔 "오늘"만, 주차 중엔 아래 가운데 떠 있는 둥근 탭 바 `CarTabBar`(넷플릭스 하단 바 참고, 정원 2026-10-31 — 차에서 둥근 건 이 바만): 오늘·캘린더·같이 탈 사람·기록·차량(`CarTab`). 차에서 초대는 QR로만, 채팅·코멘트·묶음 관리는 폰. 화면은 차 크기로 새로 만들고 데이터는 `shared` 그대로. 내비와 반반은 운전자가 창 막대 ⋮를 끌어서(앱이 강제 못 함).
- **차엔 시스템 뒤로 버튼이 없다**(Pleos GNB). 차 앱의 전체 화면은 왼쪽 위 `CarBackButton`([← 뒤로])으로 시작, 대화창은 아래 [닫기].
- **차 앱은 다크 모드가 있다**(2026-10-31 정원): 차가 밤 모드면 검정 바탕·밝은 글자(`car/CarTheme.kt`의 `DarkCarColors`, 파랑은 #4DA3FF로 밝게). 차 색은 `LocalCarColors`를 통해서만 쓴다(`Ink`·`Soft` 같은 getter). 그리기 람다(`drawBehind`·`Canvas`) 안에선 색을 미리 변수에 담는다. 시연 메뉴 [다크 모드 미리보기]로 확인.
- **섹션은 둥근 카드로 나누지 않는다.** 얇은 구분선(`HorizontalDivider(color = Palette.Line)`)과 여백으로 나눈다. 색 바탕 칸 금지(입력 필드만 `InputBg`).
- 모서리는 전부 **4dp**(`Corner`). 원형 점·배지 대신 숫자·짧은 글자.
- 버튼: 주요 = 검정 채움 흰 글자(`Palette.Navy`), 보조 = 흰 바탕 + 1dp 회색 테두리. 칩도 테두리형.
- 강조색은 하나: **파랑 `Palette.Teal`(#0066B3)** = "차가 가는 곳"(장소·다음 목적지·경로)에만.
- **이모지 쓰지 않는다**(🚗🔧🔔▶ 등). 필요하면 Material 아이콘(`material-icons-core`)이나 글자("이동", "정비", "다음").
- 차 앱도 같은 규칙(`car/MainActivity.kt` 상단 색 상수). 세 칸은 세로 구분선으로, 질문은 검정 띠.
- 폰 하단 바는 시스템식 평면 바(아이콘+글자, 위에 구분선). 떠 있는 둥근 바 아님.
- **하루 일정은 레일 타임라인**(`phone/ui/Rail.kt`: `railLine`·`RailNode`·`speechBubble`): 노드 = 장소, 지난 칸 ✓ 회색, 다음 칸 파랑, 계획 칸 빈 고리, 코멘트는 노드 아래 말풍선. 여행 편집·그날 동선·차 화면이 같은 그림. 애니메이션: **주차 중엔 모든 전환을 부드럽게**(정원 2026-10-31 — 차 `car/CarMotion.kt`: 0.25초 하나로 창은 살짝 커지며 나타남·전체 화면은 옆에서·띠와 알림 줄은 위→아래로 열고 닫힘·선택 색은 서서히·순서 바꾸기는 미끄러짐·탭과 달은 옆으로), **주행 중엔 전부 멈춤**(`LocalMotion` false, Pleos). 새 화면도 `CarEnter`·`CarAppear`·`carColor`·`carSize`로. 폰도 같은 0.25초(`phone/ui/Motion.kt` `Enter` — `CenterSheet`가 자동으로, 전체 화면은 `Entry.SCREEN`, 아래 탭은 옆으로). **손짓**(2026-11-01): 폰 전체 화면은 `BackScreen`으로 원래 화면 위에 겹쳐 띄움(뒤로 손짓을 따라 밀려남), 옆으로 넘기기는 `Modifier.sideSwipe`(달·날짜, 넘어갈 때 진동), 탭 다시 누르기는 `OnTabTapAgain`, 사진은 `PhotoViewer`. 차는 `monthSwipe`·`OnCarTabTapAgain`.
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
- **초대 = 일정, 친구 = 한 번, 그룹 = "자주 타는 사람" 묶음**(2026-10-31 정원): 일정마다 [같이 갈 사람]에서 친구 이름·묶음을 눌러 초대, 처음 보는 사람만 QR·링크. 여행 없는 날 차 QR = 차주와 친구 맺기(같은 /i/ 링크에 친구 코드, 폰은 그룹 코드가 아니면 친구로 처리).
- **초대 = 일정 단위**(2026-10-31 A안): 그룹을 먼저 만들지 않는다. 전용 그룹이 아닌 일정(나만 그룹·묶음)에 처음 초대하면 `GroupRepository.shareEvent`가 그 일정 전용 그룹(`Group.forEvent`)을 만들고 원래 보던 사람째, 하위(날·칸·픽업·코멘트·기록)째 옮긴다. `forEvent` 없는 공유 그룹 = "자주 타는 사람" 묶음(`isCircle`). 초대 방법 = 친구 한 번 누르기·묶음 [모두 초대]·QR·링크·친구 코드. 차는 QR 창, 폰은 여행 화면 [같이 갈 사람](`trip/InviteSheet.kt`, 2026-11-01).
- 여러 날 여행 = 이어진 일정 하나 + 날짜별 장소 칸. 내비 경로는 하루 단위.
- **차주가 호스트**: 차는 차주 계정 하나에 연결(차 화면 6자리 코드를 폰 차량 탭에 입력). 차주가 만든 그룹의 일행은 그날 일정을 차주와 같은 권한으로 편집.
- 차 화면 QR = 동승자가 탔을 때 그룹 가입용. 차에서도 일정 추가 가능(주차 중만).
- 기록 칸은 시동 대신 **P단 + 운전석 벨트/문 신호** 제안(에뮬 확인 전).
- 프로토/나중 구분 없음, iOS만 제외. 로그인(구글·카카오)은 맨 마지막.

## 진행 상황 (2026-10-30)
- 완료: 뼈대·Firebase(`carplan-htb`), shared 데이터 구조, 폰 캘린더 메인(오늘 카드+월 보기+그날 동선, 디자인은 추후), 빠른 일정 입력, 카테고리 추가, 폰 차량 탭(차 연결), 차 첫 화면(상태 띠·오늘·이번 주·큰 버튼), 차에서 일정 추가, 차 상태 Firestore 공유, 이름만 있는 장소는 현대 내비 검색으로, **3장 여행 일정**(여러 날 여행 → 날짜별 장소 칸: 추가·시간·메모·빼기·되살리기·끌어서 순서·직선거리 정렬, 매 변경마다 그날 `version +1`; `docs/다음작업_3장_여행일정.md`는 끝난 메모).
- 카카오 REST 키·오피넷 키는 정원 컴퓨터 `local.properties`에 들어 있음(2026-10-30, 둘 다 동작 확인). 한국도로공사 휴게소 API는 오피넷과 겹쳐서 안 쓴다. 키 없는 컴퓨터에서도 빌드·실행은 됨(이름 입력·직선거리·내비 검색으로 대체).
- 3장 타임라인 완료: 레일 화면, 칸별 코멘트 글타래(`days/{date}/comments`), 예상 도착 시각(`shared/calendar/Schedule.kt`), 시간 그 자리에서 확정.
- 9장 "다음 목적지로 갈까요?" 완료(차: `TripNavigator`·`VoicePrompt`, 앱 켤 때·P→D 때 묻기, 경유지 2+목적지 → 도착마다 채움, 시연용 도착 버튼). 10장(일정 변경 → 내비 갱신)은 `TripNavigator`가 `TripDay.version`을 보고 묻는 것부터.
- 10장 완료(차 `TripNavigator`: version 변경 5초 모아 묻기, 바꾸기/그대로, `routeSyncedVersion`·`holdByDriver`·`etaNext`; 폰: 차주 폰 주행 중 편집 잠금, 상태 줄).
- 4장·7장 완료(폰 `group/`: 그룹 탭·화면·초대 3종·딥링크/QR 참여·친구, 여행 그룹 칩, "오늘 이 차로 가요"; 차: 초대 QR·착석 자동 QR; `hosting/public` 초대 페이지 배포됨, `firebase deploy --only hosting`).
- 13·14장 1차(기록 칸·레일 기록·사진 코드·내보내기·기록 탭)와 12·20장(주유/충전 질문·오피넷·시연 메뉴) 코드 완료. **에뮬 확인은 `docs/테스트체크리스트.md` F2·F3가 남음.**
- 키 이름: `KAKAO_REST_KEY`(폰·차), `OPINET_KEY`(차). 둘 다 없어도 동작(대체 경로). 차는 카카오모빌리티 길찾기로 픽업 순서(총 시간)·ETA(30초마다)를 계산한다.
- 15장 차량 관리 코드 완료(shared `MaintenanceRules`·`MaintenanceRepository`, 폰 차량 탭, 차 주행거리·"주유했어요?"). 에뮬 확인은 체크리스트 F4.
- 5·6장 코드 완료(shared `VoteRepository`·`DartRepository`·`Regions`, 폰 `vote/` 창, 차 다트). 에뮬 확인은 체크리스트 F5. 행정동 전체 목록 `assets/regions.csv`(읍·면·동 3,559곳, 폰·차 둘 다) 들어감 — 출처 vuski/admdongkor ver20261001 경계의 면적 가중 중심점.
- 11장 코드 완료(shared `PickupRepository`, 폰 `trip/PickupSheet`+`ui/SimpleMap`(OSM 타일), 차 픽업 동의 질문·픽업 우선 경로·5초 공유·가짜 주행 보간). 에뮬 확인은 체크리스트 F6.
- 16장 코드 완료(shared `Repeat`·`Commute` + 테스트, 반복 일정 펼치기, 차 "회사로 갈까요?", 폰 학습 띠). 에뮬 확인은 체크리스트 F7.
- 17장 **배포됨**(2026-10-30): Functions 6개(kakaoLogin은 코드에서 뺌 — 다음 배포 때 삭제 확인 Y)(asia-northeast3, Node 22) + Storage 버킷 `carplan-htb.firebasestorage.app`(US-EAST1 무료 위치) + storage.rules(18장 멤버만, 2026-10-30 배포됨). 다시 배포는 정원이 `firebase deploy --only functions --force`.
- 폰 알림 탭 딥링크 `carplan://trip/{gid}/{eid}/{date}` 처리됨(`InviteInbox.offerTrip` → `AppRoot`가 그날 레일을 전체 화면으로). `hosting/public/.well-known/assetlinks.json`에 release·debug 키 SHA-256 들어감(release 키 `~/carplan-keys/carplan-release.jks`).
- 설정 탭 완료(`phone/settings/SettingsTab.kt`: 이름·친구 코드·일정 알림 스위치·주유 알림 기준 `cars.fuelAlertPercent`·계정·버전). 일정 [알림] 칩은 `phone/push/Reminders.kt`(AlarmManager, 2주, 반복 포함)로 실제로 울림. 11장 픽업 순서(총 시간)·지도 경로선·ETA, 17장 "곧 도착"·"주차장" 푸시까지 코드 완료.
- 18장 보안 규칙 = `firestore.rules`(= `firestore.prod.rules`, 에뮬레이터로 23개 경우 통과). 배포 완료(2026-10-30). 바꾸면 **정원이 직접** `firebase deploy --only firestore:rules`(Claude는 배포 권한 없음). 문제 생기면 `firestore.dev.rules`를 `firestore.rules`로 복사해 다시 배포. 규칙 테스트는 `firebase emulators:exec --only firestore "python3 -I <스크립트>"`로 가짜 JWT(alg none) + REST. 주의: v2 규칙에서 `{doc=**}`는 0단계도 잡아 부모 문서 규칙을 덮는다.
- 14장 짧은 영상 내보내기 완료(`phone/record/DayVideo.kt`, MediaCodec YUV420 입력 → mp4, 에뮬 미확인). 기획서 대비 안 만든 기능 없음(2026-10-30 저녁 기준).
- Q05 구글 캘린더 읽기 완료(`phone/calendar/GoogleCalendar.kt`, AuthorizationClient calendar.readonly, 설정 탭 스위치, 메모리에만). GCP `carplan-htb`에 Google Calendar API 사용 설정 필요(Chrome으로 켬). Storage 규칙 멤버만 배포됨(2026-10-30).
- **2026-10-30 밤 상태**: 기획서(작동시나리오) 기능은 전부 코드 있음. 서버(규칙·Functions 6개·Storage·Hosting) 배포 끝. UI/UX 1차 개편 끝(차 첫 화면 두 모양·질문 패널·더보기·일정 추가 전체 화면·차 다트 게임 / 폰 레일 칸 접기·⋮ 메뉴·그룹 [+]·기록 일기장·차량 탭 상세·글자 크기 한 단계). 사용 흐름 평가 60~65%.
- **0단계 시뮬레이션 끝(2026-10-31)**: 차 `DriveSimulator.kt` — 시연 메뉴 [오늘 시험 여행 만들기]→[시뮬레이션 재생]. 앱이 에뮬 안 Virtual Console(127.0.0.1:8088)에 기어·속도·문을 직접 보냄, 위치는 카카오 도로 좌표. 좌석 SDK 이벤트는 에뮬에서 거의 안 옴(앱에 직접 넣음).
- **다음 작업은 `docs/다음작업_플랜.md`부터**: ~~0 시뮬레이션 뼈대~~ → 1 차 UI/UX → 2 폰 UI/UX → 3 시연 시나리오, 그 뒤 새 기능 아이디어(★출발 시각 역산·늦을 때 공유·주차 위치). 불편한 점·평가·아이디어는 `docs/UX_메모.md`, 빌드·설치는 `docs/앱_켜는_법.txt`, 확인 목록은 `docs/테스트체크리스트.md`(F11 착석·문 잠금, G 실기기).
- **2026-10-31 저녁 상태(차량만 집중)**: `docs/다음작업_플랜.md` 표 ①~⑩ 끝 — 차 최적화·다크 모드(`CarTheme`), 10장 자동 반영+[원래대로], 내비 목록 하나(`inNavi`), 질문 줄(`show()`), 차 ETA 하나, Q55 주유 계획(shared `FuelPlan`), 음성 명령([말하기]·`VoiceCommand`), 출발 시각, 오늘 여행 여럿 [다른 여행], 건너뜀(shared `Stop.skipped`). 시연 메뉴(차 화면 왼쪽 위 "carplan" 길게 누르기) 시험 버튼: 시험 여행·출근·픽업·두 번째 여행·다크/반반 미리보기. 차 남은 것 없음(Q56·Q57 차 완료 10-31). 나머지는 폰·실차.
- 정원 원칙: 대회·제출물·PT 얘기는 꺼내지 않는다. 개발(UI/UX)만. 고칠 때마다 에뮬로 써 보고 불편한 점을 UX_메모에 쌓아 보고.
- Firestore 18장 규칙 **배포됨**(2026-10-30, 실서버 23개 경우 통과). 개발용으로 되돌리려면 `cp firestore.dev.rules firestore.rules` 후 정원이 다시 배포.

## 에뮬레이터 테스트 요령
- AVD `Pleos_Connect_v2_1_0`, 반드시 Cold boot: `emulator -avd Pleos_Connect_v2_1_0 -no-snapshot-load`.
- 터치 입력은 디스플레이 지정: `adb shell input -d 0 tap X Y`, 글자는 `input -d 0 text` (영어만 됨, 띄어쓰기는 `%s`).
- 이미 떠 있는 앱을 앞으로 올릴 땐 `adb shell am start --activity-single-top -n …`. 옵션 없이 `am start`하면 새 화면이 열려 편집 상태가 사라진다. uiautomator dump는 무한 애니메이션(후광)이 있는 화면에서 실패하니 스크린샷 좌표로 탭한다.
- `adb shell input keyevent 111`(ESC)은 Compose 대화창을 닫는다. 쓰지 말 것.
- 좌표는 `uiautomator dump` 후 bounds로 찾는다. 스크린샷 `adb exec-out screencap -p`.
- 폰 앱도 이 에뮬레이터에 깔아서 같이 시험할 수 있다(앱마다 익명 계정이 따로 생김).
- Pleos 주행가능거리는 m 단위. 에뮬레이터는 전기차 설정이라 연료=배터리.
