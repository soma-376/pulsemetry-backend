# 사용자 인증 운영

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
