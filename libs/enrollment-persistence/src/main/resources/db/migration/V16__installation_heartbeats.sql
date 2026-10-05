-- 설치 보고 (ADR 0040). 데몬 heartbeat의 최신 상태와 수집 구간 이력이다.
-- 시각은 모두 서버 시각이다. 데몬이 보낸 시각은 (받은 시각 − 보낸 시각)만큼 옮겨서 저장한다.
CREATE TABLE enrollment.installation_heartbeats (
    installation_id uuid PRIMARY KEY REFERENCES enrollment.installations(id),
    received_at timestamptz NOT NULL,
    run_id varchar(64) NOT NULL CHECK (btrim(run_id) <> ''),
    daemon_version varchar(64) NOT NULL CHECK (btrim(daemon_version) <> ''),
    architecture varchar(32) NOT NULL CHECK (btrim(architecture) <> ''),
    applied_config_revision integer NOT NULL CHECK (applied_config_revision >= 1),
    applied_manifest_id uuid REFERENCES enrollment.manifests(id),
    mode varchar(8) NOT NULL CHECK (mode IN ('local','direct')),
    forwarding boolean NOT NULL,
    receiving_since timestamptz,
    delivered bigint NOT NULL CHECK (delivered >= 0),
    lost bigint NOT NULL CHECK (lost >= 0),
    pending bigint NOT NULL CHECK (pending >= 0),
    last_delivered_at timestamptz,
    CONSTRAINT installation_heartbeats_times_not_after_receipt CHECK (
        (receiving_since IS NULL OR receiving_since <= received_at)
        AND (last_delivered_at IS NULL OR last_delivered_at <= received_at))
);

CREATE TABLE enrollment.installation_collection_segments (
    id uuid PRIMARY KEY,
    installation_id uuid NOT NULL REFERENCES enrollment.installations(id),
    run_id varchar(64) NOT NULL CHECK (btrim(run_id) <> ''),
    from_at timestamptz NOT NULL,
    to_at timestamptz NOT NULL,
    lost bigint NOT NULL DEFAULT 0 CHECK (lost >= 0),
    CONSTRAINT installation_collection_segments_ordered CHECK (from_at <= to_at)
);
CREATE INDEX installation_collection_segments_idx ON enrollment.installation_collection_segments (installation_id, to_at);

COMMENT ON TABLE enrollment.installation_heartbeats IS '설치가 마지막으로 보낸 보고 한 건 (ADR 0040). 설치당 한 행.';
COMMENT ON COLUMN enrollment.installation_heartbeats.received_at IS '서버가 받은 시각. 생존 시각이다.';
COMMENT ON COLUMN enrollment.installation_heartbeats.run_id IS '데몬 프로세스의 식별자. 바뀌면 누적 개수가 0부터 다시 시작한다.';
COMMENT ON COLUMN enrollment.installation_heartbeats.applied_config_revision IS '데몬이 집행 중이라고 보고한 manifest 판.';
COMMENT ON COLUMN enrollment.installation_heartbeats.applied_manifest_id IS '보고한 판이 그 조직의 manifest일 때만 채운다. NULL이면 서버가 모르는 판이다.';
COMMENT ON COLUMN enrollment.installation_heartbeats.receiving_since IS '로컬 수신기가 끊김 없이 듣기 시작한 시각(서버 시각으로 옮긴 값).';
COMMENT ON COLUMN enrollment.installation_heartbeats.lost IS '그 프로세스가 뜬 뒤로 회사에 전달하지 못하고 버린 페이로드 수(누적).';
COMMENT ON TABLE enrollment.installation_collection_segments IS '설치가 수집 중이었다고 보고로 확인된 구간 (ADR 0040). 완전성 판정의 근거이지 증명이 아니다.';
COMMENT ON COLUMN enrollment.installation_collection_segments.to_at IS '이 구간을 확인한 마지막 보고를 받은 시각.';
COMMENT ON COLUMN enrollment.installation_collection_segments.lost IS '이 구간에서 잃은 페이로드 수. 0이 아닌 구간은 손실이 있던 구간이다.';
