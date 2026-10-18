#!/usr/bin/env node
// 설치된 Codeburn의 가격 모듈만 격리 실행한다. 사용자 설정·세션·네트워크·캐시는 접근하지 않는다.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageDir = process.argv[2];
if (!packageDir) throw new Error('Codeburn 0.9.24 패키지 경로가 필요합니다.');
const pkg = JSON.parse(fs.readFileSync(path.join(packageDir, 'package.json'), 'utf8'));
if (pkg.name !== 'codeburn' || pkg.version !== '0.9.24') throw new Error('검증된 Codeburn 0.9.24만 가져옵니다.');
const bundle = fs.readFileSync(path.join(packageDir, 'dist/main.js'), 'utf8');
const mapBytes = fs.readFileSync(path.join(packageDir, 'dist/main.js.map'));
const map = JSON.parse(mapBytes);
const source = suffix => {
  const index = map.sources.findIndex(name => name.endsWith(suffix));
  if (index < 0) throw new Error(`원천 누락: ${suffix}`);
  return map.sourcesContent[index];
};
const snapshot = JSON.parse(source('/data/litellm-snapshot.json'));
const fallback = JSON.parse(source('/data/pricing-fallback.json'));
const start = bundle.indexOf('function billableOutputTokens(');
const end = bundle.indexOf('\n// ', start);
if (start < 0 || !bundle.slice(start, end).includes('function getModelCosts(')) throw new Error('가격 모듈 경계를 찾지 못했습니다.');
const context = vm.createContext({
  litellm_snapshot_default: snapshot, pricing_fallback_default: fallback,
  init_cache_dir() {}, init_litellm_snapshot() {}, init_pricing_fallback() {}, init_fetch_utils() {},
  __esm: obj => { let loaded = false; return () => { if (!loaded) { loaded = true; Object.values(obj)[0](); } }; },
  process: { env: {}, stderr: { write() { throw new Error('예상하지 않은 경고'); } } },
});
vm.runInContext(bundle.slice(start, end) + '\ninit_models();', context, { timeout: 1000 });
const aliases = vm.runInContext('BUILTIN_ALIASES', context);
const relevant = name => /^(?:(?:openai|anthropic)\/)?(?:claude[- ]|gpt[- ]|chatgpt-|codex-|o[134](?:-|$))/i.test(name)
  || /^(?:anthropic--claude-|openai-codex:)/i.test(name);
const candidates = [...new Set([...Object.keys(snapshot), ...Object.keys(fallback), ...Object.keys(aliases)])].filter(relevant).sort();
const rates = [], excluded = [], parity = [];
const rateText = value => {
  if (!Number.isFinite(value) || value < 0) throw new Error('유효하지 않은 단가');
  const million = value * 1e6;
  const rounded = Number(million.toFixed(6));
  if (Math.abs(rounded - million) > 1e-8) throw new Error('저장 스케일 초과');
  return rounded.toFixed(6).replace(/\.?0+$/, '') || '0';
};
for (const model of candidates) {
  const reason = /(?:audio|realtime|transcribe|tts|image)/i.test(model) ? 'non_text_modality'
    : /:batch(?:$|\/)/i.test(model) ? 'batch_only' : null;
  if (reason) { excluded.push({ model, reason }); continue; }
  context.inputModel = model;
  const costs = vm.runInContext('getModelCosts(inputModel)', context);
  if (!costs) { excluded.push({ model, reason: 'no_resolved_price' }); continue; }
  const referenceModel = aliases[model] ?? model;
  const family = /claude/i.test(referenceModel) ? 'claude' : 'codex';
  const values = [costs.inputCostPerToken, costs.outputCostPerToken, costs.cacheReadCostPerToken, costs.cacheWriteCostPerToken].map(rateText);
  rates.push([model, referenceModel, family, ...values]);
  // 구현을 복제하지 않고 배포된 가격 함수를 독립 검증 기준으로 사용한다.
  const expected = vm.runInContext('calculateCost(inputModel, 1000000, 1000000, 1000000, 1000000, 0)', context);
  parity.push([model, expected.toFixed(9).replace(/\.?0+$/, '') || '0']);
}
const resource = path.join(root, 'libs/telemetry-adapter/src/main/resources/pricing/codeburn-0.9.24.tsv');
fs.mkdirSync(path.dirname(resource), { recursive: true });
fs.writeFileSync(resource, 'model\treference_model\tfamily\tinput_per_million\toutput_per_million\tread_per_million\tcreate_per_million\n' + rates.map(r => r.join('\t')).join('\n') + '\n');
const testResource = path.join(root, 'libs/telemetry-adapter/src/test/resources/pricing/codeburn-0.9.24-parity.tsv');
fs.mkdirSync(path.dirname(testResource), { recursive: true });
fs.writeFileSync(testResource, 'model\tfour_million_component_cost_usd\n' + parity.map(r => r.join('\t')).join('\n') + '\n');
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
const manifest = {
  package: pkg.name, version: pkg.version, source: 'installed bundled snapshot + bundled fallback + explicit built-in aliases',
  bundleSha256: digest(bundle), sourceMapSha256: digest(mapBytes), snapshotSha256: digest(source('/data/litellm-snapshot.json')),
  fallbackSha256: digest(source('/data/pricing-fallback.json')), catalogSha256: digest(fs.readFileSync(resource)),
  candidateCount: candidates.length, importedCount: rates.length, excludedCount: excluded.length,
  importedByFamily: Object.fromEntries(['claude', 'codex'].map(f => [f, rates.filter(r => r[2] === f).length])),
  explicitAliasCount: rates.filter(r => r[0] !== r[1]).length, excluded,
};
fs.writeFileSync(path.join(root, 'docs/pricing/codeburn-import-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(JSON.stringify({ candidates: candidates.length, imported: rates.length, aliases: manifest.explicitAliasCount, excluded: excluded.length, families: manifest.importedByFamily }));
