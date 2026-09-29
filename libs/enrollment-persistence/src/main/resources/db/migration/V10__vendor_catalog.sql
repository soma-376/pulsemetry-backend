-- 공통 선택지는 조직별 등록·계약과 분리된 기준 데이터다(ADR 0035).
CREATE TABLE enrollment.vendor_catalog_vendors (
    id varchar(50) PRIMARY KEY,
    display_name varchar(100) NOT NULL CHECK (btrim(display_name) <> '')
);
CREATE TABLE enrollment.vendor_catalog_products (
    id varchar(50) PRIMARY KEY,
    vendor_id varchar(50) NOT NULL REFERENCES enrollment.vendor_catalog_vendors(id),
    display_name varchar(100) NOT NULL CHECK (btrim(display_name) <> ''),
    product varchar(200) NOT NULL,
    allows_seat_tiers boolean NOT NULL,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0
);
CREATE TABLE enrollment.vendor_catalog_plans (
    product_id varchar(50) NOT NULL REFERENCES enrollment.vendor_catalog_products(id),
    id varchar(100) NOT NULL,
    display_name varchar(100) NOT NULL CHECK (btrim(display_name) <> ''),
    billing varchar(30) NOT NULL DEFAULT 'seat' CHECK (billing = 'seat'),
    separate_usage_billing boolean NOT NULL DEFAULT true,
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, id)
);
COMMENT ON TABLE enrollment.vendor_catalog_vendors IS '서비스 공통 공급사 목록. 조직 등록은 managed_vendors에 저장한다';
COMMENT ON TABLE enrollment.vendor_catalog_products IS '공통 제품 선택지. id는 기존 managed_vendors.kind와 대응한다';
COMMENT ON TABLE enrollment.vendor_catalog_plans IS '제품별 공통 플랜. 조직별 좌석 수와 단가는 계약 이력에 저장한다';

-- 기존 API의 ID·이름·순서를 유지한다. 이후 DB에서 편집한 값은 기동 시 덮어쓰지 않는다.
INSERT INTO enrollment.vendor_catalog_vendors (id, display_name) VALUES
    ('anthropic', 'Anthropic'), ('openai', 'OpenAI'), ('cursor', 'Cursor'),
    ('github', 'GitHub'), ('google', 'Google'), ('other', '기타');
INSERT INTO enrollment.vendor_catalog_products (id, vendor_id, display_name, product, allows_seat_tiers, sort_order) VALUES
    ('claude_team', 'anthropic', 'Claude (Anthropic)', 'Claude Code · claude.ai', true, 10),
    ('openai_biz', 'openai', 'ChatGPT / Codex (OpenAI)', 'ChatGPT · Codex', false, 20),
    ('cursor', 'cursor', 'Cursor', 'Cursor', true, 30),
    ('copilot', 'github', 'GitHub Copilot', 'GitHub Copilot', false, 40),
    ('gemini', 'google', 'Google Gemini Code Assist', 'Gemini Code Assist', false, 50),
    ('other', 'other', '기타 조직 계약 · 직접 입력', '기타 조직 계약', true, 60);
INSERT INTO enrollment.vendor_catalog_plans (product_id, id, display_name, separate_usage_billing, sort_order) VALUES
    ('claude_team', 'team', 'Team', true, 10),
    ('claude_team', 'enterprise', 'Enterprise', true, 20),
    ('openai_biz', 'business', 'Business', true, 10),
    ('openai_biz', 'enterprise', 'Enterprise', true, 20),
    ('cursor', 'cursor_teams', 'Teams', true, 10),
    ('cursor', 'cursor_enterprise', 'Enterprise', true, 20),
    ('copilot', 'copilot_business', 'Business', true, 10),
    ('copilot', 'copilot_enterprise', 'Enterprise', true, 20),
    ('gemini', 'gemini_standard', 'Standard', false, 10),
    ('gemini', 'gemini_enterprise', 'Enterprise', false, 20),
    ('other', 'seat_flat', '좌석 정액', false, 10),
    ('other', 'seat_usage', '좌석 + 사용량', true, 20);
