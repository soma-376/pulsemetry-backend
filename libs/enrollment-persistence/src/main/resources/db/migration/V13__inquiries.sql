-- 도입 문의 접수. 조직에 속하지 않는 공개 접수라 tenant 를 가리키지 않는다.
-- 상태는 dbml 의 enum 이 아니라 이 서버만 쓰는 값이라 V6·V10 처럼 varchar + CHECK 로 둔다. 지금은 접수 하나뿐이다.
CREATE TABLE enrollment.inquiries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company varchar(100) NOT NULL CHECK (btrim(company) <> ''),
    email varchar(320) NOT NULL CHECK (email <> '' AND email = lower(btrim(email))),
    status varchar(16) NOT NULL DEFAULT 'received' CHECK (status IN ('received')),
    request_hash varchar(64) NOT NULL,
    source_ip_hash varchar(64) NOT NULL,
    received_at timestamptz NOT NULL
);
CREATE INDEX inquiries_request_hash_received_at_idx ON enrollment.inquiries (request_hash, received_at DESC);

CREATE TABLE enrollment.inquiry_attempts (
    subject_hash varchar(64) PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

COMMENT ON TABLE enrollment.inquiries IS '도입 문의 접수. 조직·계정·초대를 만들지 않는다.';
COMMENT ON COLUMN enrollment.inquiries.email IS '앞뒤 공백을 떼고 소문자로 바꾼 주소.';
COMMENT ON COLUMN enrollment.inquiries.request_hash IS '정규화한 회사명과 이메일의 SHA-256. 짧은 시간 안의 재전송을 같은 접수로 본다.';
COMMENT ON COLUMN enrollment.inquiries.source_ip_hash IS '출처 주소의 SHA-256. 원문 IP 를 저장하지 않는다.';
COMMENT ON TABLE enrollment.inquiry_attempts IS '해시한 출처별 문의 요청 수 제한. 원문 IP 를 저장하지 않는다.';
