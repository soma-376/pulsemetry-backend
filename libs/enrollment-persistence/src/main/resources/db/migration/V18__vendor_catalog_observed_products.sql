-- 관측 제품과 카탈로그 제품의 명시 매핑 (ADR 0044). 분석 행의 `product`(수집 어댑터의 어휘)가 어느 카탈로그 제품의 도구인지만 말한다.
-- 그 사용이 그 제품의 좌석 계약으로 청구된다거나 좌석이 배정됐다는 뜻이 아니다. 공급사·모델 이름으로 추정해 행을 더하지 않는다.
-- 관측 제품 하나는 카탈로그 제품 하나에만 잇는다 — 한 관측을 두 등록 제품에 세지 않는다. `unknown` 은 잇지 않는다(근거가 없다).
-- 카탈로그와 같이 Flyway 만 쓴다. 편집 API 는 없다(ADR 0035).

CREATE TABLE enrollment.vendor_catalog_observed_products (
    observed_product varchar(50) PRIMARY KEY CHECK (observed_product <> 'unknown' AND btrim(observed_product) <> ''),
    product_id varchar(50) NOT NULL REFERENCES enrollment.vendor_catalog_products(id)
);

INSERT INTO enrollment.vendor_catalog_observed_products (observed_product, product_id) VALUES
    ('claude_code', 'claude_team'),
    ('codex', 'openai_biz');

COMMENT ON TABLE enrollment.vendor_catalog_observed_products IS
    '분석 행의 관측 제품(product)이 어느 카탈로그 제품의 도구인지. 청구·좌석 배정의 근거가 아니다. 매핑 없는 관측은 어떤 등록 제품에도 귀속하지 않는다 (ADR 0044)';
