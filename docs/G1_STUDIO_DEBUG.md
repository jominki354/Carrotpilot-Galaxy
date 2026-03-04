# G1 Studio Debug (Android 16 / One UI 8)

목표:
- Android Studio에서 `debug` 빌드/실행
- Panda USB attach/permission 경로 확인
- CAN 수집 -> fingerprint 상태 전이 확인
- `FAKE_USB`와 `REAL_USB`를 같은 코어 로직으로 검증

## 1. 사전 조건

1. Android Studio 설치 (JBR 포함)
2. Android SDK Platform 36 설치
3. 디바이스 개발자 옵션/USB 디버깅 ON
4. USB-C OTG 허브 또는 직접 연결 가능한 케이블 준비

## 2. 프로젝트 열기

1. `Carrotpilot_galaxy` 폴더를 Studio로 열기
2. Gradle Sync 완료 대기
3. Build Variant: `debug`

## 3. 디버그 빌드 검증

CLI 기준:
1. `.\gradlew.bat :app:assembleDebug`
2. `.\gradlew.bat :app:testDebugUnitTest`

Studio 기준:
1. `Build > Make Project`
2. 테스트: `app/src/test` 우클릭 실행

## 4. 실기기 실행

1. 앱 실행 후 `Start G1` 클릭
2. Panda 연결/권한 허용
3. 화면에서 `Stage`가 아래 순서로 진행되는지 확인:
- `USB_PERMISSION_READY`
- `PANDA_CONNECTED`
- `CAN_RX_STABLE`
- `FINGERPRINTING`
- `CAR_IDENTIFIED`
- `CARPARAMS_PUBLISHED`
- `SAFETY_READY`

차량/판다가 없을 때:
1. 앱 상단 `Source mode`를 `Fake USB`로 변경
2. `Start G1` 클릭
3. fake stream 기준으로 `CAR_IDENTIFIED`까지 진행되는지 확인

## 5. Fake/Real 전환 원칙

핵심:
- `Fake`는 입력 어댑터 이름이다.
- 상태머신/인식/오류처리 코어는 `REAL_USB`와 동일 코드 경로를 사용한다.

전환 시 바뀌는 것:
1. USB/CAN 입력 어댑터 (`FakeUsbHostManager`, `FakeCanIngestSource` -> Android 구현)
2. 테스트용 실패 시나리오 버튼 사용 여부

전환 시 그대로 유지되는 것:
1. `VehicleRecognitionCoordinator`
2. `VehicleRecognitionStateMachine`
3. fingerprint/timeout/error 처리 규칙
4. CarParams publish -> safety ready 게이트

## 6. Fake 시나리오 버튼 의미

`Fake USB` 선택 시:
0. `Run Fake Suite`:
- 6개 시나리오를 자동 순차 실행하고 PASS/FAIL 라인을 화면에 누적

1. `Happy`:
- 정상 경로. `SAFETY_READY`, `Error=NONE`

2. `FP Timeout`:
- 식별 불가 프레임만 주입. `Error=FINGERPRINT_TIMEOUT`

3. `CAN Timeout`:
- 초반 프레임 후 무수신. `Error=CAN_TIMEOUT`

4. `IF Load Fail`:
- 차량 식별 후 인터페이스 로드 실패. `Error=INTERFACE_LOAD_FAIL`

5. `Panda Fail`:
- Panda connect 실패. `Error=PANDA_CONNECT_FAIL`

6. `USB Denied`:
- USB 권한 거부. `Error=USB_PERMISSION_DENIED`

로그 전달:
1. `Copy Debug Text` 버튼 클릭
2. 복사된 텍스트를 채팅에 그대로 붙여넣기
3. 별도 Logcat 없이도 1차 진단 가능

## 7. 상세 테스트 순서 (권장)

1. 앱 실행 후 `Source mode = Fake USB` 확인
2. `Run Fake Suite` 클릭
3. `Suite status`가 `IDLE`로 돌아올 때까지 대기(약 15~25초)
4. `Suite report`에서 6개 시나리오가 모두 `PASS`인지 확인
5. `Copy Debug Text` 클릭 후 결과 텍스트를 공유
6. 실패 항목이 있으면 그 항목부터 개별 시나리오 버튼으로 재검증

## 8. 실패 시 체크

1. `PANDA_CONNECT_FAIL`:
- OTG 케이블/전원/허브 확인
- 권한 팝업 거부 여부 확인

2. `CAN_TIMEOUT`:
- 차량 CAN 트래픽 존재 확인
- 수집 인터페이스/엔드포인트 로그 점검

3. `FINGERPRINT_TIMEOUT`:
- fingerprint catalog에 해당 차종 후보 추가 필요

## 9. 현재 제약

1. `compileSdk 36`/`AGP 8.13.2` 조합 기준으로 구성됨
2. 실제 추론 파이프라인은 미연동 상태이며, G2는 `mock + real camera ingest`까지 제공
3. G2 절차는 `docs/G2_MODEL_MOCK_DEBUG.md` 참조
