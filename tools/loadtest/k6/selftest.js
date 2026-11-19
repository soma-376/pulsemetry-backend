// 네트워크 없는 자체 검사 — `k6 run -q selftest.js` 가 0으로 끝나야 한다.
// 단언은 init 에서 돈다. 하나라도 틀리면 예외로 끝난다(k6 종료 코드 107).
// HMAC 벡터와 render 기준값은 tools/loadtest/tests 의 Python 테스트와 같은 값이다.
import crypto from 'k6/crypto';
import { SharedArray } from 'k6/data';
import { loadConfig, MAX_TOTAL_SECONDS, parseDuration, parseMix, SIGNALS } from './lib/config.js';
import { buildPlan, phaseAt } from './lib/schedule.js';
import { makePicker, prepareTemplates, render, renderWith, requestBaseNanos, requestSequence, SEQUENCE_BYTES } from './lib/payload.js';
import { installationToken, parseIdentities } from './lib/auth.js';
import { BEGIN, buildSummary, END, scheduleFinished, wrap, stepCompleted, stepDrops } from './lib/summary.js';

export const options = { vus: 1, iterations: 1 };

const failures = [];
let passed = 0;

function check(name, condition) {
	if (condition) passed += 1;
	else failures.push(name);
}

function equal(name, actual, expected) {
	check(`${name}: ${JSON.stringify(actual)} ≠ ${JSON.stringify(expected)}`, JSON.stringify(actual) === JSON.stringify(expected));
}

function throws(name, fn, pattern) {
	try {
		fn();
		failures.push(`${name}: 예외가 없다`);
	} catch (error) {
		check(`${name}: ${error.message}`, !pattern || pattern.test(error.message));
	}
}

// ── HMAC 테스트 벡터(tests/test_identities.py 와 같다) ─────────────────────────
const vectorSecret = new Uint8Array(32).map((_, i) => i).buffer;
equal('pit_ 벡터', installationToken(vectorSecret, 'vector-test', '00000000-0000-4000-8000-000000000001'),
	'pit_ikaYADG36CR2StNANJKZSWlkWgnSenFqzNa_tG0Wum0');
throws('31바이트 비밀 거부', () => installationToken(new Uint8Array(31).buffer, 'vector-test', 'x'), /32바이트/);
equal('identities 해석', parseIdentities(JSON.stringify({
	campaign: 'vector-test', tenantId: '00000000-0000-4000-8000-0000000000aa',
	installations: [{ installationId: '00000000-0000-4000-8000-000000000001', partition: 3 }],
})).installations, ['00000000-0000-4000-8000-000000000001']);
throws('identities 빈 설치 거부', () => parseIdentities('{"campaign":"vector-test","tenantId":"00000000-0000-4000-8000-0000000000aa","installations":[]}'));

// ── 설정 ─────────────────────────────────────────────────────────────────
equal('기간 해석', ['90', '30s', '5m', '1h', '1m30s', '2h0m5s'].map((v) => parseDuration(v, 'x')), [90, 30, 300, 3600, 90, 7205]);
throws('기간 형식 거부', () => parseDuration('5 minutes', 'x'), /설정 오류/);
equal('기본 신호 비율', parseMix('logs:70,metrics:20,traces:10', 'MIX_SIGNAL', SIGNALS), { logs: 70, metrics: 20, traces: 10 });
throws('모르는 신호 거부', () => parseMix('logs:70,events:30', 'MIX_SIGNAL', SIGNALS), /항목/);
throws('TESTID 없음 거부', () => loadConfig({}), /TESTID/);
throws('TESTID 대문자 거부', () => loadConfig({ TESTID: 'Bad' }), /TESTID/);
throws('hold 에 HOLD_RPS 없음 거부', () => loadConfig({ TESTID: 't', SCENARIO: 'hold' }), /HOLD_RPS/);
throws('START>MAX 거부', () => loadConfig({ TESTID: 't', START_RPS: '70' }), /START_RPS/);
throws('MAX_RPS 1000 초과 거부', () => loadConfig({ TESTID: 't', MAX_RPS: '1001' }), /MAX_RPS/);
const stairConfig = loadConfig({ TESTID: 't', SCENARIO: 'stair' });
equal('stair 기본값', [stairConfig.startRps, stairConfig.stepRps, stairConfig.maxRps, stairConfig.rampSeconds, stairConfig.stepHoldSeconds, stairConfig.cooldownSeconds, stairConfig.gzip],
	[5, 5, 60, 30, 300, 30, true]);

// ── 스케줄 ───────────────────────────────────────────────────────────────
const stair = buildPlan(stairConfig);
equal('stair 단계 수', stair.steps.length, 12);
equal('stair 총시간(12×(30+300)+30)', stair.totalSeconds, 3990);
equal('stair VU', [stair.scenario.preAllocatedVUs, stair.scenario.maxVUs], [24, 120]);
equal('stair stage 수', stair.scenario.stages.length, 25);
equal('stair 마지막 stage', stair.scenario.stages[24], { target: 0, duration: '30s' });
equal('phase 0s', phaseAt(stair, 0), { step: 's01', phase: 'ramp', targetRps: 5 });
equal('phase 30s', phaseAt(stair, 30), { step: 's01', phase: 'hold', targetRps: 5 });
equal('phase 329.9s', phaseAt(stair, 329.9), { step: 's01', phase: 'hold', targetRps: 5 });
equal('phase 330s', phaseAt(stair, 330), { step: 's02', phase: 'ramp', targetRps: 10 });
equal('phase 3960s', phaseAt(stair, 3960), { step: 's12', phase: 'cooldown', targetRps: 0 });
const smoke = buildPlan(loadConfig({ TESTID: 't' }));
equal('smoke', [smoke.scenario.executor, smoke.scenario.rate, smoke.scenario.duration, smoke.totalSeconds], ['constant-arrival-rate', 1, '60s', 60]);
const hold = buildPlan(loadConfig({ TESTID: 't', SCENARIO: 'hold', HOLD_RPS: '40' }));
equal('hold', [hold.scenario.executor, hold.scenario.rate, hold.totalSeconds, hold.scenario.maxVUs], ['constant-arrival-rate', 40, 1200, 80]);
throws(`${MAX_TOTAL_SECONDS}s 초과 거부`, () => buildPlan(loadConfig({ TESTID: 't', SCENARIO: 'stair', MAX_RPS: '300', STEP_RPS: '1', STEP_HOLD: '10m' })), /10200s/);
equal('경계 10200s 허용', buildPlan(loadConfig({ TESTID: 't', SCENARIO: 'hold', HOLD_RPS: '1', HOLD_DURATION: '10200s' })).totalSeconds, 10200);
throws('경계 10201s 거부', () => buildPlan(loadConfig({ TESTID: 't', SCENARIO: 'hold', HOLD_RPS: '1', HOLD_DURATION: '10201s' })), /10200s/);

// ── payload 기준값(Python build_templates.render 와 같은 결과) ───────────────
const mini = prepareTemplates(JSON.stringify({
	schemaVersion: 1,
	templates: [{ id: 'mini', product: 'codex', signal: 'logs', path: '/v1/logs', expected: { events: 1, metricPoints: 0 },
		ids: [{ prefix: 'req_', mask: 'dd' }],
		body: { a: '@@T-2000000@@', b: '@@D-2000000@@', c: '@@S41@@', d: '@@I0@@', e: '@@T0@@', z: '0' } }],
}))[0];
const miniOut = JSON.parse(renderWith(mini, BigInt('1800000000000123456'), 1000, new Uint8Array([6, 6])));
equal('render 기준값', miniOut, { a: '1799999999998123456', b: '2027-01-15T07:59:59.998Z', c: '1041', d: 'req_66', e: '1800000000000123456', z: '0' });
equal('요청 기준 시각', String(requestBaseNanos(1800000000000, 123456)), '1800000000000123456');
throws('순번 범위 거부', () => requestBaseNanos(1, 1000000));
equal('순번', [requestSequence(1, 0), requestSequence(999, 999), requestSequence(1001, 1)], [1000, 999999, 1001]);

// ── 실제 템플릿 ──────────────────────────────────────────────────────────
const templates = new SharedArray('templates', () => prepareTemplates(open('./data/templates.json')));
check('템플릿이 있다', templates.length > 0);
const buckets = {};
for (const template of templates) buckets[`${template.product}/${template.signal}`] = true;
equal('제품 2 × 신호 3', Object.keys(buckets).sort(), ['claude-code/logs', 'claude-code/metrics', 'claude-code/traces', 'codex/logs', 'codex/metrics', 'codex/traces']);
let renderFailures = 0;
let identical = 0;
const timeField = /"(?:timeUnixNano|observedTimeUnixNano|startTimeUnixNano|endTimeUnixNano)":"([^"]*)"/g;
for (let i = 0; i < templates.length; i++) {
	const template = templates[i];
	const nowMs = 1800000000000 + i;
	const first = render(template, requestBaseNanos(nowMs, 1), new Uint8Array(crypto.randomBytes(template.idChars + SEQUENCE_BYTES)));
	const second = render(template, requestBaseNanos(nowMs, 2), new Uint8Array(crypto.randomBytes(template.idChars + SEQUENCE_BYTES)));
	try {
		JSON.parse(first);
		JSON.parse(second);
	} catch (error) {
		renderFailures += 1;
		continue;
	}
	if (first.indexOf('@@') >= 0) renderFailures += 1;
	for (const match of first.matchAll(timeField)) {
		if (match[1] !== '0' && !/^1[78]\d{17}$/.test(match[1])) renderFailures += 1;
	}
	if (first === second) identical += 1;
}
equal('모든 템플릿이 유효한 JSON 으로 채워진다', renderFailures, 0);
equal('두 요청의 본문이 늘 다르다(시각·ID)', identical, 0);
const onlyCodexMetrics = makePicker(templates, { logs: 0, metrics: 1, traces: 0 }, { 'claude-code': 0, codex: 1 });
let pickedOther = 0;
for (let i = 0; i < 200; i++) {
	const template = templates[onlyCodexMetrics()];
	if (template.product !== 'codex' || template.signal !== 'metrics') pickedOther += 1;
}
equal('가중치 0 조합은 고르지 않는다', pickedOther, 0);

// ── summary ──────────────────────────────────────────────────────────────
const fakeData = {
	setup_data: { tokens: ['eyJsecret.token.value'], campaign: 'vector-test', tenantId: 'x', installationCount: 1, tokenExchanges: 1, setupEndMs: 1800000000000 },
	metrics: {
		http_reqs: { values: { count: 11 } },
		'http_reqs{target:gateway}': { values: { count: 10 } },
		'http_reqs{step:s01,phase:hold}': { values: { count: 600 } },
		'http_req_duration{step:s01,phase:hold}': { values: { 'p(95)': 12.5, 'p(99)': 30 }, thresholds: { 'p(95)<600000': { ok: true } } },
		http_req_failed: { values: { rate: 0.5 } },
		'http_req_failed{target:gateway}': { values: { rate: 0 }, thresholds: { 'rate<0.05': { ok: false } } },
		loadtest_expected_events: { values: { count: 42 } },
		loadtest_progress_seconds: { values: { max: 59.5 } },
	},
	state: { testRunDurationMs: 61000 },
};
const summary = buildSummary(fakeData, loadConfig({ TESTID: 't' }), smoke);
const wrapped = wrap(summary);
check('summary 에 토큰이 없다', wrapped.indexOf('eyJ') < 0 && wrapped.indexOf('tokens') < 0);
check('summary 표지', wrapped.indexOf(`${BEGIN}\n`) === 0 && wrapped.endsWith(`\n${END}\n`));
equal('summary 단계', [summary.steps[0].holdStart, summary.steps[0].holdEnd, summary.steps[0].holdActualRps, summary.steps[0].p95Ms],
	['2027-01-15T08:00:00.000Z', '2027-01-15T08:01:00.000Z', 10, 12.5]);
equal('summary 합계', [summary.totals.expectedEvents, summary.totals.expectedMetricPoints, summary.thresholdsPassed, summary.failedThresholds],
	[42, 0, false, ['http_req_failed{target:gateway}: rate<0.05']]);
equal('summary 합계는 Gateway 요청만 센다(setup 제외)', [summary.totals.requests, summary.totals.errorRate], [10, 0]);
// 계획 끝 표지가 없으면 중단이다(원인 무관) — 끝 0.5초 전까지만 반복한 단계를 허용 오차로 완료 처리하지 않는다.
equal('계획 끝 표지 없는 실행의 단계', [summary.scheduleFinished, summary.steps[0].completed, summary.steps[0].droppedIterations], [false, false, null]);
// 표지가 있으면 끝까지 실행한 것이다. 안전 정지 임계값이 끝 평가에서만 실패한 실행도 단계는 완료다.
const finishedRun = buildSummary(Object.assign({}, fakeData, { metrics: Object.assign({}, fakeData.metrics, { loadtest_schedule_end: { values: { count: 1 } } }) }),
	loadConfig({ TESTID: 't' }), smoke);
equal('summary 단계 drop(계획 끝까지 실행, 실행 전체 drop 0)',
	[finishedRun.scheduleFinished, finishedRun.steps[0].completed, finishedRun.steps[0].scheduledIterations, finishedRun.steps[0].droppedIterations], [true, true, 60, 0]);
equal('단계 완료 판정(계획 끝까지 실행)', [stepCompleted(60, 1, 59, 60, true), stepCompleted(60, 1, 57.9, 58, true), stepCompleted(600, 100, 598.5, 59900, true),
	stepCompleted(600, 100, 60, 5990, true), stepCompleted(600, 100, null, 5990, true), stepCompleted(600, 100, 600, null, true)], [true, false, true, false, false, false]);
// 중단(표지 없음)은 허용 오차가 없다: 유지 1초 단계의 첫 요청 뒤 중단, 300초 단계의 298.5초 중단은 미완료. 끝 뒤에 시작한 반복이 있으면 완료.
equal('단계 완료 판정(중단)', [stepCompleted(1, 1, 0, 1, false), stepCompleted(1, 1, 0, 1, true), stepCompleted(300, 10, 298.5, 2985, false),
	stepCompleted(300, 10, 300.2, 2990, false), stepCompleted(300, 10, 299.9, 2990, true), stepCompleted(60, 1, 59, 60)], [false, true, false, true, true, false]);
equal('계획 끝 표지 판단', [scheduleFinished({ metrics: {} }), scheduleFinished({ metrics: { loadtest_schedule_end: { values: { count: 1 } } } })], [false, true]);
// 유지 1초 실행이 첫 요청 뒤 401·SIGTERM 으로 멈춘 경우(CodeRabbit 재현): 경과 0초·요청 1건이어도 완료가 아니고 drop 을 내지 않는다.
const oneSecondConfig = loadConfig({ TESTID: 't', SCENARIO: 'hold', HOLD_RPS: '1', HOLD_DURATION: '1s' });
const interrupted = buildSummary({ setup_data: { setupEndMs: 1800000000000 }, metrics: {
	'http_reqs{step:s01,phase:hold}': { values: { count: 1 } }, loadtest_progress_seconds: { values: { max: 0 } } } }, oneSecondConfig, buildPlan(oneSecondConfig));
equal('중단된 1초 유지 단계는 미완료', [interrupted.scheduleFinished, interrupted.steps[0].completed, interrupted.steps[0].droppedIterations], [false, false, null]);
// 100 RPS × 600s 유지를 60초에 멈춘 실행: 요청 5,990·전체 drop 10 이면 예정 대비 차감으로 54,010 이 나오던 경우다.
const aborted = buildSummary({ setup_data: { setupEndMs: 1800000000000 }, metrics: {
	'http_reqs{step:s01,phase:hold}': { values: { count: 5990 } }, dropped_iterations: { values: { count: 10 } },
	loadtest_progress_seconds: { values: { max: 60 } } } }, loadConfig({ TESTID: 't', SCENARIO: 'hold', HOLD_RPS: '100', HOLD_DURATION: '600s' }),
	buildPlan(loadConfig({ TESTID: 't', SCENARIO: 'hold', HOLD_RPS: '100', HOLD_DURATION: '600s' })));
equal('중단된 유지 단계는 미완료이고 drop 을 내지 않는다', [aborted.steps[0].completed, aborted.steps[0].droppedIterations, aborted.progressSeconds], [false, null, 60]);
equal('단계 drop 계산', [stepDrops(600, 600, 0), stepDrops(600, 598, 0), stepDrops(600, 590, 12), stepDrops(600, 605, 3), stepDrops(600, null, 5)], [0, 0, 10, 0, null]);
equal('FINAL_FLUSH 기본값과 상한', [loadConfig({ TESTID: 't' }).finalFlushSeconds, loadConfig({ TESTID: 't', FINAL_FLUSH: '0' }).finalFlushSeconds], [25, 0]);
check('FINAL_FLUSH 300초 초과 거부', (() => { try { loadConfig({ TESTID: 't', FINAL_FLUSH: '301' }); return false; } catch (e) { return true; } })());

if (failures.length > 0) {
	throw new Error(`selftest 실패 ${failures.length}건:\n- ${failures.join('\n- ')}`);
}

export default function () {
	console.log(`selftest 통과: ${passed}건, 템플릿 ${templates.length}개`);
}
