-- 허브 ADR 0008. 이전 알림과 확인 기록은 남기고 열린 묶음만 닫는다.
UPDATE dashboard_cache.alerts SET status = 'closed', version = version + 1, updated_at = now()
    WHERE rule_id IN ('model_not_allowed', 'tool_unapproved') AND status = 'open';
