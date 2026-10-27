# 설치 코드 사전 검증 운영

`GET /unix`·`GET /windows`는 초대 상태를 읽기 전용 조회한다. 공개 전에 infra PROJ-269의 등록 그룹 WAF 정책이 dev 공개 ALB에 연결돼 있어야 한다. 최종 소비는 `POST /api/v1/enroll`의 조건부 UPDATE만 수행한다. 세부 HTTP 계약은 [설치 API 명세](api/reference/enrollment.md#부트스트랩-스크립트와-바이너리), 결정은 [ADR 0005](adr/0005-설치-부트스트랩-스크립트와-바이너리-서빙.md)를 따른다.

## 배포 순서

1. 기존 enrollment 이미지 digest와 runtime 설정 참조를 기록한다. prod는 변경하지 않는다.
2. infra에서 정확한 세 경로가 기존 등록 그룹과 IP당 300회/300초를 공유하는지, 차단 429와 `Retry-After: 60`, 다른 IP 및 차단 해제 뒤 정상 요청을 확인한다. 배포된 Web ACL과 공개 ALB 연결 증거를 남긴다.
3. backend 필수 CI·리뷰와 계약 PR을 확인한 뒤 병합한다. 이미지 게시 digest를 infra EKS 런북으로 배포하고 Flyway·readiness·공개 경로를 각각 확인한다.
4. 지정한 테스트 수신함에서 받은 메일의 설치 명령으로 원격 게시 CLI를 내려받아 enroll까지 실행한다. outbox `sent`와 SES 접수 ID만으로 실제 수신·설치 완료라고 판단하지 않는다.
5. 잘못된 코드 여섯 상태·반복 조회 비소비·조회 뒤 취소/만료/사용·동시 단일 성공을 dev에서도 확인한다. 코드·토큰·본문·전체 query URL을 로그·PR·Jira에 기록하지 않는다. 초대 식별자·배포 digest·상태·시각만 남긴다.

복구는 기록한 이전 enrollment digest를 같은 runtime 참조로 재배포한다. DB 마이그레이션은 없다. WAF를 먼저 완화하지 않는다. 이전 스크립트 발급 동작이 복구된 뒤 필요하면 infra의 이전 정책 복구 절차를 따르고, 두 변경의 실제 상태를 따로 기록한다.

## 로컬 검증

원격 telemetryctl 기본 브랜치의 `contracts`를 `PULSEMETRY_CONTRACTS_DIR`로 공급한다. Java 25·Docker·`sh`·PowerShell 7의 `pwsh`가 필요하다. PowerShell이 없으면 오류 실행 테스트는 실패하며 건너뛰지 않는다. GitHub Ubuntu runner의 PowerShell과 같은 실행 경로를 사용한다.

```sh
./gradlew :apps:enrollment-api:test --tests '*Bootstrap*' --tests '*EnrollApiTest'
```

`BootstrapApiTest`는 실제 PostgreSQL·HTTP로 상태별 응답을 받고 sh와 PowerShell에 입력해 stderr·종료 코드 1·작업 디렉터리의 파일 미생성을 확인한다. 응답에 바이너리 요청·다운로드·enroll 명령이 없고 DB 설치 행도 늘지 않는지 확인한다. 테스트 전용 DB의 테이블을 잠시 숨겨 실제 HTTP 5xx를 확인한 뒤 finally에서 원래 이름으로 되돌린다. 조회 후 동시 enroll 8개는 하나만 201이어야 한다. `EnrollApiTest`는 기존 네 키 응답과 원자적 소비 계약을 함께 검증한다.

로컬 결과는 dev 실제 검증 증거가 아니다. WAF 미적용·리뷰 대기·실제 수신함 미확인 중 하나라도 남으면 해당 티켓을 완료로 전환하지 않는다.
