-- 완전 관측으로 판정한 날짜 (ADR 0042). build 때 한 번 계산해 snapshot 에 고정한다 — 같은 snapshot 의 모든 endpoint 가 같은 판정을 쓴다.
-- 판정의 근거(설치·수집 구간·정책 판)는 원천에 있고 여기에는 결과만 둔다. manifest 를 지우면 함께 지워진다.

CREATE TABLE IF NOT EXISTS dashboard_cache.snapshot_complete_days (
    snapshot_id   text NOT NULL,
    complete_date date NOT NULL,
    CONSTRAINT pk_snapshot_complete_days PRIMARY KEY (snapshot_id, complete_date),
    CONSTRAINT fk_snapshot_complete_days_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES dashboard_cache.snapshots (snapshot_id) ON DELETE CASCADE
);

COMMENT ON TABLE dashboard_cache.snapshot_complete_days IS
    'snapshot 의 현재·비교 기간 중 완전 관측으로 판정한 날짜(조회 시간대 기준). 없는 날짜는 완전하지 않다 (ADR 0042)';
