# Carrotpilot Galaxy

Android 16 / One UI 8 기반 `single-APK` 코어 런타임으로 Carrotpilot/openpilot 핵심 흐름을 이식하는 프로젝트.

핵심 범위:
- 차량인식: Panda USB/CAN/fingerprint
- 모델 런타임: Camera/IMU/GNSS/location/calibration
- 주행 코어: planner/controller/selfdrive state machine

상세 동결안:
- `docs/STACK_FREEZE.md`
- `docs/G1_STUDIO_DEBUG.md`
- `docs/G2_MODEL_MOCK_DEBUG.md`
- `offline_porting/README.md` (실차 없는 오프라인 포팅/seed 생성)

현재 G2 추론 상태(2026-03-04):
1. ONNX 로드 우선순위:
- `external files/models/comma_model.onnx`
- `assets/models/comma_model.onnx`
- `models/mul_1.onnx` (probe fallback)
- `ONNX_PLACEHOLDER`
2. `g2_inference_backend`에 실제 로드 경로가 표기됨
3. 디버그 출력:
- `g2_inference_latency_ms_p50`
- `g2_inference_latency_ms_p95`
- `g2_inference_failures`

진행 게이트:
1. G0 아키텍처 동결
2. G1 차량인식 Bring-up
3. G2 모델 런타임 Bring-up
4. G3 주행 코어 통합
5. G4 성능/회귀 통과

실행(Windows):
1. `.\gradlew.bat :app:assembleDebug`
2. `.\gradlew.bat :app:testDebugUnitTest`

앱 실행 모드:
1. `REAL_USB`: 실제 Panda USB/CAN 입력
2. `FAKE_USB`: 동일 코어 로직에 테스트 입력 어댑터 연결
3. `G2 Model source`: `MOCK`(suite) / `REAL_CAMERA`(실프레임 ingest smoke)

자동 검증(ADB):
1. `powershell -ExecutionPolicy Bypass -File tools/run_g2_real_camera_auto.ps1`
2. 출력 라벨:
- `T2-RUNNING`
- `T2-AFTER-RESUME`
- `T2-STOPPED`
- `T3-2MIN`
- `T3-5MIN`
- `T3-STOPPED`

ONNX 호환성 프로브:
1. `powershell -ExecutionPolicy Bypass -File tools/run_g2_onnx_compat_probe.ps1`
2. 판정값:
- `PASS_COMMA_ORT_EXTERNAL`
- `PASS_COMMA_ORT`
- `PASS_PROBE_FALLBACK`
- `FAIL_ORT_INIT`

comma 모델 투입(ADB push + probe):
1. `powershell -ExecutionPolicy Bypass -File tools/push_comma_model_and_probe.ps1 -ModelPath C:\path\comma_model.onnx`
