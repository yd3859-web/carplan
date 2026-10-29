# carplan

**캘린더에 쓴 일정이 차를 움직이는 차량용 캘린더 앱.**
일행이 폰으로 같이 짠 하루 일정이 그대로 차의 내비 경유지가 되고, 차를 세울 때마다 그날의 기록이 한 칸씩 쌓입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작 (Pleos Connect).

## 구성
- `car-app` — Pleos Connect 차 앱 (일정표, 음성 안내, 내비 연동, 주차 시 기록)
- `phone-app` — 안드로이드 폰 앱 (일정 편집, 그룹, 기록)
- `shared` — 두 앱이 함께 쓰는 데이터 형식

## 테스트 환경
- 차 앱: Pleos Connect Emulator v2.1.0 (Android Automotive, API 34), Cold boot
- 폰 앱: Android 9 (API 28) 이상 기기
- 서버: Firebase (Auth, Firestore)

## 실행 방법
1. Android Studio에서 이 저장소를 연다.
2. `car-app/google-services.json`, `phone-app/google-services.json`을 넣는다. (Firebase 콘솔에서 받음, 저장소에는 없음)
3. 차 앱: Pleos Connect Emulator를 Cold boot로 켜고 `car-app`을 실행한다.
4. 폰 앱: 폰을 연결하고 `phone-app`을 실행한다.
5. 폰에서 장소를 추가하면 차 화면의 오늘 일정과 내비 경로에 반영된다.

릴리스 빌드에는 저장소 밖의 서명 키와 `keystore.properties`가 필요하다.

## APK
`apk/` 폴더 (제출 시 `팀명_carplan_release.apk`) 또는 GitHub Releases.
