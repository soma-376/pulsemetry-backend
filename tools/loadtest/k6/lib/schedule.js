// 시나리오의 k6 실행기 설정과 단계표. 단계표는 태그(step·phase)·목표 RPS·summary 의 절대 시각이 함께 쓴다.
//
//   smoke  constant-arrival-rate 1 RPS × 60s
//   stair  ramping-arrival-rate — 단계마다 RAMP 동안 다음 목표까지 올리고 STEP_HOLD 동안 유지, 끝에 COOLDOWN 동안 0으로
//   hold   constant-arrival-rate HOLD_RPS × HOLD_DURATION
import { MAX_TOTAL_SECONDS } from './config.js';

const SMOKE_RPS = 1;
const SMOKE_SECONDS = 60;

function stepName(index) {
	return `s${String(index + 1).padStart(2, '0')}`;
}

function vus(peakRps) {
	return {
		preAllocatedVUs: Math.max(10, Math.ceil(peakRps * 0.4)),
		maxVUs: Math.max(50, peakRps * 2),
	};
}

/** 단계별 목표 RPS — START 부터 STEP 씩, MAX 를 넘지 않는 데까지. */
export function stairRates(config) {
	const rates = [];
	for (let rate = config.startRps; rate <= config.maxRps; rate += config.stepRps) rates.push(rate);
	return rates;
}

/**
 * 계획을 만든다. 시각은 시나리오 시작 기준 초다. 계획 시간이 MAX_TOTAL_SECONDS 를 넘으면 예외다.
 * steps[i] = {step, rps, rampStart, holdStart, holdEnd}. smoke·hold 는 상승 구간이 없다(rampStart = holdStart).
 */
export function buildPlan(config) {
	let steps;
	let cooldown = null;
	let scenario;
	if (config.scenario === 'stair') {
		const stages = [];
		steps = [];
		let t = 0;
		stairRates(config).forEach((rate, index) => {
			steps.push({ step: stepName(index), rps: rate, rampStart: t, holdStart: t + config.rampSeconds, holdEnd: t + config.rampSeconds + config.stepHoldSeconds });
			stages.push({ target: rate, duration: `${config.rampSeconds}s` }, { target: rate, duration: `${config.stepHoldSeconds}s` });
			t += config.rampSeconds + config.stepHoldSeconds;
		});
		if (config.cooldownSeconds > 0) {
			cooldown = { start: t, end: t + config.cooldownSeconds };
			stages.push({ target: 0, duration: `${config.cooldownSeconds}s` });
		}
		const peak = steps[steps.length - 1].rps;
		scenario = { executor: 'ramping-arrival-rate', startRate: 0, timeUnit: '1s', stages, ...vus(peak) };
	} else {
		const rps = config.scenario === 'smoke' ? SMOKE_RPS : config.holdRps;
		const seconds = config.scenario === 'smoke' ? SMOKE_SECONDS : config.holdSeconds;
		steps = [{ step: stepName(0), rps, rampStart: 0, holdStart: 0, holdEnd: seconds }];
		scenario = { executor: 'constant-arrival-rate', rate: rps, timeUnit: '1s', duration: `${seconds}s`, ...vus(rps) };
	}
	const totalSeconds = cooldown ? cooldown.end : steps[steps.length - 1].holdEnd;
	if (totalSeconds > MAX_TOTAL_SECONDS) {
		throw new Error(`설정 오류: 계획 시간 ${totalSeconds}s 가 ${MAX_TOTAL_SECONDS}s(telemetry JWT 3h − 여유 600s)를 넘는다 — 단계 수·유지 시간을 줄이거나 나눠 실행한다`);
	}
	return {
		scenarioName: config.scenario,
		scenario,
		steps,
		cooldown,
		totalSeconds,
		peakRps: Math.max(...steps.map((s) => s.rps)),
	};
}

/** 시나리오 시작 뒤 경과 초 → {step, phase, targetRps}. 상승 구간의 목표는 그 단계의 목표다(대시보드 주석이 단계 시작을 잡게). */
export function phaseAt(plan, elapsedSeconds) {
	for (const step of plan.steps) {
		if (elapsedSeconds < step.holdStart) return { step: step.step, phase: 'ramp', targetRps: step.rps };
		if (elapsedSeconds < step.holdEnd) return { step: step.step, phase: 'hold', targetRps: step.rps };
	}
	const last = plan.steps[plan.steps.length - 1];
	return { step: last.step, phase: 'cooldown', targetRps: 0 };
}
