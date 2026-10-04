ALTER TABLE enrollment.members ADD COLUMN planned_vendor_ids text[] NOT NULL DEFAULT '{}';
ALTER TABLE enrollment.members ADD CONSTRAINT ck_member_planned_vendors_limit CHECK (cardinality(planned_vendor_ids) <= 100);
COMMENT ON COLUMN enrollment.members.planned_vendor_ids IS '관리자가 지정한 사용 예정 제품. 실제 좌석 배정·접근 권한이 아니다';
