-- ChatGPT Business는 Standard/Premium 좌석을 함께 사용할 수 있다.
-- Enterprise는 계약에 따라 ChatGPT/Codex 좌석을 구분한다.
-- 공식 근거와 제품별 예외: docs/vendor-catalog-evidence.md
-- 이미 적용된 V10과 조직의 계약 이력은 수정하지 않는다.
UPDATE enrollment.vendor_catalog_products
SET allows_seat_tiers = true
WHERE id = 'openai_biz' AND vendor_id = 'openai' AND allows_seat_tiers = false;
