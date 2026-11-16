# 0081. dev 앱 이미지는 검증한 묶음으로 GitOps에 인계한다

## Status

Accepted — PROJ-334에서 [허브 ADR 0036](https://github.com/soma-376/docs/blob/main/adr/0036-simple-dev-git-publication-and-argocd-sync.md)과
[generated values 계약](https://github.com/soma-376/docs/blob/main/contracts/generated-values-sync.md)의 간단한 Git 게시 방식으로 개정한다.
PROJ-311의 장기 CAS 잠금·Argo observer·자동 rollback·실패 fence·readiness 수락 조건은 대체한다.
네 ARM64 이미지 완료 manifest와 출처·packaged contract 검증, 현재 infra 및 비이미지 보존은 유지한다.
실제 게시 활성화와 root 연결은 PROJ-335의 별도 운영 검증이며 문서 채택으로 완료를 주장하지 않는다.

## Context

기존 dev 배포는 세 앱의 mutable `dev` 태그를 순차 갱신했고 Gateway 이미지는 선택 입력이었다.
PROJ-311은 네 앱의 원본 완료 묶음과 별도 GitOps 게시 workflow를 추가했다. 당시 설계는 게시부터
Argo 관측·자동 이미지 복구까지 같은 owner가 장기 잠금을 유지하도록 했다.

공유 dev에서는 이 배포 상태 시스템의 초기화·운영 수락 비용이 크다. PROJ-334·335는 Git 게시까지만
검증·충돌 처리를 맡고 실제 적용·상태 복구는 Argo CD가 하도록 역할을 줄인다. 이전 배포가 끝나기 전에
새 Git 선택이 게시될 수 있으며 중간 버전을 건너뛰고 최신 상태로 수렴하는 것을 수용한다.

## Decision

### 1. 네 앱의 완료 묶음과 원본 실행을 검증한다

- enrollment-api·telemetry-ingest·telemetry-gateway·dashboard-api를 같은 backend 전체 SHA와
  같은 원본 run/attempt에서 생산한다. 모두 `linux/arm64`이며 하나라도 누락·실패하면 완료 묶음을 만들지 않는다.
- 기존 완료 manifest schemaVersion 1을 유지한다. B는 manifest 원본 UTF-8 바이트의 SHA-256이며
  자기 hash를 manifest 내부에 넣거나 재직렬화한 바이트로 원본을 대체하지 않는다.
- 최종 성공한 원본 workflow를 별도 게시 workflow가 API로 대조한다. repository·workflow ID/path·develop
  계보·event·SHA·run/attempt·전체 성공과 필수 job 성공, 정확한 artifact ID·이름·만료·ZIP digest를 검증한다.
- immutable 이미지에서 ARM64 manifest·label과 packaged deployment contract를 재검증한다.
  이미지의 코드나 artifact의 스크립트를 실행하지 않는다. 검사 container는 start하지 않고 제거한다.
- 기존 호환 `dev` 태그는 유지하지만 배포 선택에 사용하지 않는다. Image Updater·tracking tag·image overlay는 추가하지 않는다.

### 2. backend는 이미지만, infra는 설정만 Git에 게시한다

- backend 게시기는 현재 선택 V의 네 앱 image digest와 원본 `bundle.json`·`producer.json`을 바꾼다.
  현재 infra 코드 C·repository·generated 비이미지 바이트·Application 구조와 syncPolicy를 보존한다.
- infra 게시기는 공식 profile/runtime과 검증한 AWS output·Strimzi metadata로 설정을 재생성하며
  현재 선택 이미지·bundle·producer를 보존한다. 두 게시기는 허브의 같은 작은 게시 모듈을 사용한다.
- snapshot V와 이를 가리키는 desired/Application 선택 P를 로컬에서 만든 뒤 P를 한 번 push한다.
  `dev/desired.yaml`은 schemaVersion 2와 C/V/B만 보관한다. 자기 SHA를 자기 파일에 넣지 않는다.
- 현재 main HEAD와 변경 소유권을 검증하고, 충돌하면 최신 main에서 반대 영역을 보존하여 최대 3회
  재생성한다. force push·자동 rebase·장기 잠금·`delivery-state.json`은 사용하지 않는다.
- backend 게시 직전 최신 성공 producer를 다시 확인한다. 현재 선택보다 오래된 실행이나 develop
  계보 밖 source는 거부하며 수동 실행도 이를 우회하지 않는다.

### 3. Git 게시와 실제 배포 결과를 구분한다

- `promote_dev.yml`은 보호된 default `develop`에서만 실행한다. 성공한 생산 `workflow_run`과
  `workflow_dispatch`를 사용하며 schedule은 두지 않는다. 동시 실행은 cancel-in-progress false로 직렬화한다.
- 게시 코드는 workflow 자신의 검토된 SHA에서 실행하고 공통 infra tooling은 별도 전체 SHA로 고정한다.
- Git 게시 결과는 `published`·`noop`·`superseded`·실패다. `published`는 Argo Synced/Healthy나
  로그인·조회·수집·저장 성공을 뜻하지 않는다. 게시기는 Argo 완료를 기다리지 않는다.
- backend에는 Kubernetes·Argo 쓰기나 observer 접근을 추가하지 않는다. 기존 ECR 읽기 역할과
  별도의 private publisher App을 사용한다. Git reader의 키와 이미지 push 역할을 재사용하지 않는다.
- root와 하위 Application은 PROJ-335에서 auto sync/self-heal로 연결한다. prune·allowEmpty·삭제
  finalizer는 끄고 기존 namespace·releaseName·PVC·CMP·비교 설정을 보존한다.

### 4. 배포 실패는 Argo 상태를 확인하고 수동으로 복구한다

- 자동 rollback·lastKnownGood·실패 이미지 차단·resume fence·전역 blocked 상태는 만들지 않는다.
  이전 rollout 실패가 다음 정상 Git 게시를 막지 않는다.
- 복구가 필요하면 게시를 중지하고 운영자가 검토한 이전 또는 수정된 Git 선언을 선택한다.
  DB downgrade·PVC 복원·Kafka offset reset을 자동으로 수행하지 않는다.
- 유지보수 때 Git에서 대상 하위 Application의 자동화를 끄고 root 반영과 진행 operation 종료를 확인한다.
  게시기는 그 syncPolicy를 보존하므로 다음 이미지 게시가 중지 설정을 되돌리지 않는다.

## Alternatives Considered

- 기존 장기 CAS·observer·자동 rollback 유지: 배포 상태와 재개를 자동 추적하지만 초기화·운영 비용이 커 제외한다.
- mutable tag 또는 Image Updater: 원본 생산이 이미 아는 digest를 다시 찾을 이유가 없어 제외한다.
- backend의 직접 Kubernetes 변경: Git 선언과 실제 상태의 책임을 분리하기 위해 제외한다.

## Consequences/Tradeoffs

### Positive

네 앱의 출처와 C/V/B 선택은 보존하면서 별도 배포 상태 시스템을 제거한다. Git 게시의 짧은 충돌 처리는
두 repo writer를 조정하고, root·하위 Application의 적용과 drift 복구는 Argo CD가 맡는다.

### Negative

중간 선택이 실제로 배포되지 않을 수 있다. Git 게시 성공 뒤 Argo 배포가 실패하거나 기능 장애가 남을 수 있으며
자동 이미지 복구는 하지 않는다. 운영자가 Argo 상태와 기능을 확인하고 복구해야 한다. schema·키·TLS 변경과
이전 이미지의 호환성은 별도 검토 대상이다.

## Acceptance Criteria

원본 manifest·artifact·ARM64·packaged contract 검증과 현재 C·비이미지 보존을 통과해야 한다.
기본 develop 실행만 허용하고, schedule·observer·Kubernetes 쓰기·자동 rollback을 호출하지 않아야 한다.
Git 충돌·no-op·오래된 producer·응답 유실은 공통 게시 테스트로 검증하며 실제 root·auto sync는 PROJ-335에서 확인한다.

## References

- [허브 ADR 0036](https://github.com/soma-376/docs/blob/main/adr/0036-simple-dev-git-publication-and-argocd-sync.md)
- [generated values 계약](https://github.com/soma-376/docs/blob/main/contracts/generated-values-sync.md)
- [운영 안내](../dev-gitops-image-delivery.md)
