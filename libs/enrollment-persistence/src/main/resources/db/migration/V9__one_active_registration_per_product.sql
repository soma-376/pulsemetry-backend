-- 최신 버전의 보관 상태를 현재 등록에 반영한다. 기존 계약 이력은 변경하지 않는다.
ALTER TABLE enrollment.managed_vendors ADD COLUMN archived boolean NOT NULL DEFAULT false;
UPDATE enrollment.managed_vendors v SET archived = c.archived
FROM (
    SELECT DISTINCT ON (tenant_id, vendor_id) tenant_id, vendor_id, archived
    FROM enrollment.vendor_contract_versions ORDER BY tenant_id, vendor_id, version DESC
) c WHERE v.tenant_id=c.tenant_id AND v.vendor_id=c.vendor_id;

-- 중복이 있으면 데이터 소유자의 판단이 필요하다. 임의 병합/삭제 없이 전체 마이그레이션을 롤백한다.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM enrollment.managed_vendors WHERE NOT archived GROUP BY tenant_id, kind HAVING count(*) > 1) THEN
        RAISE EXCEPTION '중복 제품 등록이 있습니다. 조직별 활성 managed_vendors의 kind 중복을 검토한 뒤 다시 실행하세요.';
    END IF;
END $$;
CREATE UNIQUE INDEX uq_managed_vendors_active_product ON enrollment.managed_vendors (tenant_id, kind) WHERE NOT archived;
