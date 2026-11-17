// 부하 시험 설정 — 환경변수를 읽어 검증한다(ADR 0082). 기본값은 파이프라인 계단 시나리오(5→60 RPS, +5씩, 유지 5분)다.
// 잘못된 값은 init 에서 예외로 끝낸다(k6 종료 코드 107).

// telemetry JWT 수명 3h(10800s)에서 setup·종료 여유 600s를 뺀 값. 실행 1회의 계획 시간 상한이다.
export const MAX_TOTAL_SECONDS = 10200;
export const MAX_RPS_LIMIT = 1000;
export const SCENARIOS = ['smoke', 'stair', 'hold'];
export const SIGNALS = ['logs', 'metrics', 'traces'];
export const PRODUCTS = ['claude-code', 'codex'];

// Helm 차트는 3~40자를 강제한다. 스크립트 단독 점검(`k6 inspect`)을 위해 여기서는 1자부터 받는다.
const TESTID_PATTERN = /^[a-z0-9][a-z0-9-]{0,39}$/;
const DURATION_PATTERN = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/;

export const DEFAULTS = {
	SCENARIO: 'smoke',
	GATEWAY_URL: 'http://telemetry-gateway.pulsemetry-dev.svc:8082',
	ENROLLMENT_URL: 'http://enrollment-api.pulsemetry-dev.svc:8080',
	START_RPS: '5',
	STEP_RPS: '5',
	MAX_RPS: '60',
	RAMP: '30s',
	STEP_HOLD: '5m',
	COOLDOWN: '30s',
	HOLD_DURATION: '20m',
	MIX_SIGNAL: 'logs:70,metrics:20,traces:10',
	MIX_PRODUCT: 'claude-code:50,codex:50',
	GZIP: '1',
};

function invalid(message) {
	return new Error(`설정 오류: ${message}`);
}

/** "90"·"30s"·"5m"·"1h"·"1m30s" → 초(정수). */
export function parseDuration(text, name) {
	const value = String(text).trim();
	if (/^\d+$/.test(value)) return Number(value);
	const match = DURATION_PATTERN.exec(value);
	if (!value || !match) throw invalid(`${name}=${value} 는 90·30s·5m·1h·1m30s 꼴이어야 한다`);
	return Number(match[1] || 0) * 3600 + Number(match[2] || 0) * 60 + Number(match[3] || 0);
}

function parseInteger(text, name, min, max) {
	const value = String(text).trim();
	if (!/^\d+$/.test(value)) throw invalid(`${name}=${value} 는 정수여야 한다`);
	const number = Number(value);
	if (number < min || number > max) throw invalid(`${name}=${number} 는 ${min}~${max} 이어야 한다`);
	return number;
}

/** "logs:70,metrics:20,traces:10" → {logs: 70, metrics: 20, traces: 10}. 빠진 키는 0, 합은 0보다 커야 한다. */
export function parseMix(text, name, allowed) {
	const weights = {};
	for (const key of allowed) weights[key] = 0;
	const seen = {};
	for (const part of String(text).split(',')) {
		const match = /^\s*([a-z-]+)\s*:\s*(\d+)\s*$/.exec(part);
		if (!match || allowed.indexOf(match[1]) < 0 || seen[match[1]]) {
			throw invalid(`${name} 의 항목 "${part}" 는 ${allowed.join('|')}:<정수> 꼴이고 한 번씩만 쓴다`);
		}
		seen[match[1]] = true;
		weights[match[1]] = Number(match[2]);
	}
	if (allowed.reduce((sum, key) => sum + weights[key], 0) <= 0) throw invalid(`${name} 의 가중치 합이 0이다`);
	return weights;
}

function parseUrl(text, name) {
	const value = String(text).trim().replace(/\/+$/, '');
	if (!/^https?:\/\/[^\s/]+(\/[^\s]*)?$/.test(value)) throw invalid(`${name}=${value} 는 http(s) URL 이어야 한다`);
	return value;
}

function parseFlag(text, name) {
	const value = String(text).trim().toLowerCase();
	if (['1', 'true', 'yes'].indexOf(value) >= 0) return true;
	if (['0', 'false', 'no'].indexOf(value) >= 0) return false;
	throw invalid(`${name}=${value} 는 1 또는 0 이어야 한다`);
}

/** k6 `__ENV` 같은 객체를 받아 검증된 설정을 돌려준다. 비밀 값은 담지 않는다(파일 경로만). */
export function loadConfig(env) {
	const get = (name) => (env[name] !== undefined && env[name] !== '' ? env[name] : DEFAULTS[name]);
	const testid = env.TESTID;
	if (!testid || !TESTID_PATTERN.test(testid)) throw invalid(`TESTID 가 필요하다(^[a-z0-9][a-z0-9-]{0,39}$): ${testid}`);
	const scenario = get('SCENARIO');
	if (SCENARIOS.indexOf(scenario) < 0) throw invalid(`SCENARIO=${scenario} 는 ${SCENARIOS.join('|')} 중 하나다`);

	const config = {
		testid,
		scenario,
		gatewayUrl: parseUrl(get('GATEWAY_URL'), 'GATEWAY_URL'),
		enrollmentUrl: parseUrl(get('ENROLLMENT_URL'), 'ENROLLMENT_URL'),
		identitiesFile: env.IDENTITIES_FILE || '',
		seedSecretFile: env.SEED_SECRET_FILE || '',
		templatesFile: env.TEMPLATES_FILE || '',
		summaryFile: env.SUMMARY_FILE || '',
		startRps: parseInteger(get('START_RPS'), 'START_RPS', 1, MAX_RPS_LIMIT),
		stepRps: parseInteger(get('STEP_RPS'), 'STEP_RPS', 1, MAX_RPS_LIMIT),
		maxRps: parseInteger(get('MAX_RPS'), 'MAX_RPS', 1, MAX_RPS_LIMIT),
		rampSeconds: parseDuration(get('RAMP'), 'RAMP'),
		stepHoldSeconds: parseDuration(get('STEP_HOLD'), 'STEP_HOLD'),
		cooldownSeconds: parseDuration(get('COOLDOWN'), 'COOLDOWN'),
		holdRps: env.HOLD_RPS ? parseInteger(env.HOLD_RPS, 'HOLD_RPS', 1, MAX_RPS_LIMIT) : null,
		holdSeconds: parseDuration(get('HOLD_DURATION'), 'HOLD_DURATION'),
		mixSignal: parseMix(get('MIX_SIGNAL'), 'MIX_SIGNAL', SIGNALS),
		mixProduct: parseMix(get('MIX_PRODUCT'), 'MIX_PRODUCT', PRODUCTS),
		gzip: parseFlag(get('GZIP'), 'GZIP'),
	};
	if (config.startRps > config.maxRps) throw invalid(`START_RPS(${config.startRps}) 가 MAX_RPS(${config.maxRps}) 보다 크다`);
	// 0초 상승 구간은 ramping-arrival-rate 에서 앞 단계와 이어져 의도와 다르게 보간된다. 1초 이상만 받는다.
	if (config.rampSeconds < 1) throw invalid('RAMP 는 1초 이상이다');
	if (config.stepHoldSeconds < 1 || config.holdSeconds < 1) throw invalid('STEP_HOLD·HOLD_DURATION 은 1초 이상이다');
	if (scenario === 'hold' && config.holdRps === null) throw invalid('SCENARIO=hold 에는 HOLD_RPS 가 필요하다');
	return config;
}
