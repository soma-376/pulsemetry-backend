// 시험 결과 JSON. Job Pod 가 끝난 뒤 `kubectl logs` 로 꺼내도록 stdout 에 표지로 감싸 쓴다.
// setup_data 에는 토큰이 있다 — 필요한 필드만 골라 쓰고 통째로 옮기지 않는다.
export const BEGIN = '===LOADTEST-SUMMARY-BEGIN===';
export const END = '===LOADTEST-SUMMARY-END===';
export const SUMMARY_VERSION = 1;

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
	const steps = plan.steps.map((step) => {
		const names = stepMetricNames(step.step);
		const holdSeconds = step.holdEnd - step.holdStart;
		const requests = value(data, names.requests, 'count');
		return {
			step: step.step,
			targetRps: step.rps,
			rampStart: at(step.rampStart),
			holdStart: at(step.holdStart),
			holdEnd: at(step.holdEnd),
			holdSeconds,
			holdRequests: requests,
			holdActualRps: requests === null ? null : requests / holdSeconds,
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
			requests: value(data, 'http_reqs', 'count') || 0,
			errorRate: value(data, 'http_req_failed', 'rate'),
			p95Ms: value(data, 'http_req_duration', 'p(95)'),
			p99Ms: value(data, 'http_req_duration', 'p(99)'),
			droppedIterations: value(data, 'dropped_iterations', 'count') || 0,
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
