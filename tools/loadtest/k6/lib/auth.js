// 설치 토큰(pit_) 파생과 telemetry JWT 교환(ADR 0082 Decision 3, ADR 0065).
// pit_ 은 generator/identities.py 와 같은 HMAC 이다 — 바꾸면 두 쪽과 테스트 벡터를 함께 고친다.
// 토큰·pit_ 은 로그·오류 메시지·summary 에 쓰지 않는다.
import crypto from 'k6/crypto';
import http from 'k6/http';
import { fail } from 'k6';

export const PREFIX = 'pulsemetry-loadtest/v1';
export const SECRET_BYTES = 32;
const CAMPAIGN_PATTERN = /^[a-z0-9-]{3,40}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** `"pit_" + base64url_nopad(HMAC_SHA256(secret, "pulsemetry-loadtest/v1/<campaign>/<installationId>"))`. */
export function installationToken(secret, campaign, installationId) {
	if (!secret || secret.byteLength !== SECRET_BYTES) throw new Error(`시드 비밀은 정확히 ${SECRET_BYTES}바이트여야 한다`);
	return 'pit_' + crypto.hmac('sha256', secret, `${PREFIX}/${campaign}/${installationId}`, 'base64rawurl');
}

/** seed_sql.py --identities-out 의 JSON. 비밀은 없다. */
export function parseIdentities(text) {
	const document = JSON.parse(text);
	if (!document || !CAMPAIGN_PATTERN.test(document.campaign || '')) throw new Error('identities: campaign 이 없거나 형식이 아니다');
	if (!UUID_PATTERN.test(document.tenantId || '')) throw new Error('identities: tenantId 가 UUID 가 아니다');
	if (!Array.isArray(document.installations) || document.installations.length === 0) throw new Error('identities: installations 가 비었다');
	document.installations.forEach((item, index) => {
		if (!item || !UUID_PATTERN.test(item.installationId || '')) throw new Error(`identities: installations[${index}].installationId 가 UUID 가 아니다`);
	});
	return { campaign: document.campaign, tenantId: document.tenantId, installations: document.installations.map((i) => i.installationId) };
}

/**
 * 설치마다 `POST /api/v1/installations/telemetry-token` 을 차례로 부른다. 하나라도 실패하면 fail() — 시험을 시작하지 않는다.
 * 발급하면 그 설치의 기존 활성 토큰은 rotated 가 된다(ADR 0065). 같은 캠페인으로 Job 두 개를 동시에 돌리지 않는다.
 */
export function exchangeTokens(enrollmentUrl, identities, secret) {
	const url = `${enrollmentUrl}/api/v1/installations/telemetry-token`;
	const tokens = [];
	identities.installations.forEach((installationId, index) => {
		const response = http.post(url, null, {
			headers: { Authorization: `Bearer ${installationToken(secret, identities.campaign, installationId)}` },
			tags: { name: 'telemetry-token', phase: 'setup' },
			// main.js 는 discardResponseBodies 를 켠다. 이 응답만 본문을 받는다.
			responseType: 'text',
			timeout: '30s',
		});
		const where = `설치 ${index + 1}/${identities.installations.length} (${installationId})`;
		if (response.status !== 200) fail(`토큰 교환 실패: ${where} 상태 ${response.status} — 시드·시드 비밀·ENROLLMENT_URL 을 확인한다`);
		let body;
		try {
			body = JSON.parse(response.body);
		} catch (error) {
			fail(`토큰 교환 응답이 JSON 이 아니다: ${where}`);
		}
		if (body.installation_id !== installationId || typeof body.telemetry_token !== 'string') fail(`토큰 교환 응답의 설치 ID·토큰이 맞지 않는다: ${where}`);
		// ADR 0065: 발급 설정이 켜져 있어야 JWT 다. 꺼져 있으면 ptt_ 가 와서 Gateway 가 401 로 거부한다.
		if (body.telemetry_token.indexOf('eyJ') !== 0) fail(`telemetry JWT 가 아니다(발급 설정 꺼짐?): ${where}`);
		tokens.push(body.telemetry_token);
	});
	return tokens;
}
