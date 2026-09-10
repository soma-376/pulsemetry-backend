-- 실행 입력·권한 스냅샷과 워커 소유권을 보존한다. 결과는 lease를 소유한 워커만 저장한다.
ALTER TABLE dashboard.scenario_runs ADD COLUMN execution jsonb NOT NULL DEFAULT '{}';
ALTER TABLE dashboard.scenario_runs ADD COLUMN claim_token uuid;
ALTER TABLE dashboard.scenario_runs ADD COLUMN progress_step integer NOT NULL DEFAULT 0;
CREATE INDEX scenario_runs_queue ON dashboard.scenario_runs(created_at,id) WHERE status='queued';
