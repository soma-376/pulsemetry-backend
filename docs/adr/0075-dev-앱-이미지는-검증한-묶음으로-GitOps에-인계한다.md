# 0075. dev 앱 이미지는 검증한 묶음으로 GitOps에 인계한다.

## Status

Accepted — PROJ-286의 backend 이미지 공급 설계다. 레포 간 인계는
[허브 ADR 0030](../../../docs/adr/0030-dev-gitops-delivery.md)과
[generated values 동기화 계약](../../../docs/contracts/generated-values-sync.md)이 소유한다.
ADR 0005·0009·0016의 dev 이미지 공급·배포 관련 서술만 부분 대체하며, 설치·스키마·조립 규칙은 유지한다.
이 채택은 workflow·승격 경로·Argo CD의 구현 또는 dev 인계 완료를 뜻하지 않는다.

## Context

현재 `.github/workflows/deploy_dev.yml`은 enrollment-api·telemetry-ingest·dashboard-api의
`linux/arm64` SHA 이미지를 게시한다. telemetry-gateway는 ECR 저장소 설정이 있을 때만 게시한다.
설정된 이미지의 게시가 끝난 뒤 앞 세 앱의 `dev` 태그를 각 build digest로 순차 갱신한다.
중간 실패 시 이미 바뀐 태그는 남고, Gateway에는 `dev` 태그를 만들지 않는다.
같은 소스 SHA로 재실행해도 base image·외부 릴리스 자산 등의 입력에 따라 digest가 달라질 수 있다.

dev의 공개 수집 경로는 Gateway → Strimzi Kafka → ingest다. Gateway를 제외한 세 앱의 성공이나
각 저장소에서 발견한 최신 태그만으로는 네 앱이 함께 검증된 공급 단위인지 알 수 없다.
현재 workflow에는 네 앱 완료 manifest와 GitOps 승격이 없으며, infra가 확인한 digest를 수동 배포한다.

Git의 desired 변경을 한 번에 기록해도 네 앱의 실제 교체는 순차적이다. enrollment의 Flyway,
ingest의 Kafka backlog 처리, dashboard 캐시 DDL과 ClickHouse 초기화 책임을 유지하면서
이미지 공급 완료·배포 진행·기능 검증의 경계를 명시해야 한다.

## Decision

1. **자동 승격 단위는 네 앱의 완료 묶음이다.** enrollment-api·telemetry-ingest·telemetry-gateway·dashboard-api를
   같은 backend 전체 commit SHA, 같은 workflow 실행과 같은 실행 attempt에서 검증·빌드·게시한다.
   네 이미지 모두 `linux/arm64`여야 한다. Gateway의 저장소 설정 누락·빌드 생략·실패는 묶음 실패다.
   다른 SHA나 이전 attempt의 성공 이미지를 섞어 빈자리를 채우지 않는다.
   retention-worker·prod·별도 frontend는 이 묶음의 대상이 아니다.

2. **backend는 완료 manifest를 발행하고 배포 권한은 갖지 않는다.** 필수 검증과 네 이미지 게시가 모두
   성공한 뒤 해당 build 단계의 output digest를 모아 manifest를 만든다. 나중에 `dev`나 SHA 태그를
   다시 조회한 값으로 digest를 대체하지 않는다. 예상 ECR 저장소의 digest 존재와 플랫폼을 검증한다.
   enrollment 이미지에 포함되는 CLI·GUI는 기존 고정 릴리스 및 `SHA256SUMS` 검증을 유지한다.
   검증 실패·누락이 있으면 성공한 개별 이미지가 있어도 완료 묶음을 발행하지 않는다.
   후보 발견용 tracking tag는 기존 `dev`를 Gateway까지 확장해 네 앱 모두 `dev`로 고정한다. tag 갱신은 후보 알림일 뿐
   완료 manifest나 immutable digest를 대신하지 않으며 부분 갱신을 배포로 연결하지 않는다.
   CI는 Kubernetes·Argo CD 배포나 private GitOps desired 쓰기를 수행하지 않는다.

3. **manifest와 식별자는 공개 허브 계약을 따른다.** schema version, backend 저장소·전체 commit,
   workflow 식별·run ID·attempt, 정확히 네 앱의 repository·digest·platform, 완료 상태와 검증 증거,
   enrollmentAssets의 고정 CLI·GUI 릴리스·자산·체크섬 provenance를
   [generated values 동기화 계약](../../../docs/contracts/generated-values-sync.md)에 맞춰 전달한다.
   묶음 식별자 B는 manifest 원본 UTF-8 바이트의 SHA-256이며 manifest 내부에 자기 hash를 넣지 않는다.
   B와 별개로 source·run·attempt 필드를 대조해 동일 실행의 네 앱인지 확인한다.
   원본과 검증 증거는 소비 및 rollback에 필요한 동안 변경 없이 보존한다. infra 소비자는 manifest의
   성공 표기를 그대로 믿지 않고 해당 실행의 최종 성공·출처와 이미지 대응을 검증한다.
   이 ADR은 공통 schema·저장 위치·소비 검증 규칙의 별도 사본을 만들지 않는다.

4. **재실행 결과는 다시 검증한다.** 동일한 manifest B의 재소비는 멱등 처리한다.
   같은 backend SHA라도 digest가 달라지거나 실행 attempt가 달라져 manifest 바이트가 바뀌면 새 B다.
   새 B는 네 앱의 검증 및 허브의 승격 조건을 다시 충족해야 하며, SHA가 같다는 이유로 생략하지 않는다.
   같은 source와 네 digest를 다시 검증한 경우에는 provenance 기록을 갱신할 수 있으며, B 변경만으로
   같은 이미지의 불필요한 Pod 재시작을 요구하지 않는다.
   이전 실행이 늦게 완료돼 현재 선택을 덮지 않도록 허브의 최신성·expected revision 검사를 따른다.
   run 번호나 완료 시각만으로 최신 소스를 판정하지 않는다.

5. **Image Updater는 후보를 찾고 infra가 묶음을 승격한다.** native Image Updater의 후보 발견 이후
   infra 검증·발행 경로가 완성된 B와 호환되는 infra commit C, immutable values snapshot V를 확인한다.
   네 앱의 digest는 먼저 V에 기록한다. 보호된 private main의 desired commit P는 그 V와 B·C를
   한 번에 선택하며, desired metadata와 Application source 참조를 함께 갱신한다.
   Image Updater가 앱별 digest를 독립적으로 desired에 쓰는 경로는 허용하지 않는다.
   검증 실패나 불완전한 묶음은 현재 P를 유지한다. private 저장소와 publisher·Argo 운영은
   [infra ADR 0054](../../../infra/docs/adr/0054-dev-argocd-gitops-operations.md)가 구체화한다.

6. **기존 스키마 책임을 보존하고 앱을 순서대로 배포한다.** 저장소·권한·runtime 준비 뒤
   enrollment-api의 원천 Flyway 적용 성공과 기동을 확인하고 telemetry-ingest → telemetry-gateway →
   dashboard-api 순으로 진행한다. ingest는 원천 Flyway를 실행하지 않고 dashboard는 자기 캐시 DDL만
   적용한다. ClickHouse 초기화·재시도와 실패 시 동작은 ADR 0015·0016 및 현행 앱 책임을 유지한다.
   GitOps 도입만을 위해 Flyway를 별도 Job·hook으로 옮기거나 DB downgrade를 추가하지 않는다.
   앞 단계의 실패는 다음 앱 진행을 막는다. 실제 순서·상태 판정은 infra가 구현한다.

7. **묶음의 원자성과 실행 중 호환성을 구분한다.** 네 digest가 담긴 V와 B·C를 P가 한 번에 선택해도
   앱 교체에는 구·신 버전이 함께 동작하는 구간이 있다. 현재 단일 replica·Recreate도 앱 간 혼합 버전과 중단을 없애지 않는다.
   배포 전후의 RDS 스키마·JWT/키링·runtime 입력, Gateway ingress envelope와 ingest의 backlog 소비,
   ClickHouse 적재·dashboard 조회가 이 구간에 호환돼야 한다. 변경은 추가형 전환과 소비자 선행 배포로
   기존 입력을 보존하며, 안전한 공존을 증명할 수 없는 변경은 일반 자동 승격에 넣지 않고 별도 전환 절차로 다룬다.
   [ADR 0074](0074-producer-버전-대신-이벤트-계약으로-정규화한다.md)의
   이벤트 정규화·호환성 규칙도 유지한다. 제품 버전 문자열을 배포 허용이나 이벤트 수용의 새 조건으로 만들지 않는다.

8. **배포 성공은 기능 증거로 확인한다.** 이미지 게시, Argo Synced/Healthy, Pod readiness와 기능 성공을
   별도로 기록한다. Gateway health는 Kafka를 보지 않고 ingest health도 저장소 성공을 보증하지 않는다.
   enrollment 발급·bootstrap 공급, 공개 수집 요청의 Kafka ACK → consume → commit과 S3·ClickHouse 기대 결과,
   dashboard의 해당 데이터 조회를 확인한다. 특정 smoke 결과를 전체 SLO나 최대 처리량 검증으로 확대하지 않는다.

9. **rollback은 이전 검증 묶음과 현재 호환 설정의 조합으로 한다.** infra 절차로 Image Updater와 자동 sync를
   일시 중지하고 승격기도 멈춘다. 이전 검증 B의 네 digest를 현재 DB 스키마·JWT 키·TLS revision·runtime 입력과 대조한다.
   호환되는 C·V를 선택하거나 새로 검증해 복구 P를 발행한다. 과거 values를 통째로 되돌려 만료된 키·인증서나
   폐기한 MSK 경로를 복원하지 않는다. DB·PVC·Kafka offset을 이미지 rollback과 함께 되돌리지 않는다.
   호환되지 않으면 자동 복구를 진행하지 않고 별도 데이터·전환 절차로 판단한다.
   복구된 desired와 실제 상태·기능 검증이 일치한 뒤 자동화를 재개한다.

10. **GitOps 인계 전에는 현행 수동 배포를 유지한다.** 네 앱 manifest 발행, infra 소비·승격 검증,
    기존 release의 Argo 소유권 인계와 복구 검증을 마치기 전에는
    [EKS 수동 배포 런북](../../../infra/docs/runbooks/dev-eks-manual-deployment.md)을 따른다.
    다만 완료 B가 없는 기존 이미지의 C·handoff snapshot H 기반 수동 인수는
    [허브 계약의 인계 절차](../../../docs/contracts/generated-values-sync.md#5-적용-순서와-성공-판정)에 따라 V/P·root 자동화와 분리한다.
    인계 후 같은 release를 수동 Helm과 Argo가 동시에 변경하지 않는다. 이 ADR과 관련 문서의 수정만으로
    workflow나 배포 경로가 바뀌었다고 판단하지 않는다.

## Alternatives Considered

### A. 각 앱의 최신 태그를 독립적으로 자동 배포한다

- 장점: 한 앱의 수정만 빠르게 배포할 수 있다.
- 단점: 네 앱의 검증 결과가 다른 SHA·attempt에 속하거나 게시 도중 일부만 바뀔 수 있다.
- 탈락 이유: 같은 backend SHA의 완료 묶음만 자동 승격한다는 정책을 만족하지 않는다.

### B. SHA 태그 하나로 완료와 불변성을 판단한다

- 장점: 별도 완료 manifest 없이 소스와 이미지를 연결한다.
- 단점: 같은 SHA 재실행의 digest 변화, 필수 앱 누락과 부분 게시를 구분하지 못한다.
- 탈락 이유: 배포·재현·rollback의 실체는 build output digest와 검증된 묶음이다.

### C. backend CI가 private desired 또는 클러스터를 직접 변경한다

- 장점: 이미지 게시 뒤 바로 배포할 수 있다.
- 단점: runtime·infra revision 검증과 배포 권한이 backend로 옮겨지고 작성자가 중복된다.
- 탈락 이유: 공통 허브 계약에 따라 backend는 산출물, infra는 검증·승격·배포를 소유한다.

## Consequences/Tradeoffs

### Positive

- 네 앱의 출처·검증 실행·digest를 B 하나로 추적하며 부분 게시가 desired를 바꾸지 않는다.
- 동일 SHA의 재빌드도 별도 산출물로 대조하고 이전 검증 묶음으로 복구할 수 있다.
- Flyway·캐시 DDL·ClickHouse 초기화 책임과 레포 간 권한 경계를 유지한다.

### Negative

- 한 앱의 검증·게시 실패가 네 앱의 자동 승격을 막는다. 네 앱을 같은 attempt에서 다시 준비하는 비용이 있다.
- manifest·검증 증거·이미지를 복구 가능 기간 동안 보존해야 한다.
- desired를 한 commit으로 바꿔도 배포 중단과 혼합 버전 호환성 검토는 필요하다.
  비호환 DB·이벤트 변경은 이미지 rollback만으로 복구할 수 없다.

## Follow-up

- backend workflow에서 Gateway를 필수화하고 네 앱 완료 manifest·출처·검증 증거 및 네 앱의 `dev` tracking tag를 공급한다.
  현재 선택적 Gateway와 세 앱 `dev` 태그 승격을 완료 묶음 구현으로 간주하지 않는다.
- infra가 native Image Updater 후보를 검증된 B로 연결하고 C·V 검증 및 P의 단일 commit 승격을 구현한다.
- enrollment Flyway 확인을 포함한 앱 진행 순서, 기능 검증, 소유권 인계와 rollback·자동화 재개를 실제로 검증한다.
- 문서 변경에는 위 workflow·Argo 구현, AWS/Kubernetes 변경과 실배포 검증을 포함하지 않는다.

## Acceptance Criteria

- 네 앱의 SHA·run·attempt·platform·repository와 build output digest를 manifest 및 실행 증거로 대조한다.
- Gateway 누락, 한 앱 실패, 서로 다른 attempt 혼합, 출처 불일치, 오래된 실행에는 새 P가 생기지 않는다.
- 같은 B의 재소비는 멱등이고 같은 SHA의 새 digest는 새 B로 전체 검증한다.
- 네 digest가 담긴 V와 B·C를 P가 한 번에 선택하며 enrollment Flyway 실패 시 다음 앱으로 진행하지 않는다.
- 구·신 앱 및 backlog 호환, 현재 설정을 유지한 이전 B rollback과 자동화 재개를 확인한다.
- Argo 상태와 별도로 발급·수집·적재·조회 기능 증거를 남긴다. 이 항목들은 후속 구현의 수락 조건이다.

## References

- [허브 ADR 0030](../../../docs/adr/0030-dev-gitops-delivery.md) · [generated values 동기화 계약](../../../docs/contracts/generated-values-sync.md)
- [infra ADR 0054](../../../infra/docs/adr/0054-dev-argocd-gitops-operations.md)
- [enrollment 서버 명세](../enrollment-server-spec.md) §9.4·9.6 · [Kafka 운영](../telemetry-kafka-operations.md)
- [ADR 0070](0070-Kafka-mTLS-접속은-공통-설정에서-PKCS12-파일로-조립한다.md) — TLS 파일 공급·재시작 계약
- `.github/workflows/deploy_dev.yml` — 현재 앱별 build와 `Promote dev image tags` 단계
