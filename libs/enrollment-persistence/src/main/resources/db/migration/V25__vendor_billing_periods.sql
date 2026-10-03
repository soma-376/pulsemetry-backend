-- 벤더 청구 누계 (ADR 0050). 벤더의 비용·지출 API 가 준 정산 기간별 금액만 담는다 — 환산 비용·계약액·좌석 단가로 채우지 않는다.
-- 기간마다 한 행이고 같은 기간을 다시 읽으면 금액·끝·읽은 시각을 덮는다(벤더가 값을 고칠 수 있다).
CREATE TABLE enrollment.vendor_billing_periods (
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    vendor_id varchar(100) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    amount_usd numeric(24, 8) NOT NULL CHECK (amount_usd >= 0),
    kind varchar(16) NOT NULL CHECK (kind IN ('usage_cost', 'usage_spend')),
    finalized boolean NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('connector', 'seed')),
    connection_id uuid REFERENCES enrollment.vendor_connections(id),
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, vendor_id, period_start),
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES enrollment.managed_vendors(tenant_id, vendor_id),
    CONSTRAINT vendor_billing_periods_range CHECK (period_end > period_start),
    CONSTRAINT vendor_billing_periods_connector CHECK ((source = 'connector') = (connection_id IS NOT NULL))
);

ALTER TABLE enrollment.vendor_connections
    ADD COLUMN last_billing_succeeded_at timestamptz,
    ADD COLUMN last_billing_failed_at timestamptz,
    ADD COLUMN last_billing_error varchar(64),
    ADD CONSTRAINT vendor_connections_billing_error CHECK ((last_billing_failed_at IS NULL) = (last_billing_error IS NULL));

COMMENT ON TABLE enrollment.vendor_billing_periods IS '벤더 청구 누계 (ADR 0050). 벤더 비용·지출 API 의 정산 기간별 금액(USD). 좌석 구독료가 아니다.';
COMMENT ON COLUMN enrollment.vendor_billing_periods.kind IS 'usage_cost: 사용 비용(Claude Enterprise 비용 보고서, 할인 뒤·크레딧 전). usage_spend: 사용 지출(Cursor Enterprise 이번 주기 on-demand).';
COMMENT ON COLUMN enrollment.vendor_billing_periods.finalized IS 'false 면 벤더가 뒤에 고칠 수 있는 값이다(진행 중인 기간, 정산 전).';
COMMENT ON COLUMN enrollment.vendor_billing_periods.source IS 'connector: 커넥터가 벤더에서 읽었다. seed: 개발 시드 — 실제 청구의 증거가 아니다.';
COMMENT ON COLUMN enrollment.vendor_connections.last_billing_succeeded_at IS '청구 누계를 마지막으로 읽은 시각. 좌석 동기화와 같은 실행이 읽지만 결과는 따로 남긴다.';
