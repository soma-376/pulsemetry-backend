// payload 템플릿(`data/templates.json`, generator/build_templates.py)을 요청 본문으로 채운다.
// 규칙은 build_templates.render() 와 같다 — 바꾸면 두 쪽과 selftest 기대값을 함께 고친다.
//
//   @@T<오프셋>@@  요청 기준 시각(ns) + 오프셋, 10진 문자열. 2^53 을 넘으므로 BigInt 로 계산한다.
//   @@D<오프셋>@@  같은 시각의 ISO-8601(밀리초, Z)
//   @@S<원래 값>@@ 요청마다 정한 순번 기준 + 원래 값(event.sequence)
//   @@I<번호>@@    ids[번호] = {prefix, mask}. mask 글자 x 16진·d 숫자·l 영소문자·u 영대문자, 그 밖은 그대로
import { PRODUCTS, SIGNALS } from './config.js';

export const SCHEMA_VERSION = 1;
const PLACEHOLDER = /@@([TDSI])(-?\d+)@@/g;
const CLASSES = { x: '0123456789abcdef', d: '0123456789', l: 'abcdefghijklmnopqrstuvwxyz', u: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' };
const NANOS_PER_MS = BigInt(1000000);
// 요청 순번은 6자리(0~999999 ns)다 — 같은 밀리초 안의 요청끼리 시각이 겹치지 않게 한다.
export const SEQUENCE_LIMIT = 1000000;
// 순번 기준(event.sequence) 4바이트를 ID 글자 뒤에 함께 뽑는다.
export const SEQUENCE_BYTES = 4;

/** templates.json 의 텍스트 → SharedArray 에 넣을 배열. body 는 여기서 한 번만 문자열로 바꾼다. */
export function prepareTemplates(text) {
	const document = JSON.parse(text);
	if (document.schemaVersion !== SCHEMA_VERSION) throw new Error(`templates.json schemaVersion ${document.schemaVersion} 은 지원하지 않는다`);
	return document.templates.map((template) => ({
		id: template.id,
		product: template.product,
		signal: template.signal,
		path: template.path,
		expected: template.expected,
		ids: template.ids,
		idChars: template.ids.reduce((sum, shape) => sum + shape.mask.length, 0),
		body: JSON.stringify(template.body),
	}));
}

/**
 * 가중치 선택기. 신호와 제품을 각각 가중치로 고르고, 그 묶음 안에서 고르게 고른다.
 * 가중치가 0보다 큰 조합에 템플릿이 없으면 init 에서 예외다.
 */
export function makePicker(templates, mixSignal, mixProduct) {
	const buckets = {};
	templates.forEach((template, index) => {
		const key = `${template.product}/${template.signal}`;
		(buckets[key] = buckets[key] || []).push(index);
	});
	for (const product of PRODUCTS) {
		for (const signal of SIGNALS) {
			if (mixProduct[product] > 0 && mixSignal[signal] > 0 && !buckets[`${product}/${signal}`]) {
				throw new Error(`템플릿이 없는 조합이다: ${product}/${signal}`);
			}
		}
	}
	const weighted = (weights, keys) => {
		const total = keys.reduce((sum, key) => sum + weights[key], 0);
		return (random) => {
			let left = random * total;
			for (const key of keys) {
				left -= weights[key];
				if (left < 0 && weights[key] > 0) return key;
			}
			return keys.filter((key) => weights[key] > 0).pop();
		};
	};
	const signal = weighted(mixSignal, SIGNALS);
	const product = weighted(mixProduct, PRODUCTS);
	return (random = Math.random) => {
		const list = buckets[`${product(random())}/${signal(random())}`];
		return list[Math.floor(random() * list.length)];
	};
}

/** 요청 기준 시각(ns) — 현재 밀리초 × 1e6 + 6자리 순번. */
export function requestBaseNanos(nowMs, sequence) {
	if (!(sequence >= 0 && sequence < SEQUENCE_LIMIT)) throw new Error(`요청 순번은 0~999999 다: ${sequence}`);
	return BigInt(nowMs) * NANOS_PER_MS + BigInt(sequence);
}

/** VU·반복 기반 순번. VU 1000개·반복 1000회 안에서는 같은 밀리초에 겹치지 않는다. */
export function requestSequence(vuId, iteration) {
	return (vuId % 1000) * 1000 + (iteration % 1000);
}

/**
 * 요청 본문 하나. bytes 는 `idChars + SEQUENCE_BYTES` 개 이상의 무작위 바이트(Uint8Array)다.
 * 같은 템플릿이라도 시각·ID 가 매번 달라 관측 ID 중복 제거에 걸리지 않는다.
 */
export function render(template, baseNanos, bytes) {
	if (bytes.length < template.idChars + SEQUENCE_BYTES) throw new Error('무작위 바이트가 모자란다');
	let cursor = 0;
	const values = template.ids.map((shape) => {
		let value = shape.prefix;
		for (const char of shape.mask) {
			const alphabet = CLASSES[char];
			value += alphabet ? alphabet[bytes[cursor++] % alphabet.length] : char;
		}
		return value;
	});
	const sequenceBase = ((bytes[cursor] << 16) | (bytes[cursor + 1] << 8) | bytes[cursor + 2]) % SEQUENCE_LIMIT;
	return template.body.replace(PLACEHOLDER, (match, kind, number) => {
		if (kind === 'T') return String(baseNanos + BigInt(number));
		if (kind === 'D') return new Date(Number((baseNanos + BigInt(number)) / NANOS_PER_MS)).toISOString();
		if (kind === 'S') return String(sequenceBase + Number(number));
		return values[Number(number)];
	});
}

/** 테스트·기준값 대조용 — 순번 기준을 직접 주는 render. */
export function renderWith(template, baseNanos, sequenceBase, idBytes) {
	const bytes = new Uint8Array(template.idChars + SEQUENCE_BYTES);
	bytes.set(idBytes.subarray(0, template.idChars));
	bytes[template.idChars] = (sequenceBase >> 16) & 0xff;
	bytes[template.idChars + 1] = (sequenceBase >> 8) & 0xff;
	bytes[template.idChars + 2] = sequenceBase & 0xff;
	return render(template, baseNanos, bytes);
}
