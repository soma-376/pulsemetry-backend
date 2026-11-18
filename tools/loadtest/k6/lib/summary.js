// 시험 결과 JSON. Job Pod 가 끝난 뒤 `kubectl logs` 로 꺼내도록 stdout 에 표지로 감싸 쓴다.
// setup_data 에는 토큰이 있다 — 필요한 필드만 골라 쓰고 통째로 옮기지 않는다.
export const BEGIN = '===LOADTEST-SUMMARY-BEGIN===';
export const END = '===LOADTEST-SUMMARY-END===';
// 2: 단계별 scheduledIterations·droppedIterations, totals 는 Gateway 요청만(setup 토큰 교환 제외).
export const SUMMARY_VERSION = 2;

function iso(epochMs) {
	return epochMs === null ? null : new Date(epochMs).toISOString();
}

function value(data, name, field) {
	const metric = data.metrics[name];
	if (!metric || !metric.values || metric.values[field] === undefined) return null;
	return metric.values[field];
}

/** 단계별 판정용 submetric 이름 — main.js 의 thresholds 와 같다. */
export function stepMetricNames(step) {
	return {
		duration: `http_req_duration{step:${step},phase:hold}`,
		failed: `http_req_failed{step:${step},phase:hold}`,
		requests: `http_reqs{step:${step},phase:hold}`,
		expectedEvents: `loadtest_expected_events{step:${step}}`,
		expectedMetricPoints: `loadtest_expected_metric_points{step:${step}}`,
	};
}

/**
 * 단계별 drop. 반복 하나가 Gateway 요청 하나라서 유지 구간에 예정된 반복(목표 RPS × 유지 초)과 실제 요청 수의 차이가
 * 그 단계의 drop 이다. Prometheus 수집 시점·collector 수명과 무관한 k6 자체 집계다. 실행 전체 drop 이 0이면
 * 단계별도 0이다(경계의 ±1 반복 오차를 drop 으로 읽지 않는다). 단계를 실행하지 않았으면 null 이다.
 */
export function stepDrops(scheduled, requests, runDropped) {
	if (requests === null) return null;
	if (!runDropped) return 0;
	return Math.max(0, scheduled - requests);
}

function failedThresholds(data) {
	const failed = [];
	for (const name of Object.keys(data.metrics)) {
		const thresholds = data.metrics[name].thresholds || {};
		for (const expression of Object.keys(thresholds)) {
			if (!thresholds[expression].ok) failed.push(`${name}: ${expression}`);
		}
	}
	return failed.sort();
}

/** handleSummary 의 data → 결과 JSON. 시각은 setup 종료(≈ 시나리오 시작) 기준의 절대 시각이다. */
export function buildSummary(data, config, plan) {
	const setup = data.setup_data || {};
	const start = typeof setup.setupEndMs === 'number' ? setup.setupEndMs : null;
	const at = (seconds) => (start === null ? null : iso(start + seconds * 1000));
	const runDropped = value(data, 'dropped_iterations', 'count') || 0;
	const steps = plan.steps.map((step) => {
		const names = stepMetricNames(step.step);
		const holdSeconds = step.holdEnd - step.holdStart;
		const requests = value(data, names.requests, 'count');
		const scheduled = Math.round(step.rps * holdSeconds);
		return {
			step: step.step,
			targetRps: step.rps,
			rampStart: at(step.rampStart),
			holdStart: at(step.holdStart),
			holdEnd: at(step.holdEnd),
			holdSeconds,
			holdRequests: requests,
			holdActualRps: requests === null ? null : requests / holdSeconds,
			scheduledIterations: scheduled,
			droppedIterations: stepDrops(scheduled, requests, runDropped),
			p95Ms: value(data, names.duration, 'p(95)'),
			p99Ms: value(data, names.duration, 'p(99)'),
			errorRate: value(data, names.failed, 'rate'),
			expectedEvents: value(data, names.expectedEvents, 'count') || 0,
			expectedMetricPoints: value(data, names.expectedMetricPoints, 'count') || 0,
		};
	});
	const failed = failedThresholds(data);
	return {
		summaryVersion: SUMMARY_VERSION,
		testid: config.testid,
		scenario: config.scenario,
		campaign: setup.campaign || null,
		tenantId: setup.tenantId || null,
		installations: setup.installationCount || 0,
		tokenExchanges: setup.tokenExchanges || 0,
		scenarioStart: at(0),
		scenarioEnd: at(plan.totalSeconds),
		plannedSeconds: plan.totalSeconds,
		cooldown: plan.cooldown ? { start: at(plan.cooldown.start), end: at(plan.cooldown.end) } : null,
		config: {
			gatewayUrl: config.gatewayUrl,
			startRps: config.startRps,
			stepRps: config.stepRps,
			maxRps: config.maxRps,
			rampSeconds: config.rampSeconds,
			stepHoldSeconds: config.stepHoldSeconds,
			cooldownSeconds: config.cooldownSeconds,
			holdRps: config.holdRps,
			holdSeconds: config.holdSeconds,
			mixSignal: config.mixSignal,
			mixProduct: config.mixProduct,
			gzip: config.gzip,
			preAllocatedVUs: plan.scenario.preAllocatedVUs,
			maxVUs: plan.scenario.maxVUs,
		},
		steps,
		totals: {
			requests: value(data, 'http_reqs{target:gateway}', 'count') || 0,
			errorRate: value(data, 'http_req_failed{target:gateway}', 'rate'),
			p95Ms: value(data, 'http_req_duration{target:gateway}', 'p(95)'),
			p99Ms: value(data, 'http_req_duration{target:gateway}', 'p(99)'),
			droppedIterations: runDropped,
			acceptedRequests: value(data, 'loadtest_accepted_requests', 'count') || 0,
			expectedEvents: value(data, 'loadtest_expected_events', 'count') || 0,
			expectedMetricPoints: value(data, 'loadtest_expected_metric_points', 'count') || 0,
		},
		thresholdsPassed: failed.length === 0,
		failedThresholds: failed,
		testRunDurationMs: data.state ? data.state.testRunDurationMs : null,
	};
}

export function wrap(summary) {
	return `${BEGIN}\n${JSON.stringify(summary)}\n${END}\n`;
}
