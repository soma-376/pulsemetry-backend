-- 완전성 판정과 같은 snapshot에 상세 사유를 고정한다 (허브 ADR 0018).
CREATE TABLE dashboard_cache.snapshot_incomplete_days (
    snapshot_id text NOT NULL REFERENCES dashboard_cache.snapshots(snapshot_id) ON DELETE CASCADE,
    observed_date date NOT NULL,
    reasons text NOT NULL,
    PRIMARY KEY (snapshot_id, observed_date)
);
COMMENT ON COLUMN dashboard_cache.snapshot_incomplete_days.reasons IS '쉼표로 구분한 서버 사유 코드. 완전한 날짜는 이 표에 없다.';
