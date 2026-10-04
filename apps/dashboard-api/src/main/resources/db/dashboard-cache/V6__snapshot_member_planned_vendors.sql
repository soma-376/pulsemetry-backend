ALTER TABLE dashboard_cache.snapshot_members ADD COLUMN planned_vendor_ids text[] NOT NULL DEFAULT '{}';
