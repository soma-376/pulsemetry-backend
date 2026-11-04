-- 늦게 커밋된 heartbeat가 동일 windowEnd의 요약·상세를 바꾸지 못하게 원천 읽기 결과를 고정한다(ADR 0076).
CREATE TABLE dashboard_cache.transmission_loss_snapshots (
    tenant_id uuid NOT NULL,
    -- Instant의 나노초 정밀도를 잃지 않도록 정규 ISO 문자열을 쓴다. 만료 비교는 expires_at이 맡는다.
    window_end varchar(40) NOT NULL,
    expires_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    PRIMARY KEY (tenant_id, window_end)
);
CREATE INDEX transmission_loss_snapshots_expiry ON dashboard_cache.transmission_loss_snapshots(expires_at);
COMMENT ON TABLE dashboard_cache.transmission_loss_snapshots IS '최근 168시간 손실 요약과 기기별 목록을 함께 고정한 10분 캐시. 원천 계정은 접근하지 않는다.';
