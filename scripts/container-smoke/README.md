# 다섯 앱 컨테이너 smoke

미리 빌드한 Enrollment·Dashboard·ingest·Gateway·Retention 이미지를 실제 PostgreSQL·ClickHouse·Kafka·Mailpit에 연결한다. 호스트 JVM이나 기존 개발 DB를 사용하지 않는다. 이미지 빌드는 [PROJ-306 검증 기록](../../docs/feature-packages-verification.md)의 4절을 따른다.

```sh
python3 scripts/container-smoke/run.py \
  --work /tmp/pulsemetry-container-smoke-new \
  --image-prefix pulsemetry-proj306 \
  --image-tag 403f245
```

필수 도구는 Docker Compose, Python 3.9 이상, openssl이다. 앱 이미지 이름은 `<prefix>/<app>:<tag>`다. 앱 이미지를 내려받거나 게시하지 않으며, 의존 인프라 이미지는 로컬에 없으면 Docker가 받는다. `--work`는 아직 없는 경로여야 한다. Python의 assertion을 끄는 `-O`는 지원하지 않는다.

실행마다 고유한 Compose 프로젝트와 내부 네트워크를 만든다. 호스트 포트도 공개하지 않으며 HTTP 요청은 같은 네트워크의 Python probe가 보낸다. 외부 OIDC·벤더·메일 공급자를 호출하지 않는다. 테스트용 RSA 키·암호화 키·조직·사용자 세션을 새로 만들고, Dashboard의 원천 읽기 계정과 캐시 쓰기 계정을 분리한다. Enrollment가 enrollment·telemetry_ops 마이그레이션을 먼저 적용하고, ingest가 ClickHouse 원천 테이블을 준비한 뒤 Dashboard를 기동한다.

## 검사 내용

- 관리 HTTP: 팀 생성 201·동일 키 재응답·다른 본문의 409·잘못된 팀 이름 400과 명령 기록 미생성. 정책 최초 생성, 일괄 초대와 멱등 재응답, outbox 1건·Mailpit 수신 1건, 설치 등록 201·응답 네 키를 확인한다.
- Dashboard: 미인증 401·인증된 팀 조회, 실제 snapshot 집계의 2,188토큰·2세션·활성 구성원 1명, 섹션 오류 없음을 확인한다.
- 수집: 기존 observability smoke의 독립 관측 ID 계산과 `FINAL` 읽기를 재사용한다. HTTP logs·Gateway→Kafka→ingest logs 각각의 관측 ID·입출력 토큰·archive 참조를 대조한다. Kafka metrics 두 점의 값·archive 참조, 사용한 파티션의 commit과 비어 있는 파티션, 잘못된 JWT의 401도 확인한다.
- Retention: 실제 정책 변경 API가 만든 정리 요청을 `--requests` 이미지 실행으로 처리한다. 오래된 log·metric만 삭제하고 최신 행·다른 조직의 오래된 행은 남긴다. 종료 0·작업 성공·경계 epoch 1·Flyway 이력 불변·과거 logs 재전송의 재삽입 차단을 확인한다.
- 종료: HTTP 앱 네 개의 실제 UID 10001, SIGTERM 종료 코드 0 또는 143, graceful shutdown 완료, ingest consumer 종료, OOM 없음을 확인한다. SIGTERM을 처리한 JVM의 143은 강제 종료 137과 구분한다.

## 증거와 정리

`result.json`이 전체 판정이다. `images.json`, `management-evidence.json`, `telemetry-*/evidence.json`, Prometheus 전후 지표, `dashboard-overview.json`, `consumer-lag.txt`, `retention-evidence.json`, `retention.log`, `shutdown.json`, `containers.log`를 남긴다. 알려진 테스트 자격 값이 컨테이너 로그에 없는지도 검사한다.

성공·실패 모두 로그를 먼저 보존하고 이번 실행의 컨테이너·네트워크·익명 볼륨을 제거한다. 다른 프로젝트와 이미지는 삭제하지 않는다. 결과 디렉터리는 소유자만 접근할 수 있는 권한으로 만들며, 그 안의 `keys`, `state.json`, `compose.json`, `telemetry-token`에는 테스트 자격이 남는다. 이 파일들을 커밋하거나 공유하지 않는다.

검증 범위는 단일 broker·PLAINTEXT, file archive, SMTP와 합성 세션이다. 외부 OIDC 로그인, 실제 벤더·SES·S3·MSK/IAM/mTLS, 운영 권한·부하·진행 중 요청의 종료, 원격 CI·배포 인수를 대신하지 않는다.
