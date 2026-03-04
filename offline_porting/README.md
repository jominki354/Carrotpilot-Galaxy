# Offline Porting Workspace

목적:
- 실차 없이 openpilot/opendbc 데이터(차종 정의, FW fingerprint, 공개 테스트 route)를 가져와
  `Carrotpilot_galaxy`의 차량 인식 준비 데이터를 보강한다.

## 왜 별도 폴더인가

1. 앱 코드(`app/`)와 오프라인 분석 스크립트를 분리해 빌드 리스크를 줄인다.
2. openpilot 기준 데이터 추출/가공 결과(JSON)를 이력 관리하기 쉽다.
3. 실차 확보 전까지 재현 가능한 반복 작업(추출 -> 비교 -> 반영) 루프를 고정할 수 있다.

## 현재 스크립트

`scripts/build_hyundai_openpilot_snapshot.py`

기능:
1. `opendbc_repo/opendbc/car/hyundai/values.py`에서 현대 플랫폼 모델 목록 추출
2. `opendbc_repo/opendbc/car/hyundai/fingerprints.py`에서 FW 모델 목록 추출
3. `opendbc_repo/opendbc/car/tests/routes.py`에서 현대 공개 테스트 route 목록 추출
4. CASPER/CASPER_EV 커버 여부 요약

`scripts/build_hyundai_route_seeds.py`

기능:
1. 공개 openpilotci route 로그(`rlog/qlog`) 다운로드
2. CAN 메시지에서 `(address, length)` 시그니처 추출
3. 모델별 seed 시그니처 JSON 생성
4. `CASPER_EV`는 public route/FW 부재 시 임시 fallback로 명시

`scripts/generate_fingerprint_catalog_kotlin.py`

기능:
1. `hyundai_seed_signatures.json`에서 Kotlin 카탈로그 소스 자동 생성
2. 출력 파일: `app/src/main/java/io/carrotpilot/galaxy/vehicle/FingerprintCatalogGenerated.kt`

## 실행

프로젝트 루트(`Carrotpilot_galaxy`)에서:

```powershell
python .\offline_porting\scripts\build_hyundai_openpilot_snapshot.py `
  --openpilot-root "e:\Carrotpilot_Galaxy\Carrotpilot" `
  --output ".\offline_porting\data\hyundai_openpilot_snapshot.json"
```

```powershell
# openpilot uv 환경에서 실행 권장 (capnp 포함)
uv run python ..\Carrotpilot_galaxy\offline_porting\scripts\build_hyundai_route_seeds.py `
  --snapshot "..\Carrotpilot_galaxy\offline_porting\data\hyundai_openpilot_snapshot.json" `
  --openpilot-root "." `
  --output "..\Carrotpilot_galaxy\offline_porting\data\hyundai_seed_signatures.json"
```

```powershell
python .\offline_porting\scripts\generate_fingerprint_catalog_kotlin.py `
  --seed-json ".\offline_porting\data\hyundai_seed_signatures.json" `
  --output ".\app\src\main\java\io\carrotpilot\galaxy\vehicle\FingerprintCatalogGenerated.kt"
```

## 결과 파일

`offline_porting/data/hyundai_openpilot_snapshot.json`
`offline_porting/data/hyundai_seed_signatures.json`

주요 필드:
1. `platform_models`: opendbc에 정의된 현대 플랫폼
2. `fw_models`: 현대 `FW_VERSIONS` 존재 모델
3. `routes_by_model`: 공개 테스트 route가 있는 모델
4. `casper_summary`: `HYUNDAI_CASPER_EV`의 실제 커버 상태

## 현재 한계

1. 이 스냅샷은 텍스트 파싱 기반이며, capnp/opendbc import 없이 동작하도록 설계됨
2. `FW_VERSIONS`/route가 없는 차종은 실차 로그 또는 별도 내부 데이터가 필요
3. route seed 생성은 로그 다운로드+파싱 때문에 초회 실행이 오래 걸릴 수 있으며, `offline_porting/cache/routes` 캐시를 재사용하면 이후 빨라진다
