// 시험 결과 JSON. Job Pod 가 끝난 뒤 `kubectl logs` 로 꺼내도록 stdout 에 표지로 감싸 쓴다.
// setup_data 에는 토큰이 있다 — 필요한 필드만 골라 쓰고 통째로 옮기지 않는다.
export const BEGIN = '===LOADTEST-SUMMARY-BEGIN===';
export const END = '===LOADTEST-SUMMARY-END===';
// 2: 단계별 scheduledIterations·droppedIterations, totals 는 Gateway 요청만(setup 토큰 교환 제외).
// 3: 단계별 completed·실행 scheduleFinished. 중단·미실행 단계의 droppedIterations 는 null 이다.
export const SUMMARY_VERSION = 3;

// 계획 끝 표지. main.js 의 표지 시나리오가 계획 시간이 다 지난 뒤 한 번 올린다. handleSummary 의 data 에는 중단 여부·사유가 없고
// (401·연속 non-2xx 의 test.abort, 안전 정지 임계값, SIGTERM 같은 외부 신호 모두 summary 는 그대로 만든다), 중단되면 이 표지가 돌지 않는다.
export const SCHEDULE_END_COUNTER = 'loadtest_schedule_end';

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

/** 계획 시간 끝까지 실행했는지 — 계획 끝 표지가 있을 때만 참이다. 없으면 중단(원인 무관)이다. */
export function scheduleFinished(data) {
	return (value(data, SCHEDULE_END_COUNTER, 'count') || 0) > 0;
}

/**
 * 단계 완료 여부. 반복마다 남긴 시나리오 경과 초(loadtest_progress_seconds)의 최댓값이 유지 구간 끝에 닿았는지 본다.
 * - 계획 끝까지 실행: 도착률 실행기는 1/RPS 간격으로 반복을 시작하므로 끝 직전 반복까지의 간격(max(2s, 2/RPS))을 허용한다.
 * - 그 밖(중단): 허용 없이 유지 구간 끝 이후에 시작한 반복이 있어야 완료다. 끝 2초 안에서 멈춘 단계나 유지 2초 이하 단계를
 *   완료로 세지 않는다. 다음 단계로 넘어가기 직전에 멈춘 단계도 미완료로 빠진다 — 보수 쪽이다.
 * 요청이 없거나 경과 기록이 없으면 미완료다. setup·teardown 이 들어가는 testRunDurationMs 는 쓰지 않는다.
 */
export function stepCompleted(holdEndSeconds, rps, progressMaxSeconds, requests, finished) {
	if (requests === null || progressMaxSeconds === null) return false;
	if (finished !== true) return progressMaxSeconds >= holdEndSeconds;
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
	const finished = scheduleFinished(data);
	const steps = plan.steps.map((step) => {
		const names = stepMetricNames(step.step);
		const holdSeconds = step.holdEnd - step.holdStart;
		const requests = value(data, names.requests, 'count');
		const scheduled = Math.round(step.rps * holdSeconds);
		const completed = stepCompleted(step.holdEnd, step.rps, progressMax, requests, finished);
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
		scheduleFinished: finished,
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
