# carplan

**캘린더에 쓴 일정이 차를 움직이는 차량용 캘린더 앱.**
일행이 폰으로 같이 짠 하루 일정이 그대로 차의 내비 경유지가 되고, 차를 세울 때마다 그날의 기록이 한 칸씩 쌓입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작 (Pleos Connect).

## 구성
- `car-app` — Pleos Connect 차 앱 (오늘 일정 레일, "다음 목적지로 갈까요?" 음성 질문, 내비 경유지, 주차 시 기록)
- `phone-app` — 안드로이드 폰 앱 (캘린더, 하루 일정 레일 편집·코멘트, 그룹, 기록)
- `shared` — 두 앱이 함께 쓰는 데이터 형식과 Firestore 경로
- `docs/` — 기능 명세(`작동시나리오.html` 0~22장), 기술 확인 결과, 일정 (`docs/README.md`)

## 지금 되는 것 (2026-10-30)
| 장 | 기능 |
|---|---|
| 2 | 폰 캘린더: 오늘 헤더 + 월 보기 + 그날 동선 레일, 빠른 일정 입력, 카테고리 |
| 3 | 여행: 여러 날 + 날짜별 장소 칸을 레일 타임라인으로 편집(끌어서 순서, 코멘트 말풍선, 예상 도착 시각). 카카오 키 없이도 동작 |
| 7·8 | 차 연결(6자리 코드), 차 첫 화면(연료·주행가능거리·기어·오늘·이번 주), 차에서 일정 추가 |
| 9 | 차: 앱 켤 때·P→D 때 "다음 목적지 ○○로 갈까요?"(TTS/STT + 버튼) → 경유지 2개 + 목적지, 도착마다 다음 칸 채움 |

다음: 10장(폰에서 바꾸면 차가 묻고 경로 갱신) → 4장 그룹·초대 → 13·14장 기록.

## 테스트 환경
- 차 앱: Pleos Connect Emulator v2.1.0 (Android Automotive, API 34), Cold boot
- 폰 앱: Android 9 (API 28) 이상 기기
- 서버: Firebase (Auth, Firestore)

## 실행 방법
1. Android Studio에서 이 저장소를 연다.
2. `car-app/google-services.json`, `phone-app/google-services.json`을 넣는다. (Firebase 콘솔에서 받음, 저장소에는 없음 — 심사용으로 필요하면 팀에 요청)
   카카오 장소 검색을 쓰려면 `local.properties`에 `KAKAO_REST_KEY=`를 넣는다. 없으면 장소는 이름만 입력하고 거리·시간은 직선거리로 계산한다.
3. 차 앱: Pleos Connect Emulator를 Cold boot로 켜고 `car-app`을 실행한다.
4. 폰 앱: 폰을 연결하고 `phone-app`을 실행한다.
5. 폰에서 장소를 추가하면 차 화면의 오늘 일정과 내비 경로에 반영된다.

릴리스 빌드에는 저장소 밖의 서명 키와 `keystore.properties`가 필요하다.

## APK
`apk/` 폴더 (제출 시 `팀명_carplan_release.apk`) 또는 GitHub Releases.
