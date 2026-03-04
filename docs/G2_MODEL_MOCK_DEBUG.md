# G2 Model Debug (Mock + Real Camera)

목표:
- 실차/카메라 없이 `G2 모델 런타임` 상태 전이와 timeout 처리를 검증
- `Run Model Suite` 원클릭으로 기준 시나리오 PASS/FAIL 확보
- `Copy Debug Text` 1회 복사로 G1+G2 상태를 함께 공유
- 필요 시 `REAL_CAMERA` 입력으로 실제 프레임 ingest 경로를 확인

## 1. 전제

1. 앱이 최신 debug 빌드로 설치되어 있어야 함
2. `Fake USB` 모드에서 테스트 수행 권장
3. G1 Fake suite는 먼저 PASS 상태가 이상적

## 2. 원클릭 테스트 순서

1. 앱 실행
2. `Run Model Suite` 클릭
3. `Model suite status`가 `RUNNING -> IDLE`로 바뀔 때까지 대기 (약 10~20초)
4. `Model suite report`에서 4개 시나리오가 모두 `PASS`인지 확인
5. `Copy Debug Text` 클릭
6. 복사 텍스트를 채팅에 붙여넣기

## 3. 시나리오 정의

1. `HAPPY_PATH`
- 기대: `STABLE / NONE`, modelHz 대략 16~24.5

2. `CAMERA_PERMISSION_DENIED`
- 기대: `ERROR / CAMERA_PERMISSION_DENIED`

3. `MODEL_TIMEOUT`
- 기대: `ERROR / MODEL_TIMEOUT`

4. `POSE_TIMEOUT`
- 기대: `ERROR / POSE_TIMEOUT`

## 4. Real Camera ingest 테스트

1. `Model source`에서 `Real Camera` 선택
2. `Start G2` 클릭
3. 카메라 권한 팝업이 뜨면 허용
4. 2~5초 대기 후 아래 확인:
- `G2 Stage`: `MODEL_STREAMING` 또는 `STABLE`
- `G2 Error`: `NONE`
- `G2 modelHz`: 0 초과
- `G2 modelFrames`: 증가 중
- `G2 inferenceBackend`: `ONNX_RUNTIME_ANDROID` (fallback 시 `ONNX_PLACEHOLDER`)
- `G2 inferenceReady`: `true`
- `G2 inferenceOutputs`: 증가 중
- `G2 inferenceLatencyP50(ms)`: 0 초과
- `G2 inferenceLatencyP95(ms)`: P50 이상
- `G2 inferenceFailures`: 0 유지
5. `Stop G2` 클릭 후 `Copy Debug Text`로 결과 공유

참고:
- `Run Model Suite`는 항상 MOCK 모드 기준이다.
- Real Camera 검증은 수동(`Start/Stop G2`)으로 확인한다.
- `Stop G2` 후에도 마지막 hz/frame 값은 유지되고, stage만 `STOPPED`로 전환된다.
- `Copy Debug Text` 버튼은 상단 고정 위치에서 항상 사용 가능하다.

## 5. 수동 디버그(고급)

1. `Show G2 Advanced` 클릭
2. 원하는 시나리오 선택
3. `Start G2` 실행
4. 상태값 확인:
- `G2 Stage`
- `G2 Error`
- `G2 modelHz`
- `G2 poseHz`
- `G2 frameDropPerc`
- `G2 inferenceBackend`
- `G2 inferenceReady`
- `G2 inferenceOutputs`
- `G2 inferenceLatencyP50(ms)`
- `G2 inferenceLatencyP95(ms)`
- `G2 inferenceFailures`

## 6. 현재 범위 제한

1. 본 문서는 `Model Mock + Real Camera ingest`를 다룸
2. 현재 ONNX Runtime은 `probe model(models/mul_1.onnx)`로 연결됨
3. livePose 입력 결합은 다음 단계
4. comma 실모델은 표준 ONNX와 다를 수 있어(커스텀 op/입출력 스키마 차이), 별도 호환 검증이 필요함

## 7. ADB 자동 실행 (수동 최소화)

아래 스크립트 1회 실행으로 `T2/T3`를 자동 수행하고, 스냅샷을 콘솔에 출력한다.

1. 명령:
```powershell
powershell -ExecutionPolicy Bypass -File tools/run_g2_real_camera_auto.ps1
```

2. 기본 동작:
- 카메라 권한 grant
- 앱 force-stop 후 재실행
- `MODEL_SOURCE_REAL_CAMERA -> RESET_G2 -> START_G2`
- T2 running / after-resume / stopped 스냅샷 수집
- T3 2분/5분/stopped 스냅샷 수집

3. 빠른 검증용(짧은 시간 파라미터):
```powershell
powershell -ExecutionPolicy Bypass -File tools/run_g2_real_camera_auto.ps1 -T3FirstWindowSeconds 3 -T3SecondWindowSeconds 3
```

## 8. Backend 전환 게이트 (QNN/SNPE)

목표:
- ORT 경로로 먼저 안정성/정확도 기준선을 고정하고, 실패 시에만 backend 전환을 진행한다.

QNN/SNPE 착수 조건(하나라도 만족 시):
1. `g2_inference_failures > 0`가 반복 발생
2. comma 실모델이 ORT에서 로드/런 불가(custom op 또는 I/O 계약 불일치)
3. 목표 지연 예산(p95) 미달이 장시간 테스트에서 지속

착수 전 준비:
1. 동일 입력 seed로 ORT vs 후보 backend 출력 비교 하네스
2. 모델 I/O 스키마 고정 문서(입력 shape/type, 출력 tensor 의미)
3. fallback 정책 유지(실패 시 즉시 placeholder 복귀)
