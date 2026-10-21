-- telemetry token 의 자격 기록 (ADR 0065 Decision 4). 추가형이다 — 기존 행과 구 이미지의 쓰기가 그대로 통과한다.
--
-- 번호가 V33 인 이유: develop 최신은 V31 이고, 다른 로컬 작업(synthetic 데이터셋)이 V32 를 쓰고 있다.
-- 적용 순서가 엇갈려도 충돌하지 않게 그 다음 번호를 쓴다. 이미 적용된 마이그레이션은 고치지 않는다.
--
-- token_format  opaque(ptt_) | jwt. 기존 행과 구 이미지가 넣는 행은 기본값 opaque 다.
-- expires_at    jwt 의 exp. opaque 는 만료가 없어 NULL 이다.
-- signing_kid   jwt 를 서명한 키의 kid. Consumer 가 envelope 의 kid 와 대조한다.
-- revoke_reason rotated(정상 회전) | security(보안 폐기). NULL 은 사유 없음 — 이 마이그레이션 전의 폐기와
--               구 이미지의 회전이 그렇다. Consumer 는 사유 없는 폐기를 거부한다.
--
-- "폐기 시각이 있으면 사유가 있다"는 CHECK 로 걸지 않는다. 구 enrollment 이미지로 되돌리면 그 이미지의 회전
-- UPDATE 가 사유 없이 쓰므로 CHECK 가 재발급을 막는다. 새 코드는 언제나 사유를 쓴다(ADR 0065 Decision 4).
ALTER TABLE enrollment.telemetry_tokens
    ADD COLUMN token_format  varchar(16)  NOT NULL DEFAULT 'opaque',
    ADD COLUMN expires_at    timestamptz,
    ADD COLUMN signing_kid   varchar(128),
    ADD COLUMN revoke_reason varchar(16);

ALTER TABLE enrollment.telemetry_tokens
    ADD CONSTRAINT ck_telemetry_tokens_token_format
        CHECK (token_format IN ('opaque', 'jwt')),
    -- 사유가 있으면 폐기 시각도 있다. 보안 폐기는 폐기 시각을 반드시 채워야 구 ingest 이미지도 거부한다.
    ADD CONSTRAINT ck_telemetry_tokens_revoke_reason
        CHECK (revoke_reason IS NULL OR (revoke_reason IN ('rotated', 'security') AND revoked_at IS NOT NULL)),
    -- jwt 는 만료와 서명 kid 가 있어야 대조할 수 있다. 만료는 발급보다 뒤다.
    ADD CONSTRAINT ck_telemetry_tokens_jwt_claims
        CHECK (token_format <> 'jwt' OR (expires_at IS NOT NULL AND signing_kid IS NOT NULL AND expires_at > issued_at));

COMMENT ON COLUMN enrollment.telemetry_tokens.token_format IS 'opaque(ptt_) 또는 jwt. jwt 의 id 는 JWT jti 와 같다 (ADR 0065)';
COMMENT ON COLUMN enrollment.telemetry_tokens.expires_at IS 'jwt 의 exp. opaque 는 NULL';
COMMENT ON COLUMN enrollment.telemetry_tokens.signing_kid IS 'jwt 를 서명한 키의 kid. opaque 는 NULL';
COMMENT ON COLUMN enrollment.telemetry_tokens.revoke_reason IS 'rotated 또는 security. NULL 이면서 revoked_at 이 있으면 사유 불명 폐기다 (ADR 0065)';
COMMENT ON COLUMN enrollment.telemetry_tokens.token_hash IS 'Bearer 전문의 HMAC-SHA256. jwt 도 저장한다 — 구 ingest 롤백 호환 (ADR 0065)';
