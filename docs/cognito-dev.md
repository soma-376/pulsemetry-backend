# Cognito 개발 로그인과 자동 시딩

A·B 회사는 각각 별도의 Cognito 사용자 풀을 사용한다. 개발 IdP의 범위와 인프라 코드 제거 결정은
[허브 ADR 0014](../../docs/adr/0014-document-development-identity-without-infra-code.md)를 따른다.
이 안내는 준비된 풀·계정과 공개 JSON을 연결하는 절차다. 현재 저장소에는 Cognito 배포·계정 생성·JSON 내보내기 도구가 없다. 기본 API 테스트는 외부 계정 없이 모의 OIDC를 사용한다.

## 팀원 최초 실행

backend 루트에서 실행한다.

```bash
docker compose up -d --build
docker compose logs dev-seed
docker compose ps -a dev-seed
```

`dev-seed`가 종료 코드 0이면 DB 스키마·A/B/C 기본 시드·A/B Cognito 회사 설정·로컬 인증 키 준비가 끝난 상태다.
시드 JSON은 `tools/dev-seed/config/cognito/company-a/cognito-seed.json`과 `company-b/cognito-seed.json`이다.
Compose가 읽기 전용으로 마운트하므로 시딩에는 AWS 자격 증명·인터넷·Client Secret이 필요 없다.
C 회사의 Cognito 설정은 주입하지 않는다.
구 버전 시드 이미지에서 전환할 때도 `--build`로 이미지를 갱신한다. 버전 2 JSON 적용을 위해 기존 회원 sub나 DB 볼륨을 삭제하지 않는다.

Enrollment를 실행하는 **호스트 셸 또는 IDE 실행 설정**에 아래 두 환경변수를 넣는다.
Docker Compose의 `.env`에 넣는 것만으로 호스트에서 실행하는 Spring 서버에 전달되지는 않는다.

```bash
export PULSEMETRY_COGNITO_A_CLIENT_SECRET='<A 앱 클라이언트의 Secret>'
export PULSEMETRY_COGNITO_B_CLIENT_SECRET='<B 앱 클라이언트의 Secret>'
./gradlew :apps:enrollment-api:bootRun --args='--spring.profiles.active=local'
# 별도 터미널
./gradlew :apps:dashboard-api:bootRun --args='--spring.profiles.active=local'
```

실제 Secret은 Git·채팅·로그에 넣지 않는다. 위 환경변수 이름과 빈 예제만 공유한다.
A의 `config:cognito-a`, B의 `config:cognito-b`는 서버 비밀 맵을 가리키는 공개 참조다.
Secret이 없으면 해당 회사 로그인 시작이 503으로 실패한다. 다른 회사의 Secret으로 대체하지 않는다.
Cognito 설정 파일은 필수가 아니다. Compose가 만드는 `local-auth.properties`와 JWT 키는 계속 필요하다.
호스트 작업 디렉터리가 앱 디렉터리가 아니면 `PULSEMETRY_DEV_AUTH_DIR`에 인증 키 디렉터리의 절대 경로를 지정한다.

## 로그인 대상과 주소

| 회사 | 사용자 풀 | 테스트 계정 |
|---|---|---|
| A | `pulsemetry-dev-company-a` | `owner@seed-a.example.test`, `admin@seed-a.example.test` |
| B | `pulsemetry-dev-company-b` | `owner@seed-b.example.test` |

비밀번호는 별도 공유하며 JSON에는 저장하지 않는다. 회원 이메일은 합성 테스트 주소다.
버전 2 공개 JSON은 실제 개발 Cognito의 issuer·client ID·비밀 참조만 담는다. 사용자 sub·이메일은 내보내지 않는다. 회원 sub는 최초 SSO 로그인에서 연결한다. 기존 DB의 연결은 보존한다.

- 두 Cognito 앱 클라이언트의 callback: `http://localhost:8080/api/v1/auth/oidc/callback/cognito`
- 프론트 복귀 주소: `http://localhost:3000/auth/callback`

프론트는 기존 BFF 설정을 사용한다. Cognito/프론트 콜백을 서로 바꾸지 않는다.
역할·회원 상태는 서비스 DB가 판단하며, 최초 로그인에서 사전 등록 회원의 검증 이메일로 sub를 연결하고 이후 `(tenant, issuer, sub)`로 확인한다.
로그아웃은 Pulsemetry 세션을 종료하며 Cognito 로그인 세션은 별도로 남을 수 있다.

## 재실행과 충돌

- 아직 OIDC 설정이 없는 시드 회사에만 회사 OIDC 설정을 트랜잭션으로 저장한다. 회원 sub는 시딩하지 않는다.
- 동일 연결을 재실행하면 세션·회원·온보딩·SSO 활성화 상태를 바꾸지 않는다.
- 시드 회사 UUID·slug·ready 기록을 검사한다. 기존 issuer/client/ref가 다르면 전체 OIDC 주입을 거부한다.
- 공개 JSON의 알 수 없는 필드, Secret 필드, 구 버전의 users 필드와 파일 누락도 거부한다.
- 충돌을 해결하려고 DB 볼륨이나 분석 데이터를 지우지 않는다. 원본을 백업하고 해당 회사의 신원 연결만 명시적으로 전환한다.

풀이나 클라이언트를 다시 만들면 새 issuer/client ID로 공개 JSON을 다시 받아야 한다. 기존 회원의 계정이 재생성돼 sub가 바뀌어도 자동 덮어쓰기하지 않으며 운영자가 신원을 확인해 별도로 재연결한다.
공개 JSON을 갱신할 때는 별도 파일로 준비하고 diff를 검토해 교체한다. 삭제된 infra 내보내기 명령을 사용하지 않는다.
이미 시딩한 DB의 다른 연결은 자동 교체하지 않는다. 변경한 회사의 기존 세션과 미사용 단회 코드도 폐기해야 한다.

기존 `switch-oidc`·`configure-oidc` 명령은 구 공유 풀 A/B/C 자료용이다. 회사별 공개 JSON에 사용하지 않는다. 특히 `configure-oidc`의 `config:cognito` 참조는 local의 `cognito-a`·`cognito-b` 비밀 맵과 맞지 않는다. A/B 풀 교체와 복구 절차는 별도 구현·검증이 필요하다.

## 검증 범위

자동 테스트는 실제 PostgreSQL 컨테이너에서 최초 주입·재실행·충돌 시 전체 롤백·데이터 보존을 확인한다.
AWS 계정 생성·DB 설정이 끝나도 호스트 Secret 주입 전에는 로그인할 수 없다.
사용자가 실제 Cognito 브라우저 로그인이 정상 동작함을 수동 확인했다. 모든 A·B 계정과 최초 연결·실패 경계를 브라우저에서 검증했다는 뜻은 아니다. 기존 자동 E2E의 단일 IdP origin·C 계정 가정은 A·B 독립 풀에 맞춘 수정과 재실행이 남아 있다.
