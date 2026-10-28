# 설치 CLI 릴리스 공급과 dev 검증

## 공급 계약

`config/telemetryctl-release.json`이 소비할 저장소·태그·SHA-256의 단일 입력이다.
현재 태그는 공개 GitHub Release의 **`v0.1.0-rc.2`**다. prerelease를 명시적으로 선택하며
`latest` 조회, develop 재빌드, GUI 패키지, S3 공급은 사용하지 않는다.
공개 `/bin/pulsemetry_{os}_{arch}[.exe]` 이름과 실제 `pulsemetry_cli_*` 자산 대응은
기존 `DaemonRelease` 구현과 [ADR 0053](adr/0053-데몬에-기대는-서버-동작은-telemetryctl-기본-브랜치의-구현만-전제한다.md)을 따른다.

공급 스크립트는 게시 상태와 CLI 6종·`SHA256SUMS`의 존재, GitHub 자산 digest,
저장소에 고정한 해시, 내려받은 실제 파일 해시와 체크섬 목록을 모두 대조한다.
조회·다운로드 실패, 중복·누락 자산, 해시 불일치는 비영 종료이며 완성된 출력 경로를 남기지 않는다.
체크섬 파일에 GUI 항목이 있어도 CLI 6종 모두를 확인하고 원본 체크섬 파일은 바꾸지 않는다.

출력은 `<공급 경로>/v0.1.0-rc.2/` 하나이며 CLI 6종과 원본 `SHA256SUMS`만 포함한다.
이미지 빌드는 `telemetry-binaries` named context를 다시 검증한 뒤
`/app/binaries/v0.1.0-rc.2/`로 복사한다. 런타임 사용자 UID 10001이 읽을 수 있고
`PULSEMETRY_BINARIES_DIR=/app/binaries`를 유지한다. 다른 앱 이미지에는 포함하지 않는다.

## 로컬 및 CI 검증

```bash
python3 -m unittest discover -s scripts/releases -p 'test_*.py'
python3 .github/tests/test_deploy_dev.py
python3 scripts/releases/telemetryctl_release.py download /tmp/telemetryctl-binaries
python3 scripts/releases/telemetryctl_release.py verify /tmp/telemetryctl-binaries
docker build --target telemetry-binaries-verified \
  --build-context telemetry-binaries=/tmp/telemetryctl-binaries -t telemetry-binaries-verified .
docker run --rm --user 10001:10001 telemetry-binaries-verified \
  python /supply/scripts/releases/telemetryctl_release.py verify /binaries
```

다운로드 출력 경로가 존재하면 중단한다. 이전 증거를 덮어쓰지 않고 새 경로를 선택한다.
공개 자산 다운로드는 인증 없이 수행하며 GitHub API 조회에만 선택적 `GH_TOKEN`을 쓴다.
CI는 기본 `github.token`의 `contents: read`만 필요하다. 토큰을 build argument·context에 넣지 않는다.
PR의 `Published CLI supply`는 실패 회귀, 실제 원격 다운로드, Docker 입력과 UID 10001 읽기,
Windows·macOS·Linux × amd64·arm64 네이티브 CLI 버전을 검증한다.
기존 Gradle 계약 원본 checkout과 검증은 유지한다.

`Publish Dev Images`는 같은 검증 후 enrollment 이미지를 게시하고 workflow summary에
backend 커밋·enrollment 이미지 digest·CLI 태그·일곱 해시를 기록한다.
ECR 이미지 게시 완료는 EKS 배포나 설치 완료가 아니다.

전체 enrollment 이미지 로컬 빌드는 기존 계약 context와 릴리스 context를 함께 넘긴다.
계약 파일은 원격 telemetryctl 기본 브랜치 원본을 사용한다.

```bash
docker buildx build --platform linux/arm64 --target enrollment-api \
  --build-context telemetry-contracts=/path/to/remote-telemetryctl/contracts \
  --build-context telemetry-binaries=/tmp/telemetryctl-binaries \
  -t pulsemetry-enrollment:verification --load .
```

## dev 배포 및 실제 검증

현행 EKS 접근·Helm 값 생성·배포 순서는 infra의
[`dev-eks-manual-deployment.md`](../../infra/docs/runbooks/dev-eks-manual-deployment.md)를 따른다.
현재 enrollment의 **정확한 이미지 digest와 Helm revision**, backend 커밋, infra 커밋,
비밀 없는 runtime 입력·생성값을 먼저 보존한다. 기존 migration과 복구 이미지의 호환성을 확인한다.
새 이미지도 가변 `dev` 태그 대신 CI에서 확인한 digest로 지정한다.
PROJ-268 사전 검증이 포함된 이미지는 PROJ-269 WAF 적용·실검증 이후에 공개한다.

검증 기록은 다음 관문을 별도로 표시한다.

1. ECR 이미지의 `/app/binaries/v0.1.0-rc.2/` 존재·실제 해시·UID 10001 읽기 권한.
2. EKS enrollment rollout과 실제 Pod의 이미지 digest, 공개 HTTPS·target health.
3. dev 공개 `/bin/pulsemetry_*` 여섯 경로에서 내려받은 파일의 SHA-256과
   `config/telemetryctl-release.json`의 대응 `pulsemetry_cli_*` 해시 일치.
4. Mach-O x86_64·arm64, ELF x86-64·aarch64, PE32+ x86-64·Aarch64 형식.
   실행 가능한 OS에서 `--version`이 `pulsemetry 0.1.0-rc.2`인지 확인한다.
   실행하지 못한 OS는 형식 검증과 실행 검증을 구분해 기록한다.
5. 기존 `GET /api/v1/check-updates`가 같은 판·대상 해시를 답하는지 확인한다.
6. 지정한 테스트 수신함의 실제 초대 메일 → `/install` → 설치 명령 → 다운로드 →
   `POST /api/v1/enroll` 성공. CLI 버전 출력이나 API 단독 성공으로 대체하지 않는다.

원격 릴리스 URL·태그·해시·커밋·이미지 digest·초대 식별자·검증 시각·결과는 증거로 남긴다.
초대 코드, 메일 본문 원문, URL 쿼리, 토큰, 키는 로그·PR·Jira에 기록하지 않는다.

## 판 갱신과 복구

판 갱신은 새로운 게시 릴리스의 여섯 CLI와 원본 체크섬을 검증한 뒤 lock 파일의 태그·일곱 해시를
함께 바꾸는 별도 PR로 수행한다. 같은 태그의 게시 파일이 교체되면 기존 lock이 공급을 거부한다.
해시 검사를 끄거나 다른 릴리스로 자동 전환하지 않는다.

복구는 저장한 마지막 검증 Helm revision 또는 같은 설정의 이전 이미지 digest를 사용한다.
임의 이전 태그나 `kubectl set image`로 Helm 선언과 실행 상태를 갈라놓지 않는다.
runtime 변경까지 되돌리는 경우 메일·WAF 등 선행 안전 정책의 영향도 함께 확인한다.

```bash
helm rollback enrollment-api "$LAST_GOOD_REVISION" --namespace pulsemetry-dev --wait --timeout 10m
kubectl --namespace pulsemetry-dev rollout status deployment/enrollment-api --timeout=5m
kubectl --namespace pulsemetry-dev get pods -l app.kubernetes.io/instance=enrollment-api \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[*].imageID}{"\n"}{end}'
```

복구 뒤 digest·health·공개 다운로드 상태가 이전 기록과 같은지 확인한다.
이전 이미지에 CLI 자산이 없었다면 `/bin`이 다시 404인 상태도 복구의 결과로 명시한다.
코드·런북·CI 통과만으로 실제 복구를 수행했다고 기록하지 않는다.
