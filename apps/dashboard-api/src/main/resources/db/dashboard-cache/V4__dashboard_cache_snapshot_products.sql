-- build 때의 관측 제품 매핑 (ADR 0044·0045). 분석 snapshot 의 제품 축은 이 복제본으로 관측 제품을 카탈로그 제품에 잇는다 —
-- 같은 snapshot 의 개요·팀 목록·팀 상세가 같은 매핑·같은 표시 이름을 쓴다. manifest 를 지우면 함께 지워진다.

CREATE TABLE IF NOT EXISTS dashboard_cache.snapshot_products (
    snapshot_id      text    NOT NULL,
    observed_product text    NOT NULL,
    product_id       text    NOT NULL,
    display_name     text    NOT NULL,
    sort_order       integer NOT NULL,
    CONSTRAINT pk_snapshot_products PRIMARY KEY (snapshot_id, observed_product),
    CONSTRAINT fk_snapshot_products_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES dashboard_cache.snapshots (snapshot_id) ON DELETE CASCADE
);

COMMENT ON TABLE dashboard_cache.snapshot_products IS
    'build 때의 관측 제품 → 카탈로그 제품 매핑과 카탈로그 표시 이름·순서. 매핑에 없는 관측은 어느 제품에도 넣지 않는다 (ADR 0044·0045)';
