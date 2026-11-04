-- 설치 보고 공백은 보안·비용과 독립적인 시스템 알림이다(ADR 0076).
ALTER TABLE dashboard_cache.alerts DROP CONSTRAINT ck_alerts_category;
ALTER TABLE dashboard_cache.alerts ADD CONSTRAINT ck_alerts_category
    CHECK (category IN ('cost', 'security', 'collection'));
