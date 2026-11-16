# dev 이미지 완료 묶음과 Git 게시

[ADR 0081](adr/0081-dev-앱-이미지는-검증한-묶음으로-GitOps에-인계한다.md)과
[허브 ADR 0036](https://github.com/soma-376/docs/blob/main/adr/0036-simple-dev-git-publication-and-argocd-sync.md)을 따른다.
PROJ-334는 검증한 이미지 선택을 Git에 게시하고 PROJ-335는 root와 하위 Application의 자동 적용을 연결한다.
기존 PROJ-311의 장기 CAS·readiness·observer·자동 rollback·실패 fence는 사용하지 않는다.

## 실행과 활성화

`DEV_GITOPS_ENABLED` 미설정/false에서는 게시 job을 시작하지 않는다. 이 스위치는 job-level if가 읽는
repository variable이며 producer의 완료 B 생성과는 별개다. `config/gitops-tooling.json`은 검토한 infra
전체 SHA로 고정하며 null·형식 오류는 private 게시 전에 거부한다.

- `promote_dev.yml`과 게시 코드를 default `develop`에 두고 GitHub `dev-gitops` Environment도 develop만 허용한다.
- 기존 `GITOPS_PROMOTION_ROLE_ARN_DEV`의 ECR 읽기 역할을 사용한다. Kubernetes·Argo 쓰기나 observer는 호출하지 않는다.
- publisher App은 `infra-generated-values` 하나의 Contents write만 사용한다. `GITOPS_PUBLISHER_APP_ID`와
  기존 `GITOPS_PUBLISHER_PRIVATE_KEY` 등록 상태를 확인하며 Argo reader 키를 공유하지 않는다.
- PROJ-335에서 초기 snapshot·Application 선언·root 연결을 확인한 후 repository variable을 활성화한다.
- 키·token·AWS 조회 원문·private values를 로그나 공개 artifact에 넣지 않는다.

코드·테스트 완료는 위 실제 설정과 Argo 연결 완료를 뜻하지 않는다.

## 원본 생산과 검증

`Publish Dev Images`는 같은 backend SHA/run/attempt의 enrollment-api·telemetry-ingest·telemetry-gateway·
dashboard-api 네 ARM64 이미지를 생산한다. 어느 하나라도 실패하면 완료 B가 없다. 기존 세 앱의 `dev`
태그는 호환용이며 배포 선택에 쓰지 않는다.

각 `/app/deployment-contract.json`은 Flyway·ClickHouse DDL·Kafka envelope·고정 CLI/GUI 자산의 선언
지문이다. 실제 schema나 기능 성공을 뜻하지 않는다. immutable digest의 ARM64 root/child·label·contract
hash를 대조하고, container는 pull/create/cp 후 제거하며 start/run/exec하지 않는다.

완료 artifact `dev-image-bundle-<run>-<attempt>`에는 `completion.json`과 `completion.sha256`만 들어간다.
B는 원본 UTF-8 바이트의 hash다. 공개 artifact에는 canonical `soma-376/<app>` repository·digest·출처만
담고 계정 ID·전체 ECR URL·내부 주소·runtime values는 넣지 않는다. ECR lifecycle은 현재 선택 이미지를 보존해야 한다.

## 별도 게시 workflow

성공한 원본 생산 workflow 종료 이벤트로 실행한다. schedule은 없으며 수동 실행은 `run_id`와
`run_attempt`를 함께 주거나 둘 다 비워 최신 성공 producer를 선택한다. 수동 입력도 최신성 검사를 우회하지 않는다.

게시 workflow는 자신의 `github.workflow_sha` 코드를 실행하고 고정 infra tooling을 checkout한다.
원본 API에서 workflow ID/path·repository·develop 계보·SHA/run/attempt·전체 및 필수 job 성공을 대조한다.
artifact ID·이름·만료·원본 ZIP hash와 완료 B를 확인한 뒤 ARM64·packaged contract를 검증한다.
private publisher token을 발급한 뒤 동일 원본과 최신 성공 producer를 다시 확인한다.

`node scripts/dev-gitops/publish.mjs --candidate <completion.json> --provenance <producer-evidence.json>`를
호출한다. `GH_TOKEN`은 원본 API 조회, `VALUES_GH_TOKEN`은 private Git 게시에만 사용한다.
공통 게시기는 현재 desired가 선택한 V를 기준으로 이미지 digest·bundle·producer만 갱신하고 현재 C·
비이미지 bytes·Application syncPolicy를 보존한다. 로컬 snapshot V와 Application 선택 P를 만들고 P를
한 번 push한다. 기존 main HEAD와 충돌하면 최신 선택에서 최대 3회 재생성하며 force push하지 않는다.

`published`는 Git 선택 완료만 의미한다. Argo 배포 완료를 기다리거나 실패 시 자동 rollback하지 않는다.
연속 게시의 중간 버전은 건너뛸 수 있으며 Argo는 최신 선언으로 수렴한다. 실패한 게시는 workflow 실패로
남고 운영자가 재실행하거나 새 성공 producer를 게시한다. 자세한 충돌·no-op·응답 유실은 허브 공급 계약을 따른다.

## 배포 상태와 수동 복구

Argo UI에서 root와 하위 앱의 현재 revision·Synced·Healthy·operation을 확인한다. 기능 장애는 별도로
확인한다. 게시 성공을 앱 기능 성공으로 보고하지 않는다.

복구가 필요하면 두 게시기를 잠시 중지하고 검토한 이전 또는 수정된 Git 선언을 선택한다. DB downgrade·
PVC 복원·Kafka offset reset은 자동화하지 않는다. 유지보수 중에는 Git에서 해당 child의 auto sync와
self-heal을 끄고 root 반영·진행 operation 종료를 확인한다. 다음 게시도 그 syncPolicy를 보존한다.

## 로컬 확인

```sh
python3 -m unittest discover -s scripts/gitops -p 'test_*.py'
python3 .github/tests/test_deploy_dev.py
python3 -m unittest discover -s scripts/releases -p 'test_*.py'
```

원본·archive·package·실행 경계는 모의 테스트로 검증한다. Git 게시와 원격 충돌은 infra 공통 게시 테스트가
담당한다. 실제 ECR·GitHub Environment·OIDC·private Git·root·auto sync 확인은 PROJ-335 운영 기록으로 남긴다.
