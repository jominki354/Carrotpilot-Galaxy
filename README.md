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
- `g2_inference_last_failure`
4. ONNX 런타임 엔진:
- 다중 입력 tensor를 모두 주입하여 실행
- 입력 dtype(`FLOAT16/FLOAT/UINT8/INT8/INT16/INT32/INT64/BOOL`)을 스키마대로 생성
- 출력 tensor는 형 변환 가능한 타입(예: `FLOAT16`)을 우선 사용
5. `REAL_CAMERA` 모드:
- CameraX Y plane을 `ModelInputFrame`으로 추출
- 입력 스키마(예: `img`, `big_img`) 크기에 맞춰 nearest-neighbor resize 후 채널 축으로 반복 주입
- openpilot `DT_MDL(20Hz)` 방식과 유사하게 최신 프레임 기준 고정 주기 추론 루프 사용

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
3. 요약 라벨:
- `BENCH-SUMMARY`
- `bench_verdict` (기능 합격: stage/error/failure 기준)
- `t3_health_verdict`, `t3_perf_verdict`
- `bench_threshold_model_hz_min`, `bench_threshold_drop_max`, `bench_threshold_p95_ms_max`
- `t3_2min_model_hz`, `t3_2min_p95_ms`, `t3_2min_drop_perc`
- `t3_5min_model_hz`, `t3_5min_p95_ms`, `t3_5min_drop_perc`
4. 임계치 커스텀 예시:
- `powershell -ExecutionPolicy Bypass -File tools/run_g2_real_camera_auto.ps1 -MinModelHz 18 -MaxFrameDropPerc 15 -MaxP95Ms 65`

Real Camera 최신 벤치(2026-03-04, external `driving_vision.onnx`):
1. 기능 안정성:
- `bench_verdict=PASS`
- `t2_verdict=PASS`, `t3_verdict=PASS`
2. 성능 관측:
- `t3_2min_model_hz=18.4`, `p95_ms=66.66`, `drop_perc=8.0`
- `t3_5min_model_hz=17.5`, `p95_ms=68.71`, `drop_perc=12.7`
3. 해석:
- 기능/복귀/장시간 에러 없는 동작은 확보
- FPS/드롭은 개선됐고, p95 지연은 추가 최적화가 필요

현재 G3 상태(2026-03-04):
1. state machine 최소 루프 추가 (`DISABLED/PRE_ENABLED/ENABLED/SOFT_DISABLING/OVERRIDING`)
2. planner(20Hz) / control(100Hz) tick 카운터/Hz 계측 추가
3. 디버그 출력:
- `g3_enable_requested`
- `g3_stage`, `g3_error`
- `g3_lat_active`, `g3_long_active`, `g3_sendcan_allowed`
- `g3_planner_hz`, `g3_control_hz`, `g3_planner_ticks`, `g3_control_ticks`

ONNX 호환성 프로브:
1. `powershell -ExecutionPolicy Bypass -File tools/run_g2_onnx_compat_probe.ps1`
2. 판정값:
- `PASS_COMMA_ORT_EXTERNAL`
- `PASS_COMMA_ORT`
- `PASS_PROBE_FALLBACK`
- `FAIL_ORT_INIT`
3. 통과 기준:
- `g2_inference_ready=true`
- `g2_inference_failures=0`
- `g2_inference_outputs > 0`
- 스크립트가 `INJECT_FRAME` 자동 주입으로 실제 추론 호출 여부를 함께 확인

comma 모델 투입(ADB push + probe):
1. `powershell -ExecutionPolicy Bypass -File tools/push_comma_model_and_probe.ps1 -ModelPath C:\path\comma_model.onnx`
