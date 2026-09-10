-- CLI 세션의 manifest 제약은 유지하고 웹 세션만 독립한다.
ALTER TABLE enrollment.user_sessions ADD COLUMN session_kind text NOT NULL DEFAULT 'cli';
ALTER TABLE enrollment.user_sessions ALTER COLUMN manifest_revision DROP NOT NULL;
ALTER TABLE enrollment.user_sessions ADD CONSTRAINT user_sessions_kind_manifest CHECK (
    (session_kind = 'cli' AND manifest_revision IS NOT NULL) OR
    (session_kind = 'web' AND manifest_revision IS NULL)
);
