package com.team376.pulsemetry.devseed

/** 로컬 대시보드 계정과 캐시 DB만 준비한다. 테이블 DDL은 앱의 기존 마이그레이션이 적용한다. */
internal fun prepareDashboardStore(store: SeedStore) {
        store.execute("""DO $$ BEGIN
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='pulsemetry_dashboard_source') THEN
            CREATE ROLE pulsemetry_dashboard_source LOGIN PASSWORD 'local-dashboard-source';
          END IF;
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='pulsemetry_dashboard_cache') THEN
            CREATE ROLE pulsemetry_dashboard_cache LOGIN PASSWORD 'local-dashboard-cache';
          END IF;
        END $$;
        -- 캐시 Flyway의 CREATE SCHEMA IF NOT EXISTS도 DB CREATE 권한을 검사한다.
        GRANT CREATE ON DATABASE pulsemetry TO pulsemetry_dashboard_cache;
        CREATE SCHEMA IF NOT EXISTS dashboard_cache AUTHORIZATION pulsemetry_dashboard_cache;
        GRANT USAGE ON SCHEMA enrollment,telemetry_ops TO pulsemetry_dashboard_source;
        GRANT SELECT ON ALL TABLES IN SCHEMA enrollment,telemetry_ops TO pulsemetry_dashboard_source;
        ALTER DEFAULT PRIVILEGES FOR ROLE pulsemetry IN SCHEMA enrollment,telemetry_ops GRANT SELECT ON TABLES TO pulsemetry_dashboard_source;
        GRANT USAGE ON SCHEMA telemetry_ops TO pulsemetry_dashboard_cache;
        GRANT SELECT ON telemetry_ops.tenant_retention_boundary TO pulsemetry_dashboard_cache;
        """)
        listOf("CREATE DATABASE IF NOT EXISTS dashboard_cache",
            "CREATE USER IF NOT EXISTS pulsemetry_dashboard_source IDENTIFIED BY 'local-dashboard-source'",
            "CREATE USER IF NOT EXISTS pulsemetry_dashboard_cache IDENTIFIED BY 'local-dashboard-cache'",
            "GRANT SELECT ON default.* TO pulsemetry_dashboard_source",
            "GRANT SELECT ON default.* TO pulsemetry_dashboard_cache",
            "GRANT ALL ON dashboard_cache.* TO pulsemetry_dashboard_cache").forEach(store::clickHouse)
    println("로컬 대시보드 읽기 계정·캐시 계정 준비 완료")
}
