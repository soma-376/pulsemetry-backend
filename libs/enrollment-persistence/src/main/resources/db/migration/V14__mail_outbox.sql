-- 메일 outbox (ADR 0037). 업무 쓰기와 같은 트랜잭션에서 적재하고 발송 작업이 선점해 보낸다.
-- 본문은 암호문으로만, 끝나지 않은 동안만 있다. 제목·수신자·종류는 평문이다.
CREATE TABLE enrollment.mail_outbox (
    id uuid PRIMARY KEY,
    dedup_key varchar(200) NOT NULL UNIQUE CHECK (btrim(dedup_key) <> ''),
    kind varchar(40) NOT NULL CHECK (btrim(kind) <> ''),
    recipient varchar(320) NOT NULL CHECK (btrim(recipient) <> ''),
    subject varchar(200) NOT NULL CHECK (btrim(subject) <> ''),
    encrypted_body text,
    status varchar(16) NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','sending','sent','failed','cancelled')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    locked_until timestamptz,
    failure_code varchar(40),
    failure_detail varchar(200),
    queued_at timestamptz NOT NULL,
    last_attempt_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT mail_outbox_body_while_open CHECK ((status IN ('queued','sending')) = (encrypted_body IS NOT NULL)),
    CONSTRAINT mail_outbox_finished_when_closed CHECK ((status IN ('sent','failed','cancelled')) = (finished_at IS NOT NULL)),
    CONSTRAINT mail_outbox_lease_while_sending CHECK ((status = 'sending') = (locked_until IS NOT NULL))
);
-- 발송 작업이 보는 것은 끝나지 않은 메일뿐이다.
CREATE INDEX mail_outbox_open_idx ON enrollment.mail_outbox (next_attempt_at) WHERE status IN ('queued','sending');

COMMENT ON TABLE enrollment.mail_outbox IS '메일 발송 대기열과 결과 (ADR 0037).';
COMMENT ON COLUMN enrollment.mail_outbox.dedup_key IS '메일의 정체. 같은 키로 다시 적재하면 새 행을 만들지 않는다.';
COMMENT ON COLUMN enrollment.mail_outbox.subject IS '평문이다. 비밀을 넣지 않는다.';
COMMENT ON COLUMN enrollment.mail_outbox.encrypted_body IS 'AES-256-GCM 암호문(행 ID가 AAD). sent·failed·cancelled가 되면 NULL.';
COMMENT ON COLUMN enrollment.mail_outbox.locked_until IS 'sending 선점의 임대 만료 시각. 지나면 다른 작업이 다시 선점한다.';
COMMENT ON COLUMN enrollment.mail_outbox.failure_code IS '실패 분류 코드. 재시도 대기 중에는 마지막 시도의 사유.';
COMMENT ON COLUMN enrollment.mail_outbox.failure_detail IS 'SMTP 응답 코드. 서버 응답 원문은 저장하지 않는다.';
