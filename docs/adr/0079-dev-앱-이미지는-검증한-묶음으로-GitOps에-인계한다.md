# 0079. dev 앱 이미지는 검증한 묶음으로 GitOps에 인계한다.

## Status

Accepted — PROJ-286의 backend 이미지 공급·Git 발행 설계다. Image Updater 도입을 철회하고 backend workflow가
이미지 values 승격과 조건부 자동 rollback을 맡도록 개정한다. 레포 간 계약은
[허브 ADR 0035](https://github.com/soma-376/docs/blob/docs/PROJ-311-gitops-delivery-contract/adr/0035-dev-gitops-delivery.md)과
[generated values 동기화 계약](https://github.com/soma-376/docs/blob/docs/PROJ-311-gitops-delivery-contract/contracts/generated-values-sync.md)이 소유한다.
PROJ-286의 미병합 초안을 현재 develop에 가져오며 이미 사용 중인 0076~0078과의 충돌을 피하려고 0079를 사용한다.
PROJ-311은 backend 공급·승격과 Argo 상태 기반 판정을 구현한다. PostSync 검증 Job·실환경 호환성 검사를 제거하고 기능 미탐지와 migration 후 비호환 복구 위험을 수용한다. 일반 infra 설정 publisher는 후속이다.
ADR 0005·0009·0016의 dev 이미지 공급·배포 관련 서술만 부분 대체하며 설치·스키마·조립 규칙은 유지한다.
문서 채택은 workflow·Git 발행·Argo CD 구현 또는 실제 dev 인계 완료를 뜻하지 않는다.

## Context

PROJ-311 이전 `.github/workflows/deploy_dev.yml`은 enrollment-api·telemetry-ingest·dashboard-api의
`linux/arm64` SHA 이미지를 게시한다. telemetry-gateway는 ECR 저장소 설정이 있을 때만 게시한다.
설정된 이미지의 게시가 끝난 뒤 앞 세 앱의 `dev` 태그를 각 build digest로 순차 갱신하며,
중간 실패 시 이미 바뀐 태그는 남고 Gateway에는 `dev` 태그를 만들지 않는다.
당시 workflow에는 네 앱 완료 manifest·GitOps 승격·자동 rollback이 없었으며 확인한 digest로 수동 배포했다.
PROJ-311의 코드 경로는 완료 manifest와 별도 승격 workflow를 제공한다. 실제 PROJ-310 인계와 publisher/CAS 수락 전에는 비활성이다.

dev 공개 수집 경로는 Gateway → Strimzi Kafka → ingest다. 앱별 최신 태그나 같은 소스 SHA만으로는
네 앱이 같은 실행에서 검증됐는지 알 수 없고, 같은 SHA의 재빌드도 digest가 달라질 수 있다.
기존 설계의 Image Updater는 후보를 발견한 뒤 별도 검증·승격을 요구했다. 이미지 생산 workflow가
이미 정확한 build digest를 알고 있으므로, 완료한 산출물을 직접 Git 발행 workflow에 넘길 수 있다.
다만 backend 이미지 발행과 infra 설정 발행이 같은 private 저장소를 쓰므로 두 실행의 경쟁을 제어해야 한다.
Git 선택의 원자성은 앱 교체·Flyway·Kafka backlog·분석 저장소의 호환성을 대신하지 않는다. 공유 dev 운영을 단순화하기 위해 자동 배포는 Argo 상태만 판정하며 실제 기능·호환성 검증은 하지 않는다.

## Decision

### 1. 네 앱의 완료 묶음과 원본 실행을 보존한다

- enrollment-api·telemetry-ingest·telemetry-gateway·dashboard-api를 같은 backend 전체 SHA,
  같은 이미지 게시 workflow run과 attempt에서 검증·빌드·게시한다. 네 이미지 모두 `linux/arm64`이며,
  Gateway 설정 누락·생략·실패 또는 다른 실행의 이미지 보충은 묶음 실패다. retention-worker·prod·frontend·perf는 제외한다.
- 필수 검증과 네 이미지 게시가 모두 성공한 뒤 각 build output digest로 완료 manifest를 만든다.
  예상 ECR repository의 존재·ARM64 manifest, enrollment의 고정 CLI·GUI 릴리스와 `SHA256SUMS` provenance를 검증한다.
  나중에 SHA·`dev` 태그를 다시 조회한 값으로 build digest를 대체하지 않는다.
- manifest schema와 `B`는 허브 계약을 따른다. `B`는 원본 UTF-8 바이트의 SHA-256이며 자기 hash를
  manifest 내부에 넣지 않는다. 원본 바이트·출처·실행 증거·이미지는 소비와 복구에 필요한 동안 보존한다.
- 원본 이미지 게시 workflow가 최종 성공한 뒤 **별도 backend 승격 workflow**가 이를 소비한다.
  manifest의 성공 표기만 믿지 않고 허용 workflow/ref·원본 SHA/run/attempt·최종 성공과 이미지 대응을 검증한다.
  승격·rollback 실행의 식별자는 별도 증거로 남기며 `B`의 생산 run/attempt를 덮어쓰지 않는다.
- 같은 B의 재소비는 멱등이다. 같은 SHA여도 digest·attempt가 달라 새 B가 되면 다시 전체 검증한다.
  source와 네 이미지가 같은 정상 재실행은 provenance를 갱신할 수 있지만 불필요한 Pod 재시작은 요구하지 않는다.
- Image Updater·`candidate/dev`·태그 발견 단계를 사용하지 않는다. 기존 호환용 `dev` 태그는 배포 선택의
  근거가 아니며 Gateway에 tracking tag를 추가하는 요구도 철회한다.

### 2. backend는 이미지·복구 Git 발행을, infra는 설정 Git 발행을 맡는다

- backend publisher는 검증한 B의 네 앱 `image.repository`·`image.digest`와 대응 metadata를 발행한다.
  현재 선택된 infra commit C와 비이미지 값을 보존하며 임의 runtime·replica·ServiceAccount·키/TLS 참조를 바꾸지 않는다.
  현재 chart의 `repository@digest` 렌더를 유지한다. `image.ref` 전환과 별도 image overlay는 도입하지 않는다.
- infra publisher는 chart·추적 runtime 입력·생성기와 비이미지 generated를 소유한다. 설정을 재생성할 때
  현재 선택 B의 네 이미지를 유지한다. backend와 infra는 별도 publisher이며 공통 저장소의 제어 계약을 함께 따른다.
  사람이 generated를 임의 편집하거나 private 사본을 infra 원본으로 역동기화하지 않는다.
- `C`는 infra 전체 commit, `V`는 generated·원본 manifest 사본의 불변 private snapshot commit,
  `P`는 C·V·B를 선택하는 별도 desired commit이다. 검증된 V를 먼저 append하되 기존 Application pin을 유지하고,
  이후 P 하나에서 `dev/desired.yaml`과 영향을 받는 Application의 C/V 참조를 함께 바꾼다.
  자기 commit SHA를 자기 파일에 넣지 않는다. V 게시만으로 배포 성공을 선언하지 않는다.
- backend workflow는 private Git에 제한된 변경을 기록하며 Kubernetes·Argo API에 쓰지 않는다.
  Argo CD는 Git을 읽어 적용한다. infra는 chart·생성·검증 정책과 Argo 운영을 계속 소유한다.
  정상 이미지는 검증 뒤 자동 승격하며 매번 별도 사람 승인을 추가하지 않는다.
- 고정 C의 전체 파일 목록·허용 image 필드·렌더 결과·비밀 원문 부재와 immutable 이미지의 packaged contract를 검증한다. 실환경 runtime/schema/key 호환성은 검사하지 않는다.
  재생성 중 변경이나 출처 불일치를 마지막 writer의 값으로 덮지 않고 최신 기준에서 다시 검증한다.

### 3. 두 publisher는 Git CAS 잠금을 배포·복구 종료까지 유지한다

- 공통 제어 파일 `dev/delivery-state.json`은 허브 계약의 `schemaVersion: 1`과
  `idle`·`deploying`·`rolling_back`·`blocked` 상태를 따른다. owner의 repository/workflow/run/attempt,
  fencing generation, expected desired P, 작업 종류, last-known-good 참조, 실패 B와 앱 key별 repository/digest
  전체 조합, 재개 fence를 기록한다. 이 ADR은 공통 schema의 별도 사본을 만들지 않는다.
- backend 이미지/rollback과 infra 설정 발행은 같은 private main HEAD를 expected ref로 확인한
  정상 fast-forward compare-and-swap으로 잠금을 얻는다. 저장소별 GitHub concurrency는 이 공유 잠금을 대체하지 않는다.
  owner와 generation을 각 전이에서 확인하고 force push하지 않는다. 상태 기록 commit은 배포 선택 P와 구별한다.
- 잠금은 V/P 게시뿐 아니라 현재 Argo 상태 판정과 가능한 한 번의 rollback까지 유지한다.
  Argo 상태가 성공이면 lastKnownGood을 검증된 C/V/B/P로 갱신하고 idle로 잠금을 해제한다.
  충돌은 최신 main·desired·source·C·B를 다시 읽어 재검증하며 다른 owner를 덮지 않는다.
  V 게시 뒤 P 실패나 응답 유실은 실제 선택을 조회하여 중복 선택과 혼합 조합을 막는다.
- owner 취소·runner 소실 등으로 진행을 입증하지 못하면 자동 lease 만료·잠금 탈취 없이 `blocked`로 처리한다.
  소유권을 잃은 이전 owner는 추가 Git 쓰기를 중단한다. 상태가 손상·부재·미지원이면 idle로 가정하지 않는다.
  기록 갱신조차 불가능하면 기존 잠금도 닫힌 상태로 유지한다. 운영자가 원인과 실제 상태를 대조해 명시적으로 복구한다.
  잠금 owner가 살아서 관측한 rollout 실패와 owner 자체의 소실을 같은 실패 경로로 다루지 않는다.

### 4. 현재 Argo 상태를 읽기 전용으로 판정한다

- 플랫폼·runtime·데이터 준비 뒤 enrollment → ingest → Gateway → dashboard 순서를 유지한다. Flyway는
  기존 앱 기동 경로에 남기며 PostSync 검증 Job을 만들지 않는다.
- infra의 전용 observer는 root·18개 child Application만 읽는다. 현재 spec·comparedTo·resolved C/V,
  Synced·Healthy·진행 중/현재 revision 실패 operation 부재를 성공 기준으로 삼는다. root의
  resolved revision Git 이력에 현재 P가 포함돼야 한다. 이전 revision의 Healthy를 재사용하지 않는다.
- Job UID·실제 Pod imageID·Flyway 이력·인증·로그인·수집·Kafka commit·S3/ClickHouse 적재를 검사하지 않는다.
  Job·probe·DB reader·Prometheus/S3/Secret 조회 권한과 실환경 fingerprint를 제거한다. immutable 이미지의
  packaged contract·ARM64·출처 검증은 생산·승격 경로에 유지한다.
- 조회 장애·stale 상태·진행 중 operation·timeout은 unknown/blocked다. 현재 목표 C/V와 완료 operation의
  revision이 일치하는 Failed/Error만 확정 실패이며 root와 모든 child의 진행 중 operation이 없어야 한다.
  Argo 정상 상태가 실제 기능 성공을 보장하지 못하는 위험을 수용한다. 증거는 정제된 revision·UID·시각·결과다.

### 5. Argo 확정 실패만 같은 owner가 한 번 자동 rollback한다

- 이미지 승격 직전에 마지막 Argo 정상 B를 고정한다. 현재 revision의 Argo 확정 실패이면 같은 owner가
  실패 B·이미지 벡터·source와 fence를 먼저 CAS로 기록하고 이전 네 이미지로 한 번 복구한다.
- 현재 C와 비이미지 바이트를 유지한 새 V/P를 게시한다. 실제 schema/migration·runtime·키·TLS·Kafka backlog
  호환성은 재조회하거나 비교하지 않는다. 이미 적용된 migration과 이전 코드가 비호환일 수 있는 위험을 수용한다.
- 일반 rollback은 Argo auto sync/self-heal을 유지하며 backend가 Kubernetes·Argo에 직접 쓰지 않는다.
  DB downgrade·PVC·Kafka offset 복원·과거 values 전체 revert는 하지 않는다.
- 정상 B 부재·config 실패·unknown·owner 소실·복구 실패는 blocked다. 복구 revision도 §4의 Argo 성공 기준을
  통과해야 lastKnownGood을 갱신하고 잠금을 해제한다. 두 번째 자동 rollback은 하지 않는다.
- offset reset·키/인증서 교체·데이터 작업은 infra의 별도 maintenance 절차를 따른다.

### 6. 복구 성공 뒤에는 실패 이후의 새 정상 묶음만 자동 재개한다

- 복구 desired·현재 revision·Argo 상태가 성공이면 복구 조합을 lastKnownGood으로 기록한 뒤 잠금을 해제한다.
  실패 이력과 resume fence를 보존하며 후속 실패로 이전 거부 항목을 덮어쓰지 않는다. 실패 B와 정확히 같은 앱 key별
  repository/digest 전체 조합은 B·SHA를 새로 포장해도 다시 승격하지 않는다.
- 자동 재개 후보는 허용 `develop` 계보에서 실패 source의 strict descendant이며,
  **원본 이미지 게시 run의 `created_at`이 durable CAS commit으로 기록한 실패 fence 이후**인
  최종 성공 묶음이어야 한다. attempt 시작·run 번호·완료 시각만으로 판단하지 않는다.
  실패 전에 생성된 대기/진행 run, 그 run의 재시도, 같은 source의 새 attempt는 재개 조건을 충족하지 않는다.
- rollback 중 잠금 때문에 대기한 후보는 해제 시 다시 조회·평가한다. 이벤트 한 번을 놓쳤다는 이유로
  허용된 새 후보가 영구히 멈추지 않도록 한다. 실패·unknown·설정 실패로 `blocked`인 상태는 새 backend 이미지가 우회하지 못한다.
  자동 복구 성공과 명시적으로 확인한 운영 복구만 재개 판단의 출발점이다.

### 7. 구현·인계 전 운영과 호환성 경계를 유지한다

- 네 이미지 묶음의 Git 선택이 원자적이어도 앱 교체에는 구·신 버전 공존과 중단이 있다.
  DB·JWT/keyring·runtime, Gateway envelope·ingest backlog, ClickHouse·dashboard 계약의 호환성을 유지한다.
  하위 호환성을 깨는 변경은 작성자가 배포 전 검토에서 분리하고 별도 전환 절차를 따른다. 자동 승격기는
  이 호환성을 실환경에서 검사하지 않으며 이전 이미지로 복구할 때도 같은 한계가 적용된다.
  [ADR 0074](0074-producer-버전-대신-이벤트-계약으로-정규화한다.md)의 이벤트 계약도 유지한다.
- manifest·두 publisher·읽기 전용 상태 판정·자동 복구·소유권 인계 검증이 끝나기 전에는
  [EKS 수동 배포 런북](../../../infra/docs/runbooks/dev-eks-manual-deployment.md)을 따른다.
  완료 B가 없는 기존 이미지는 허브 계약의 고정 C와 별도 handoff snapshot H로 수동 인수하며 B로 위장하지 않는다.
  인계 후 같은 release를 수동 Helm과 Argo가 동시에 변경하지 않는다.

같은 manifest의 새 V나 main 상태-only commit에서는 새 sync가 없을 수 있다. 성공은 현재 spec·comparedTo·
resolved revision과 Synced·Healthy·진행 중/현재 실패 operation 부재로 판정한다. operationState 부재나 과거
terminal operation은 성공을 막지 않는다. 현재 revision에 귀속된 완료 Failed/Error만 확정 실패로 우선한다.
root는 P ancestry와 해당 resolved revision의 desired/Application 파일이 현재 선택과 같은지도 확인한다.

## Alternatives Considered

### A. Image Updater로 태그를 발견한 뒤 묶음을 승격한다

- 장점: registry 변경을 컨트롤러가 발견한다.
- 단점: 생산자가 아는 digest를 다시 발견하고 candidate·tracking tag·추가 운영 상태를 관리해야 한다.
- 탈락 이유: 완료 workflow의 직접 인계로 같은 묶음 검증을 수행할 수 있어 도입을 철회한다.

### B. backend와 infra 발행을 단일 workflow로 합친다

- 장점: 실행 직렬화가 단순하다.
- 단점: 이미지 공급과 infra 설정 발행의 실행 책임을 한곳으로 옮긴다.
- 탈락 이유: 별도 publisher를 유지하고 공유 Git CAS 잠금으로 경쟁을 제어한다.

### C. backend가 Kubernetes·Argo API를 직접 변경한다

- 장점: CI에서 즉시 rollout을 시작할 수 있다.
- 단점: Git desired와 실제 적용 경로가 갈라지고 클러스터 쓰기 권한이 backend에 추가된다.
- 탈락 이유: backend는 Git 선택을 기록하고 실제 적용·drift 복구는 Argo가 맡는다.

### D. 실패하면 과거 values 전체를 자동 revert하거나 모든 실패를 자동 복구한다

- 장점: 구현과 조작이 짧다.
- 단점: 현재 schema·키·TLS와 맞지 않는 과거 설정을 복원하거나 관측 불능 상태에서 잘못된 복구를 수행한다.
- 탈락 이유: 현재 C·비이미지 설정은 유지하고 현재 Argo 확정 실패에서 마지막 정상 이미지만 한 번 복구한다. 실환경 호환성 위험은 수용한다.

## Consequences/Tradeoffs

### Positive

- 이미지 생산 결과를 직접 인계하며 Updater·candidate·`image.ref` 전환 없이 기존 digest 입력을 유지한다.
- 네 앱 출처와 실제 C/V/B/P 조합, 실패·복구·재개의 근거를 Git과 관측 증거로 추적한다.
- backend와 infra의 발행 책임을 나누고 Kubernetes 적용·스키마·키·데이터 책임은 유지한다.

### Negative

- backend에 private Git의 제한된 쓰기 권한과 배포 상태를 읽는 경로가 필요하다.
- 두 publisher가 상태 전이·CAS·증거 보존·잠금 복구를 함께 구현해야 한다. owner 소실은 운영자 복구가 필요하다.
- 네 앱 중 하나의 실패가 묶음을 막으며, 상태 불명·설정 실패는 자동 rollback하지 않는다. Argo Healthy여도 기능 장애가 남을 수 있고 migration 이후 이전 이미지 복구가 비호환으로 실패하거나 장애를 악화시킬 수 있다.
- 엄격한 재개 fence는 실패 전에 시작한 새 source 빌드도 제외하므로 새 원본 게시 run이 필요할 수 있다.

## Follow-up

- [운영 안내](../dev-gitops-image-delivery.md)의 비활성 기본값을 유지하고 검토된 infra tooling commit을 고정한다.
- PROJ-310 실제 인계, GitHub Environment·전용 역할·publisher App·private 활성화 구성을 운영 수락한다.
- 일반 infra 설정 publisher는 공통 CAS를 재사용해 후속으로 구현한다. 기존 PROJ-279 writer의 종료가 선행한다.
- 순차 배포·Argo 상태 증거·한 번의 Argo 기반 rollback·실패 fence·잠금 해제 후 재평가를 실제 환경에서 수락한다. 로컬 테스트 통과를 운영 인수로 간주하지 않는다.

## Acceptance Criteria

- 네 SHA/run/attempt/ARM64/repository/digest 및 CLI·GUI 출처가 맞고 생산 run 최종 성공 뒤에만 승격한다.
- Gateway 누락·부분 게시·혼합 attempt·출처 누락은 새 P를 만들지 않는다. 같은 SHA의 새 B는 재검증한다.
- 이미지 발행은 C·비이미지 값을, 설정 재생성은 현재 B를 보존한다. 동시 요청은 같은 CAS 잠금에서 한쪽만 진행한다.
- 상태 commit과 P를 구분하고 V 이후 P 실패·응답 유실·stale expected revision을 최신 상태 조회로 복구한다.
- 이전 revision의 Healthy, 관측 불능·진행 상태 불명은 자동 rollback을 일으키지 않는다. owner 소실 시 잠금을 자동 탈취하지 않는다.
- 현재 revision의 Argo 확정 실패에서 사전 last-known-good B로 한 번 복구하고, 정상 B 부재·설정 실패·복구 실패는 blocked다. 실환경 호환성 검사는 하지 않는다.
- 일반 rollback에서 Argo 자동 적용을 유지하며 DB/PVC/offset·현재 키/TLS를 보존한다.
- 실패 B·같은 전체 이미지 조합·이전 run/재시도는 재승격하지 않는다. 새 source·실패 이후 원본 run만 복구 후 재개한다.
- rollback 중 도착한 허용 후보를 해제 시 재조회하고, blocked를 새로운 backend run으로 우회하지 못한다.
- 문서 검증은 링크·ADR 번호/인덱스·대체 관계·허브와의 소유권/실패/복구 정합성이다. 위 실행 사례는 실제 활성화 수락 조건이며 로컬 테스트와 구분한다.

## References

- [허브 ADR 0035](https://github.com/soma-376/docs/blob/docs/PROJ-311-gitops-delivery-contract/adr/0035-dev-gitops-delivery.md) · [generated values 동기화 계약](https://github.com/soma-376/docs/blob/docs/PROJ-311-gitops-delivery-contract/contracts/generated-values-sync.md)
- [infra ADR 0056](../../../infra/docs/adr/0056-dev-argocd-gitops-operations.md) — Argo 운영·공유 잠금·판정과 복구
- [enrollment 서버 명세](../enrollment-server-spec.md) §9.4·9.6 · [Kafka 운영](../telemetry-kafka-operations.md)
- [ADR 0070](0070-Kafka-mTLS-접속은-공통-설정에서-PKCS12-파일로-조립한다.md) — TLS 파일 공급·재시작 계약
- `.github/workflows/deploy_dev.yml` — 네 이미지 생산·패키지 계약 검사·완료 artifact
- `.github/workflows/promote_dev.yml` — default branch의 별도 승격·재평가와 보호된 dev-gitops Environment
- [backend 운영 안내](../dev-gitops-image-delivery.md) — 원본 B·공개 artifact 경계·고정 실행기·비활성 기본값
