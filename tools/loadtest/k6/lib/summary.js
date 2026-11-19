// 시험 결과 JSON. Job Pod 가 끝난 뒤 `kubectl logs` 로 꺼내도록 stdout 에 표지로 감싸 쓴다.
// setup_data 에는 토큰이 있다 — 필요한 필드만 골라 쓰고 통째로 옮기지 않는다.
export const BEGIN = '===LOADTEST-SUMMARY-BEGIN===';
export const END = '===LOADTEST-SUMMARY-END===';
// 2: 단계별 scheduledIterations·droppedIterations, totals 는 Gateway 요청만(setup 토큰 교환 제외).
// 3: 단계별 completed·실행 aborted. 중단·미실행 단계의 droppedIterations 는 null 이다.
export const SUMMARY_VERSION = 3;

// Gateway 요청만 센다. setup 의 토큰 교환(target 태그 없음)이 오류율·지연·합계를 희석하지 않게 한다.
export const GATEWAY = 'target:gateway';

// 안전 정지 임계값. main.js 가 abortOnFail 로 등록하고, summary 는 같은 표로 중단 여부를 본다.
export const ABORT_THRESHOLDS = {
	[`http_req_failed{${GATEWAY}}`]: 'rate<0.05',
	[`http_req_duration{${GATEWAY}}`]: 'p(95)<4000',
	dropped_iterations: 'count<3000',
};

// main.js 가 exec.test.abort 직전에 올리는 카운터. handleSummary 의 data 에는 중단 사유가 없어서 따로 남긴다
// (중단한 반복에서 올린 값도 summary 에 실린다 — k6 1.8.2 확인).
export const ABORT_COUNTER = 'loadtest_aborted';

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

/**
 * 실행이 중단됐는지. 스크립트 중단(401·연속 non-2xx)은 ABORT_COUNTER 로, 임계값 중단은 ABORT_THRESHOLDS 의 실패로 본다.
 * 임계값이 끝 평가에서만 실패한 실행(중단 없음)도 중단으로 읽는다 — 마지막 단계가 미완료로 빠질 뿐이라 판정은 보수 쪽이다.
 */
export function runAborted(data) {
	if ((value(data, ABORT_COUNTER, 'count') || 0) > 0) return true;
	return Object.keys(ABORT_THRESHOLDS).some((name) => {
		const thresholds = (data.metrics[name] && data.metrics[name].thresholds) || {};
		const result = thresholds[ABORT_THRESHOLDS[name]];
		return result !== undefined && !result.ok;
	});
}

/**
 * 단계 완료 여부. 반복마다 남긴 시나리오 경과 초(loadtest_progress_seconds)의 최댓값이 유지 구간 끝에 닿았는지 본다.
 * - 정상 종료: 도착률 실행기는 1/RPS 간격으로 반복을 시작하므로 끝 직전 반복까지의 간격(max(2s, 2/RPS))을 허용한다.
 * - 중단: 허용 없이 유지 구간 끝 이후에 시작한 반복이 있어야 완료다. 끝 2초 안에서 멈춘 단계나 유지 2초 이하 단계를
 *   완료로 세지 않는다. 다음 단계로 넘어가기 직전에 멈춘 단계도 미완료로 빠진다 — 보수 쪽이다.
 * 요청이 없거나 경과 기록이 없으면 미완료다. setup·teardown 이 들어가는 testRunDurationMs 는 쓰지 않는다.
 */
export function stepCompleted(holdEndSeconds, rps, progressMaxSeconds, requests, aborted = false) {
	if (requests === null || progressMaxSeconds === null) return false;
	if (aborted) return progressMaxSeconds >= holdEndSeconds;
	return progressMaxSeconds >= holdEndSeconds - Math.max(2, 2 / rps);
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
	const progressMax = value(data, 'loadtest_progress_seconds', 'max');
	const aborted = runAborted(data);
	const steps = plan.steps.map((step) => {
		const names = stepMetricNames(step.step);
		const holdSeconds = step.holdEnd - step.holdStart;
		const requests = value(data, names.requests, 'count');
		const scheduled = Math.round(step.rps * holdSeconds);
		const completed = stepCompleted(step.holdEnd, step.rps, progressMax, requests, aborted);
		return {
			step: step.step,
			targetRps: step.rps,
			rampStart: at(step.rampStart),
			holdStart: at(step.holdStart),
			holdEnd: at(step.holdEnd),
			holdSeconds,
			holdRequests: requests,
			holdActualRps: requests === null ? null : requests / holdSeconds,
			completed,
			scheduledIterations: scheduled,
			// 중단·미실행 단계는 실제 실행 구간을 확정할 수 없어 drop 을 내지 않는다(판정은 이 단계를 한계에서 뺀다).
			droppedIterations: completed ? stepDrops(scheduled, requests, runDropped) : null,
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
		progressSeconds: progressMax,
		aborted,
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
