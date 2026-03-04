# Stack Freeze (v0.1, 2026-03-03)

## 목적

구현 전에 기술 스택/구조/실행 순서를 고정해 범위 확장을 방지한다.

## 1. 언어/런타임 동결

1. App/Orchestration: `Kotlin`
2. UI: `Jetpack Compose` + `Material 3`
3. Async/State: `Coroutines`, `StateFlow`
4. Low-level path(필요 시): `C++(NDK) + JNI`
5. Inference 1차: `ONNX Runtime Mobile`
6. Inference 2차 후보: `TFLite + NNAPI`

## 2. 구조 동결

선택:
- `Single APK + Internal Modules + Event-driven runtime`

모듈:
1. `app`: Activity/permissions/lifecycle/FGS
2. `core-runtime`: scheduler, health monitor, state machine
3. `core-vehicle`: USB/CAN/fingerprint/CarParams
4. `core-model`: preprocess/inference/parser
5. `core-driving`: planner/controller/selfdrive states
6. `feature-drive`: debug shell UI

## 3. 안전 원칙 동결

1. Panda safety 우회 금지
2. safety ready 전 TX 금지
3. stale/disconnect 시 즉시 disengage
4. 실차 전 Bench/Replay 게이트 필수

## 4. 실행 순서 동결

1. `G0` Architecture Freeze
- 요구사항/ADR/게이트 확정

2. `G1` Vehicle Bring-up
- USB attach/permission, CAN RX, fingerprint, CarParams

3. `G2` Model Bring-up
- camera ingest, model output, location/calibration chain

4. `G3` Driving Integration
- planner/controller/state machine, safety-gated sendcan

5. `G4` Qualification
- loop latency, thermal, lifecycle recovery, regression

## 5. 주기/계약 동결

1. Control tick: `100Hz`
2. Planning/Model tick: `20Hz`
3. 핵심 채널: `can`, `carState`, `carParams`, `modelV2`, `livePose`, `liveCalibration`, `carControl`, `sendcan`

## 6. Android 16 / One UI 8 대응 동결

1. 대화면 orientation 강제에 의존하지 않고 adaptive layout 사용
2. 카메라/위치/USB는 FGS + 권한 상태를 런타임 상태로 명시 관리
3. 백그라운드 복귀 시 파이프라인 재초기화 순서 고정:
- `USB/CAN -> Camera -> Model -> Location -> Driving`
4. 배터리 최적화/절전 정책으로 인한 중단을 장애 시나리오로 포함

## 7. 변경 규칙

아래 변경은 `stack freeze` 재승인이 필요:
1. 주기 변경(100Hz/20Hz)
2. 안전 경계 변경
3. 추론 엔진 1차 선택 변경
4. 모듈 경계 변경

