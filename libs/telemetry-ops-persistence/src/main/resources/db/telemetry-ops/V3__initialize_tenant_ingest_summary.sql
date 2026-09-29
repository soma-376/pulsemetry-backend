-- 신규 조직의 빈 수집 상태를 조직 생성과 원자적으로 기록한다(ADR 0034).
-- enrollment 마이그레이션 이후에 적용한다. 기존 조직의 누락된 이력은 소급해서 빈 상태로 만들지 않는다.
CREATE FUNCTION telemetry_ops.initialize_tenant_ingest_summary()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    INSERT INTO telemetry_ops.tenant_ingest_summary (tenant_id)
    VALUES (NEW.id)
    ON CONFLICT (tenant_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER initialize_tenant_ingest_summary
AFTER INSERT ON enrollment.tenants
FOR EACH ROW EXECUTE FUNCTION telemetry_ops.initialize_tenant_ingest_summary();
