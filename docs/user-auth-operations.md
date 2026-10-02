# 사용자 인증 운영

## 클라이언트 연결 범위

백엔드 로그인은 `POST /v1/auth/login`의 tenant_id·email·password 방식이다. OIDC/SAML IdP 로그인은 구현하지 않는다.
프론트 개발용 `/api/dev/seed-login`은 허용된 시드 이메일을 조직에 매핑하고 서버 측 개발 암호로 실제 로그인 API를 호출하는 어댑터다.
이메일만 보내도 임의 조직 사용자가 인증되는 기능이나 운영 SSO 도메인 탐색 API가 아니다. 운영 인증 수단으로 사용하지 않는다.
실제 필드와 응답은 [Enrollment 명세 §11](enrollment-server-spec.md#11-사용자-인증)을 따른다.

Enrollment(기본 8080)는 토큰 발급·관리 명령, Dashboard(기본 8081)는 공개키·현재 세션 검증과 조회를 담당한다.
두 서버의 issuer·audience·공개키 구성이 일치해야 한다. 조회 서버에는 서명용 개인키를 주지 않는다.
`pulsemetry.user-auth.enabled`와 `pulsemetry.management.enabled`는 별도 설정이다.
관리 기능을 켜도 owner/admin 및 조직 일치 검증을 통과해야 하며, 프론트 버튼 비활성화로 인가를 대체하지 않는다.

## 활성화

Flyway V5를 enrollment-api에서 먼저 적용한다. 기존 경로는 유지되며 사용자 인증은 기본 비활성이다.
다음 서버 설정과 키 파일을 준비한 뒤 pulsemetry.user-auth.enabled=true로 켠다.

```yaml
pulsemetry:
  user-auth:
    enabled: true
    issuer: https://auth.example.com
    audience: pulsemetry
    active-kid: key-2026-09
    private-key-file: /run/secrets/user-auth-private.pem
    public-key-files:
      key-2026-09: /run/secrets/user-auth-public.pem
    allowed-origins:
      - https://dashboard.example.com
```

issuer/audience는 요청 Host에서 만들지 않는다. RSA는 2048비트 이상이며 개인키는 PKCS8,
공개키는 X509 PEM이다. 키는 배포자가 secret volume으로 읽기 전용 주입한다. 코드/이미지에 넣지 않는다.
활성 키 누락·쌍 불일치는 기동 실패다. 테스트는 임시 키를 생성하며 운영 자동 생성은 없다.
프록시 forwarded 헤더 자동 복원을 켜지 않는다. 현재 rate limit은 remoteAddr 기준이므로
신뢰 프록시 설정을 검토하기 전에는 ALB 주소 단위로 제한될 수 있다.

## 키 교체

1. 새 공개키를 모든 검증 인스턴스 public-key-files에 추가하고 재기동한다. 구 키도 유지한다.
2. active-kid와 개인키 파일을 새 키로 전환한다. 공개키가 전파된 뒤 서명을 바꾼다.
3. 마지막 구 키 발급부터 330초 이상 대기한 뒤 구 공개키를 제거한다.
4. 문제 발생 시 구 공개키가 유지되는 동안 active-kid/개인키를 함께 되돌린다.

새/구 키 토큰 동시 수용, unknown kid 거부, 구 키 제거 후 거부는 UserJwtTest가 검증한다.
긴급 유출은 해당 공개키 제거로 그 키 AT를 즉시 거부하고 영향 세션을 폐기한다.

## 세션과 제한

로그인은 계약·manifest·온보딩 완료 여부와 무관하다. 활성 manifest가 없으면 세션의
`manifest_revision`은 0이며, 정책 저장 이후에도 같은 세션의 refresh는 0을 보존한다.
현재 온보딩 상태는 온보딩 조회 API로 확인한다(ADR 0033).
첫 정책 저장에는 `PULSEMETRY_ONBOARDING_OTLP_ENDPOINT`를 명시한다. local 프로필에서는
`http://localhost:4316`이 기본값이며, 운영에는 기본값이 없다. 미설정이어도 로그인은 가능하다.

AT 300초, 시계 오차 30초, RT 절대 만료 30일이다. 세션을 유지한 refresh는 만료를 연장하지 않는다.
클라이언트는 RT 교환을 직렬화해야 한다. 소비된 RT 재사용은 새 RT까지 폐기한다.
회원 상태·tenant 상태·role 변경은 다음 사용자 AT 검증/refresh부터 확인한다.
기존 관리자 경로에서 이 검증기를 사용하는 작업은 PROJ-109에 남아 있다.

5회/15분 로그인 실패는 15분 잠금이다. IP별 30회/분은 PostgreSQL 공유 상태다.
인증 실패 401, 제한 429, 인프라/서명 오류 503을 구분하고 429/503의 Retry-After를 따른다.
로그·APM의 request body와 Authorization 수집은 인증 경로에서 비활성화한다.

만료된 세션과 코드·오래된 제한 행은 운영 유지보수에서 삭제할 수 있다. RT 이력은 세션 만료 전 지우지 않는다.
```sql
DELETE FROM enrollment.user_sessions WHERE expires_at < now() - interval '1 day';
DELETE FROM enrollment.user_authorization_codes WHERE expires_at < now() - interval '1 day';
DELETE FROM enrollment.auth_attempts
 WHERE window_started_at < now() - interval '1 day'
   AND (locked_until IS NULL OR locked_until < now() - interval '1 day');
```

## 롤백

user-auth.enabled=false로 신규 API를 닫고 이전 앱으로 되돌릴 수 있다. V5는 추가형이며 삭제 마이그레이션은 하지 않는다.
기존 enroll·pit_/ptt_는 유지된다. 가입으로 설정된 비밀번호/상태는 보존한다.

## manifest 재동기화

`GET /v1/manifest`는 사용자 RT를 회전한다. CDN/API Gateway에서 이 경로를 캐시하거나 재시도하지 않도록 한다.
503/409로 트랜잭션이 실패하면 RT 소비는 롤백된다. 연결 종료 등 응답 유실은 성공 여부를 알 수 없으므로 재로그인한다.
로컬 파일·키링의 적용 완료를 서버 성공과 동일시하지 않는다. OTLP `ptt_` 갱신과 사용자 RT 회전은 별개다.

### 빌드에 필요한 계약

enrollment-api 빌드는 telemetryctl의 `contracts/enrollment-manifest.schema.json`을 jar에 넣는다(ADR 0019).
`PULSEMETRY_CONTRACTS_DIR`은 원본 `contracts` 디렉터리의 절대 경로이고, 생략하면 형제 `../telemetryctl/contracts`를 읽는다.
파일이 없으면 `:apps:enrollment-api:processResources`가 실패한다.
런타임에는 jar에 포함된 스키마를 쓰므로 telemetryctl 체크아웃이나 네트워크가 필요 없다.

이미지 빌드는 `enrollment-api` target에만 named context를 전달한다.
나머지 target은 그 빌드 스테이지를 거치지 않으므로 컨텍스트가 필요 없다.

```sh
docker buildx build --target enrollment-api \
  --build-context telemetry-contracts=../telemetryctl/contracts -t <repo>:<tag> --load .
```

CI의 PR 검증과 develop 배포는 telemetryctl 기본 브랜치를 체크아웃해 그 `contracts`를 쓴다(ref를 고정하지 않는다).
계약 테스트는 telemetryctl 기본 브랜치에 있는 `enrollment-envelope`·`enrollment-manifest` 두 스키마만 원본으로 읽는다. 사용자 토큰 봉투·AT 클레임·재조회 봉투의 오라클은
[명세](enrollment-server-spec.md) §11·§11.1의 표다 — 원격 기본 브랜치에 없는 스키마를 읽게 하면 CI가 죽는다.
