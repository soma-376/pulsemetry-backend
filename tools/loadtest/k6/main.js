// PROJ-192 dev 파이프라인 계단식 부하(ADR 0082, infra ADR 0064).
//
//   k6 run --address 127.0.0.1:6565 -o opentelemetry main.js
//
// 필수 env: TESTID, IDENTITIES_FILE(seed_sql.py --identities-out), SEED_SECRET_FILE(32바이트). 나머지는 lib/config.js.
// setup() 이 설치마다 pit_ → telemetry JWT 를 교환하고, 각 반복이 실제 OTLP 템플릿을 채워 Gateway 에 보낸다.
// Gateway 200 은 Kafka 접수다 — 저장 확인은 ClickHouse FINAL 조회로 따로 한다. summary 의 expected* 가 그 대조값이다.
import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import { fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Gauge } from 'k6/metrics';
import { loadConfig } from './lib/config.js';
import { buildPlan, phaseAt } from './lib/schedule.js';
import { makePicker, prepareTemplates, render, requestBaseNanos, requestSequence, SEQUENCE_BYTES } from './lib/payload.js';
import { exchangeTokens, parseIdentities, SECRET_BYTES } from './lib/auth.js';
import { buildSummary, stepMetricNames, wrap } from './lib/summary.js';

// 같은 VU 에서 2xx 가 아닌 응답이 이만큼 이어지면 시험을 멈춘다(k6 thresholds 는 1분 지연 평가라 그 사이의 폭주를 막는다).
const MAX_CONSECUTIVE_FAILURES = 50;

const config = loadConfig(__ENV);
const plan = buildPlan(config);
const templates = new SharedArray('templates', () => prepareTemplates(open(config.templatesFile || './data/templates.json')));
const pick = makePicker(templates, config.mixSignal, config.mixProduct);
// 파일이 없으면 init 은 통과하고 setup 이 멈춘다 — `k6 inspect` 가 시드 없이도 계획을 보여 주게.
const identitiesText = config.identitiesFile ? open(config.identitiesFile) : null;
const seedSecret = config.seedSecretFile ? open(config.seedSecretFile, 'b') : null;

const targetRps = new Gauge('loadtest_target_rps');
const acceptedRequests = new Counter('loadtest_accepted_requests');
const expectedEvents = new Counter('loadtest_expected_events');
const expectedMetricPoints = new Counter('loadtest_expected_metric_points');

function thresholds() {
	const abort = (threshold) => ({ threshold, abortOnFail: true, delayAbortEval: '1m' });
	const result = {
		http_req_failed: [abort('rate<0.05')],
		http_req_duration: [abort('p(95)<4000')],
		dropped_iterations: [abort('count<3000')],
	};
	// 판정용 단계별 기록. 실패시키지 않는 값이다 — summary 에 단계별 값이 남게 하는 등록이다.
	for (const step of plan.steps) {
		const names = stepMetricNames(step.step);
		result[names.duration] = ['p(95)<600000'];
		result[names.failed] = ['rate<=1'];
		result[names.requests] = ['count>=0'];
		result[names.expectedEvents] = ['count>=0'];
		result[names.expectedMetricPoints] = ['count>=0'];
	}
	return result;
}

export const options = {
	scenarios: { [plan.scenarioName]: plan.scenario },
	thresholds: thresholds(),
	tags: { testid: config.testid },
	systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
	summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
	setupTimeout: '5m',
	discardResponseBodies: true,
};

export function setup() {
	if (!identitiesText || !seedSecret) fail('IDENTITIES_FILE·SEED_SECRET_FILE 이 필요하다');
	if (seedSecret.byteLength !== SECRET_BYTES) fail(`시드 비밀은 정확히 ${SECRET_BYTES}바이트여야 한다: ${seedSecret.byteLength}`);
	const identities = parseIdentities(identitiesText);
	const tokens = exchangeTokens(config.enrollmentUrl, identities, seedSecret);
	return {
		campaign: identities.campaign,
		tenantId: identities.tenantId,
		installationCount: identities.installations.length,
		tokenExchanges: tokens.length,
		tokens,
		setupEndMs: Date.now(),
	};
}

let consecutiveFailures = 0;

export default function (data) {
	const now = Date.now();
	const where = phaseAt(plan, (now - exec.scenario.startTime) / 1000);
	targetRps.add(where.targetRps);

	const template = templates[pick()];
	const bytes = new Uint8Array(crypto.randomBytes(template.idChars + SEQUENCE_BYTES));
	const base = requestBaseNanos(now, requestSequence(exec.vu.idInTest, exec.vu.iterationInScenario));
	const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length];
	const tags = { step: where.step, phase: where.phase, signal: template.signal, product: template.product, name: template.path };

	const params = { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, tags, timeout: '10s' };
	if (config.gzip) params.compression = 'gzip';
	const response = http.post(`${config.gatewayUrl}${template.path}`, render(template, base, bytes), params);

	if (response.status === 200) {
		consecutiveFailures = 0;
		const counted = { step: where.step, signal: template.signal, product: template.product };
		acceptedRequests.add(1, counted);
		expectedEvents.add(template.expected.events, counted);
		expectedMetricPoints.add(template.expected.metricPoints, counted);
		return;
	}
	if (response.status === 401) exec.test.abort(`401 — Gateway 가 telemetry JWT 를 거부했다(${template.path}). 공개키·kid·토큰 수명을 확인한다`);
	consecutiveFailures += 1;
	if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
		exec.test.abort(`2xx 가 아닌 응답이 ${MAX_CONSECUTIVE_FAILURES}회 이어졌다(마지막 상태 ${response.status}, ${template.path})`);
	}
}

export function handleSummary(data) {
	const summary = buildSummary(data, config, plan);
	const output = { stdout: wrap(summary) };
	if (config.summaryFile) output[config.summaryFile] = JSON.stringify(summary, null, 2) + '\n';
	return output;
}
