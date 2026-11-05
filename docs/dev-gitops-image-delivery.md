# dev 이미지 완료 묶음과 GitOps 승격

[ADR 0079](adr/0079-dev-앱-이미지는-검증한-묶음으로-GitOps에-인계한다.md)는 backend 공급 결정을,
[허브 ADR 0035](https://github.com/soma-376/docs/blob/docs/PROJ-311-gitops-delivery-contract/adr/0035-dev-gitops-delivery.md)는
C/B/V/P·공통 CAS·실패 fence를 소유한다. PROJ-311은 backend 생산·승격과 필수 infra 연동을 구현한다.
일반 infra 설정 publisher는 후속이다. PROJ-310 실제 인계와 publisher/CAS 운영 수락 전에는 기존 수동 Helm을 유지한다.

## 기본 상태와 활성화 전제

`DEV_GITOPS_ENABLED` 미설정/false에서는 승격 job을 시작하지 않는다. 추적된
`config/gitops-tooling.json`의 `revision`은 검토한 infra commit의 40자리 SHA에 고정한다. `null`이거나
유효한 고정 SHA가 아니면 AWS 역할·private Git token을 받기 전에 중단한다. 예시 SHA나 개발 브랜치명으로 고정을 대신하지 않는다.
backend와 infra의 로컬 테스트 통과는 AWS 배포·GitHub 보호 설정·private 게시·Argo 인계 완료가 아니다.

`DEV_GITOPS_ENABLED`는 job-level if에서 읽는 **repository variable**로 설정한다. Environment에만
설정하면 job 선택 시점에 사용할 수 없다. 이 스위치는 승격만 제어하며 `deploy_dev.yml`의 완료 B 생성은
false여도 develop 생산 실행마다 수행된다. backend-dev 역할의 기존 네 ECR repository에 대한
`ecr:GetDownloadUrlForLayer` 권한을 infra IAM에서 먼저 적용한 뒤 producer workflow를 develop에 머지한다.
이 권한이 없으면 이미지 push가 끝나도 packaged contract를 읽는 완료 B 단계가 실패할 수 있다.

활성화에는 다음 조건을 함께 충족해야 한다.

- `promote_dev.yml`과 실행 코드가 backend default branch에 존재하고 검토되어 있어야 한다. `develop`에만
  추가하면 `workflow_run`·schedule·수동 진입이 준비된 것으로 간주하지 않는다. 현행 infra 실행기는 default `main`도 고정하므로
  default branch를 바꾸면 실행기와 Environment 보호 설정을 함께 재검토한다.
- GitHub `dev-gitops` Environment가 default branch만 허용해야 한다. required reviewer 등 보호 규칙은 운영 정책대로 설정한다.
  정상 자동 승격마다 코드가 추가 승인을 요구하지는 않는다. `workflow_dispatch`도 default branch에서 실행한다.
- infra의 전용 `GitOpsPromotionRoleArn`을 `GITOPS_PROMOTION_ROLE_ARN_DEV` Secret으로 등록한다. ECR 읽기와
  지정 observer 역할 assume만 허용하며 기존 이미지 push 역할, CDK deploy 역할, Argo Git reader를 재사용하지 않는다.
- publisher App을 `infra-generated-values` 하나에 Contents write로 설치하고 `GITOPS_PUBLISHER_APP_ID` variable과
  `GITOPS_PUBLISHER_PRIVATE_KEY` Secret을 준비한다. Argo reader App의 키를 쓰지 않는다.
- private activation 구성·C/H/18개 inventory·digest로 고정한 verifier image·전용 테스트 조직과 설치·읽기 전용 observer를 준비하고
  기존 PROJ-279 writer 종료, 초기 last-known-good B 확보, 공통 CAS 충돌·rollback·resume를 실제로 수락한다.
- review 완료한 infra commit을 tooling lock에 기록하고, 마지막으로 repository variable `DEV_GITOPS_ENABLED=true`를 설정한다.

이 문서와 코드 변경은 위 설정을 생성·등록·활성화하지 않는다. 비밀 원문을 Git·로그·보고서에 넣지 않는다.

## 생산과 공개 artifact

`Publish Dev Images`는 `deploy_dev.yml`의 Gradle 검증과 네 ARM64 이미지를 같은 SHA/run/attempt로 생산한다.
enrollment-api·telemetry-ingest·telemetry-gateway·dashboard-api 중 하나라도 누락·실패하면 완료 B가 없다.
세 앱의 기존 `dev` 태그는 호환 용도로만 남기며 Gateway tracking tag는 만들지 않는다.

각 이미지의 `/app/deployment-contract.json`에는 다음 입력 지문을 담는다.

- enrollment·telemetry_ops·dashboard_cache Flyway migration의 version/script/원문 SHA-256/부호 있는 CRC32 checksum.
  CRC32는 UTF-8 BOM과 CR/LF를 제거한 Flyway 규칙이다. 실제 적용 목록은 배포 후 verifier가 별도로 확인한다.
- telemetry·dashboard_cache ClickHouse DDL 파일의 version/script/SHA-256. 선언 입력 지문이며 실제 schema
  fingerprint나 migration 적용 성공을 뜻하지 않는다.
- Gateway/ingest envelope v1 protobuf 원문의 SHA-256과 telemetryctl CLI 6종·GUI 6종·SHA256SUMS lock 원문의 SHA-256.

계약은 UTF-8의 JSON key 정렬·공백 없는 인코딩과 마지막 LF 하나로 생성한다. 각 계약은 `content` 문자열에
정확한 바이트를 보존하고 그 바이트의 `sha256`을 기록한다. 이미지 label의 backend SHA·계약 hash와 실제 ARM64
config를 확인한 뒤 immutable digest를 pull/create/cp하여 계약 파일을 대조한다. container를 start/run/exec하지 않는다.
manifest가 index라면 고유한 linux/arm64 child descriptor를 확인해 `images.<app>.arm64ManifestDigest`로 보존한다.
일반 manifest는 root digest와 같다. 승격 때 root→child 대응을 다시 확인하며 Pod imageID는 이 child와 비교한다.
tar의 추가 파일·경로 이탈·symlink·hardlink·256 KiB 초과 계약은 거부한다. 검사 container와 임시 인증 설정은 정리한다.

완료 artifact 이름은 `dev-image-bundle-<run>-<attempt>`이며 overwrite 없이 90일 보존한다.
`completion.json`과 `completion.sha256` 두 파일만 들어간다. `B=SHA256(completion.json 원본 UTF-8 바이트)`이고
B 자체를 manifest 내부에 넣지 않는다. 재직렬화한 JSON, summary, 최신 SHA/dev 태그를 B 대신 사용하지 않는다.
manifest의 `buildEvidence.imageConfigSha256`은 검사한 config JSON을 계약과 같은 정렬·LF 규칙으로 인코딩한 지문이다.
OCI config blob digest와 혼동하지 않는다.

backend가 공개 저장소이므로 artifact에는 canonical repository(`soma-376/<app>`)·digest·공개 소스/자산 provenance만
남긴다. 계정 ID·전체 ECR URL·ARN·DB/서비스 주소·runtime values·운영 관측 원문은 넣지 않는다. producer workflow 전체가
실패하면 artifact가 있어도 소비하지 않는다. 소비한 B와 복구에 필요한 이미지·private 증거는 infra 보존 정책으로 유지하며,
90일 지난 GitHub artifact의 자동 재다운로드를 가정하지 않는다. ECR lifecycle이 사용 중·last-known-good digest를 지우면 안 된다.

## 별도 승격과 재평가

`Promote Dev Image Bundle`은 생산 workflow 최종 성공 뒤 `workflow_run`으로 시작하며 30분마다 놓친 후보를 다시 평가한다.
수동 실행은 원본 `run_id`와 `run_attempt`를 함께 주거나 둘 다 비워 최신 성공 후보를 고른다. 수동 입력도 실패 fence와
공통 상태를 우회하지 못한다. 저장소 concurrency와 private Git CAS는 서로 다른 역할이며 둘 다 유지한다.

승격 workflow는 `${github.workflow_sha}`의 자체 source를 checkout한다. producer head와 artifact의 실행 코드를 사용하지 않는다.
API에서 허용 repository/workflow ID·develop·event·SHA·원본 run/attempt 전체 성공 및 필수 두 job 성공을 다시 확인한다.
artifact ID·run/SHA·이름·만료·GitHub ZIP digest와 내부 B hash를 검증한다. 현재 develop 계보 밖 source는 거부한다.
부분 job 재실행으로 검증 job이 이전 attempt에 남으면 혼합 증거로 거부한다. 완료 B를 다시 만들 때는 전체 job을 재실행한다.
실행 시 계정은 STS로 알아내고 전체 ECR URL을 메모리에서 조립한다. 별도 임시 Docker 인증으로 같은 digest의 패키지 계약·ARM64·label을
다시 확인한 뒤 publisher token을 발급한다. 직전 준비와 같은 원본 artifact인지 재검사한 후 공통 실행기에 전달한다.

Node 24와 lockfile로 설치한 검토된 infra의 `scripts/dev-gitops/promote.mjs --candidate <원본> --provenance <API증거>`만 호출한다.
runner에는 Python 3·AWS CLI·명시 설치한 PostgreSQL client와 고정 Session Manager plugin을 준비한다. job은 55분 제한이며
정상 관측·가능한 한 번의 복구가 publisher token의 1시간 수명 안에서 끝나도록 한다. 제한 중단은 잠금 탈취 근거가 아니다.
`GH_TOKEN`은 원본 GitHub 조회, `VALUES_GH_TOKEN`은 private 게시용으로 분리한다. backend는 shared CAS를 복사하지 않는다.
공통 실행기가 현재 C·비이미지 설정·owner/generation·expected P/main HEAD·누적 실패 fence를 검증하고 V 뒤 P를 발행한다.
확정된 이미지 실패에서만 같은 owner가 실제 schema·runtime·키/TLS 호환성을 재확인하고 한 번 복구한다.
관측 불능·owner 소실·복구 실패는 잠금을 자동 탈취하지 않고 보류한다. 잠금 해제 뒤 최신 후보를 다시 평가하며
3회 연속 처리 한도 또는 token 발급 직후 실행을 시작한 지 5분이 지난 뒤 남은 후보는 다음 완료 이벤트나 schedule이 이어받는다.
새 관측·복구 한 회의 시간을 남기지 못한 상태로 두 번째 배포 잠금을 잡지 않는다.

배포·복구·schema·기능 증거는 private 전달 경로에만 보존한다. public workflow artifact로 올리는 단계는 없다.
Git 게시, Argo Synced/Healthy, Pod imageID/Ready, OIDC 로그인·설치·실제 수집·dashboard 성공을 서로 다른 증거로 남긴다.
timeout이나 오래된 Healthy를 새 이미지 확정 실패나 성공으로 바꾸지 않는다.

## 로컬 확인

```sh
python3 -m unittest discover -s scripts/gitops -p 'test_*.py'
python3 .github/tests/test_deploy_dev.py
python3 -m unittest discover -s scripts/releases -p 'test_*.py'
```

이 테스트는 원본 출처·archive·계약 지문·보호 경계를 모의 검증한다. 실제 ECR image pull·GitHub webhook/Environment·OIDC·SSM·
private CAS·Argo 순차 배포·실제 DB/기능·자동 복구와 재개는 활성화 전 운영 수락으로 남는다.
