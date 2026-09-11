// 실제 frontend + dashboard + 격리 PostgreSQL. HTTP 응답을 가로채거나 목업으로 바꾸지 않는다.
// 이 테스트는 인증·P5와 브라우저 API 클라이언트의 지표 메타와 공통 지표 50개 및 owner 전용 지표 3개 집계를 검증하며 전체 PROJ-156 E2E를 대체하지 않는다.
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync, createWriteStream, rmSync, readFileSync, existsSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { generateKeyPairSync, randomUUID, createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const backend = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const frontend = resolve(backend, '../pulsemetry-frontend');
const { chromium } = await import(pathToFileURL(resolve(frontend, 'node_modules/playwright/index.mjs')).href);
const work = mkdtempSync(resolve(tmpdir(), 'proj156-e2e-'));
const artifacts = resolve(backend, 'build/e2e/auth-settings');
mkdirSync(artifacts, { recursive: true });
const resultFile = resolve(artifacts, 'result.json');
if (existsSync(resultFile) && JSON.parse(readFileSync(resultFile, 'utf8')).passed)
  copyFileSync(resultFile, resolve(artifacts, 'last-success.json'));
writeFileSync(resultFile, JSON.stringify({ passed: false, status: 'running', startedAt: new Date().toISOString() }, null, 2));
const processes = [];
const logStreams = [];
let container;
let clickhouse;
let browser;
const tenant = randomUUID();
const api = 'http://127.0.0.1:18081';
const ui = 'http://127.0.0.1:15173';
const run = (cmd, args, input) => {
  const result = spawnSync(cmd, args, { cwd: backend, input, encoding: 'utf8', timeout: 60000 });
  if (result.status !== 0) throw new Error(`${cmd} failed: ${result.error?.message || result.stderr}`);
  return result.stdout.trim();
};
const launch = (cmd, args, env, cwd, name) => {
  const stream = createWriteStream(resolve(artifacts, `${name}.log`));
  const child = spawn(cmd, args, { cwd, env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(stream); child.stderr.pipe(stream);
  processes.push(child); logStreams.push(stream);
  return child;
};
const waitFor = async (probe, seconds = 60) => {
  const end = Date.now() + seconds * 1000;
  while (Date.now() < end) {
    try { if (await probe()) return; } catch {}
    await new Promise(r => setTimeout(r, 250));
  }
  throw new Error('서비스 기동 시간 초과');
};
const sql = source => run('docker', ['exec', '-i', container, 'psql', '-U', 'pulsemetry', '-d', 'pulsemetry', '-v', 'ON_ERROR_STOP=1', '-At'], source);
try {
  container = run('docker', ['run', '--rm', '-d', '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=pulsemetry',
    '-e', 'POSTGRES_PASSWORD=e2e-only-password', '-e', 'POSTGRES_DB=pulsemetry', 'postgres:16-alpine']);
  await waitFor(() => { run('docker', ['exec', container, 'pg_isready', '-U', 'pulsemetry']); return true; });
  const port = run('docker', ['port', container, '5432/tcp']).split(':').at(-1);
  clickhouse = run('docker', ['run', '--rm', '-d', '-p', '127.0.0.1::8123', '-e',
    'CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1', 'clickhouse/clickhouse-server:24.8-alpine']);
  const chPort = run('docker', ['port', clickhouse, '8123/tcp']).split(':').at(-1);
  await waitFor(async () => (await fetch(`http://127.0.0.1:${chPort}/ping`)).ok);
  run('docker', ['exec', '-i', clickhouse, 'clickhouse-client', '--multiquery'],
    readFileSync(resolve(backend, 'libs/telemetry-persistence/src/main/resources/clickhouse/V1__enriched_events.sql'), 'utf8'));
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048,
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
  const privateFile = resolve(work, 'private.pem');
  const publicFile = resolve(work, 'public.pem');
  writeFileSync(privateFile, privateKey, { mode: 0o600 }); writeFileSync(publicFile, publicKey);
  launch('java', ['-jar', resolve(backend, 'apps/dashboard-api/build/libs/dashboard-api-0.0.1-SNAPSHOT.jar')], {
    SERVER_PORT: '18081', PULSEMETRY_DB_URL: `jdbc:postgresql://127.0.0.1:${port}/pulsemetry`,
    PULSEMETRY_DB_USERNAME: 'pulsemetry', PULSEMETRY_DB_PASSWORD: 'e2e-only-password',
    SPRING_APPLICATION_JSON: JSON.stringify({ pulsemetry: { dashboard: { 'tenant-id': tenant,
      'clickhouse-url': `http://127.0.0.1:${chPort}`, issuer: 'https://e2e.pulsemetry.test', 'active-kid': 'e2e', 'private-key-file': privateFile,
      'public-key-files': { e2e: publicFile }, 'allowed-origins': [ui] } } }),
  }, backend, 'backend');
  await waitFor(async () => (await fetch(`${api}/v1/healthz`)).ok);
  sql(`CREATE EXTENSION IF NOT EXISTS pgcrypto;
    INSERT INTO enrollment.tenants(id,name) VALUES ('${tenant}','E2E 조직');
    INSERT INTO enrollment.members(id,tenant_id,email,display_name,role,password_hash) VALUES
    ('00000000-0000-0000-0000-000000000001','${tenant}','owner@e2e.test','E2E 소유자','owner',crypt('fixture-password-123',gen_salt('bf',4))),
    ('00000000-0000-0000-0000-000000000002','${tenant}','admin@e2e.test','E2E 관리자','admin',crypt('fixture-password-123',gen_salt('bf',4)));
    INSERT INTO enrollment.teams(id,tenant_id,name) VALUES
    ('00000000-0000-0000-0000-000000000010','${tenant}','E2E 개발팀'),
    ('00000000-0000-0000-0000-000000000011','${tenant}','E2E 별도팀');
    INSERT INTO enrollment.team_memberships(team_id,member_id) VALUES
    ('00000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000002');`);
  // 정규화 완료 형태의 테스트 포인트를 실제 ClickHouse에 적재한다. ingest 경로 검증은 별도다.
  const metricFixtures = [
    ['sessions', 'claude_code.session.count', 2],
    ['active_time', 'claude_code.active_time.total', 120],
    ['lines_of_code', 'claude_code.lines_of_code.count', 10],
    ['commits', 'claude_code.commit.count', 1],
    ['pull_requests', 'claude_code.pull_request.count', 1],
  ];
  const observedAt = Math.floor(Date.now()/1000)-120;
  const costContract = randomUUID();
  const termContract = randomUUID();
  sql(`INSERT INTO enrollment.contracts(id,tenant_id,vendor,contract_type,name,contracted_at,starts_at)
    VALUES ('${costContract}','${tenant}','anthropic','token_discount','E2E 할인','2020-01-01','2020-01-01');
    INSERT INTO enrollment.contract_token_discounts(contract_id,model_pattern,discount_rate,effective_from)
    VALUES ('${costContract}','claude-e2e',0.5,'2020-01-01');
    INSERT INTO enrollment.contracts(id,tenant_id,vendor,contract_type,name,contracted_at,starts_at)
    VALUES ('${termContract}','${tenant}','anthropic','term_commitment','E2E 약정',CURRENT_DATE,CURRENT_DATE);
    INSERT INTO enrollment.contract_term_commitments(contract_id,commitment_months,commitment_amount)
    VALUES ('${termContract}',12,1000);`);
  const points = [];
  for (let index = 0; index < 5; index++) {
    const memberId = randomUUID(), installationId = randomUUID(), invitationId = randomUUID();
    sql(`INSERT INTO enrollment.members(id,tenant_id,email) VALUES ('${memberId}','${tenant}','fixture-${index}@e2e.test');
      INSERT INTO enrollment.team_memberships(team_id,member_id)
        VALUES ('00000000-0000-0000-0000-000000000010','${memberId}');
      INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at)
        VALUES ('${invitationId}','${tenant}','${memberId}','00000000-0000-0000-0000-000000000001','${randomUUID()}',now()+interval '1 day');
      INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform,created_at)
        VALUES ('${installationId}','${tenant}','${memberId}','${invitationId}','linux',to_timestamp(${observedAt}-3600));
      INSERT INTO enrollment.contract_memberships(contract_id,member_id,assigned_at) VALUES ('${costContract}','${memberId}',to_timestamp(${observedAt}-3600));
      INSERT INTO enrollment.contract_memberships(contract_id,member_id,assigned_at)
      VALUES ('${termContract}','${memberId}',to_timestamp(${observedAt}-3600));`);
    points.push(JSON.stringify({ event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'metric', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ point: { name: 'claude_code.cost.usage', value: 1, aggregation_temporality: 1, attrs: { model: 'claude-e2e', 'agent.name': 'worker', query_source: index < 2 ? 'subagent' : 'main' } } }) }));
    for (const [, name, value] of metricFixtures) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'metric', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ envelope: { session_id: installationId }, point: { name, value, aggregation_temporality: 1, attrs: { type: 'user', start_type: 'fresh' } } }),
    }));
    for (const [decision, source, value] of [['accept', 'user_temporary', 3], ['accept', 'user_permanent', 2],
      ['reject', 'user_reject', 5], ['accept', 'config', 100]]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'metric', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ point: { name: 'claude_code.code_edit_tool.decision',
        value, aggregation_temporality: 1, attrs: { decision, source, language: 'kotlin', tool_name: 'Edit' } } }),
    }));
    for (const [type, value] of [['input', 10], ['output', 20], ['cacheRead', 30], ['cacheCreation', 40]]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'metric', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ point: { name: 'claude_code.token.usage',
        value, aggregation_temporality: 1, attrs: { type, model: 'claude-e2e', 'agent.name': 'worker' } } }),
    }));
    for (const command_name of ['/review', null]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'log', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ type: 'user_prompt', envelope: { session_id: installationId }, payload: { command_name } }),
    }));
    for (const success of [true, false, null]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'log', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ type: 'tool_call', envelope: { session_id: installationId },
        payload: { tool_name: 'Read', tool_kind: 'function', action: 'read', success, agent_id: success === null ? null : success ? 'agent-a' : 'agent-b' } }),
    }));
    for (const [attempt, status_code] of [[1, 200], [2, 429], [null, null]]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'log', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ type: 'llm_call', sequence: attempt === 2 ? 100 : 1, envelope: { session_id: installationId, identity: { vendor_email: `other-${index}@vendor.test` } },
        payload: { cost_usd: 2, cost_source: 'reported', tokens: { input: 100, output: 50, cache_read: 200, cache_create: 100 }, model: 'claude-e2e', attempt, status_code, request_id: 'request-' + attempt, ttft_ms: attempt === 1 ? 100 : null, duration_ms: attempt === 1 ? 100 : 900, stop_reason: attempt === 1 ? 'end_turn' : null, error_type: status_code === 429 ? 'rate_limit' : null } }),
    }));
    points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'log', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ type: 'llm_response',
        payload: { model: 'claude-e2e', stop_reason: 'refusal', refusal_category: 'policy' } }),
    }));
    points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'span', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ type: 'turn',
        payload: { kind: 'turn', attrs: { duration_ms: '1500' } } }),
    }));
    for (const [request_id, ttft_ms] of [['request-1', 9999], ['span-only', 300]]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'span', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ type: 'llm_request',
        payload: { model: 'claude-e2e', request_id, ttft_ms } }),
    }));
    for (const blocked_on_user_ms of [0, 1999, 2000, 3000]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'span', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ type: 'tool_gate',
        payload: { blocked_on_user_ms, decided_by: 'user', decision: 'accept' } }),
    }));
    for (const num_blocking of ['2', '3']) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'span', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ type: 'hook', envelope: { session_id: installationId },
        payload: { kind: 'hook', attrs: { hook_event: 'PreToolUse', num_blocking } } }),
    }));
    for (const [decided_by, decision] of [['config', 'reject'], ['hook', 'accept'], ['user', 'abort'], [null, null]]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'log', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ type: 'tool_decision', envelope: { session_id: installationId },
        payload: { tool_name: 'Bash', decided_by, decision } }),
    }));
    for (const [tokens_before, tokens_after] of [[100, 90], [900, 100], [1000, null]]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'log', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ type: 'lifecycle', envelope: { session_id: installationId },
        payload: { kind: 'compaction', tokens_before, tokens_after, attrs: { trigger: 'auto', success: false } } }),
    }));
    for (const status of ['connected', 'failed', 'disconnected', null]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt, tenant_id: tenant, installation_id: installationId,
      signal: 'log', product: 'claude_code', team_ids_as_of: ['00000000-0000-0000-0000-000000000010'],
      enrichment_json: '{}', raw_json: JSON.stringify({ type: 'lifecycle', payload: { kind: 'mcp_connection',
        attrs: { server_name: 'github', server_scope: 'user', transport_type: 'stdio', is_plugin: 'True', status } } }),
    }));
  }
  run('docker', ['exec', '-i', clickhouse, 'clickhouse-client', '--query', 'INSERT INTO enriched_events FORMAT JSONEachRow'], points.join('\n'));
  launch('node', [resolve(frontend, 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '15173', '--strictPort'],
    { VITE_API_MODE: 'real', VITE_API_BASE_URL: `${api}/v1` }, frontend, 'frontend');
  await waitFor(async () => (await fetch(ui)).ok);
  browser = await chromium.launch({ headless: true });
  const failures = [];
  const responseReads = [];
  for (const role of ['owner', 'admin']) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    page.on('pageerror', error => console.error('브라우저 오류:', error.message));
    page.on('response', response => {
      if (!response.url().startsWith(api)) return;
      if (response.status() >= 400) failures.push({ path: new URL(response.url()).pathname, status: response.status() });
      else if (new URL(response.url()).pathname === '/v1/query') responseReads.push(response.json().then(body => {
        for (const [ref, result] of Object.entries(body.results || {})) if (result.status !== 200)
          failures.push({ path: '/v1/query', ref, status: result.status, error: result.error?.error });
      }));
    });
    await page.goto(`${ui}/settings`);
    await page.getByLabel('이메일', { exact: true }).fill(`${role}@e2e.test`);
    await page.getByLabel('비밀번호', { exact: true }).fill('fixture-password-123');
    const login = page.waitForResponse(r => r.url() === `${api}/v1/auth/login`);
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    const response = await login;
    assert.equal(response.status(), 200);
    assert.equal((await response.json()).expires_in, 28800);
    await page.getByRole('heading', { name: '설정', exact: true }).waitFor();
    await page.getByRole('cell', { name: 'E2E 개발팀', exact: true }).waitFor();
    // 실제 frontend의 메모리 토큰·CORS·JSON 처리 경로로 호출한다. 카탈로그 UI 렌더 검증은 아니다.
    const catalog = await page.evaluate(async () => {
      const client = await import('/src/api/client.ts');
      return client.request('/meta/metrics');
    });
    const spec = readFileSync(resolve(backend, 'docs/reference/pulsemetry_api_spec.yaml'), 'utf8');
    const metricSection = spec.split('    MetricId:')[1].split('    Dimension:')[0];
    const metricIds = [...metricSection.matchAll(/^        - ([a-z_]+)/gm)].map(match => match[1]);
    assert.equal(metricIds.length, 53);
    assert.deepEqual(catalog.items.map(item => item.metric_id).sort(), metricIds.sort());
    assert.deepEqual(catalog.items.find(item => item.metric_id === 'refusals').forbidden_group_by, ['team']);
    assert.equal(catalog.items.find(item => item.metric_id === 'api_error_rate').availability, 'partial');
    assert.equal(catalog.items.find(item => item.metric_id === 'subagent_activity').availability, 'partial');
    assert.equal(catalog.items.find(item => item.metric_id === 'hook_blocking').availability, 'partial');
    assert.equal(catalog.items.find(item => item.metric_id === 'hook_executions').availability, 'partial');
    assert.equal(catalog.items.find(item => item.metric_id === 'refusals').availability, 'partial');
    const scenarios = await page.evaluate(async () => {
      const { scenarioApi } = await import('/src/api/scenarios.ts');
      const { defaults, validateParams } = await import('/src/pages/scenarios/params.ts');
      const catalog = await scenarioApi.catalog();
      const details = [];
      for (const item of catalog.items) details.push(await scenarioApi.detail(item.scenario_id));
      const spike = details.find(item => item.scenario_id === 'S1-3');
      const params = defaults(spike.params_schema, {});
      return { catalog, details, errors: validateParams(spike.params_schema, params),
        invalid: validateParams(spike.params_schema, { ...params, moving_avg_days: 2 }) };
    });
    assert.equal(scenarios.catalog.categories.length, 8);
    assert.equal(scenarios.details.length, 46);
    assert.deepEqual(scenarios.errors, []);
    assert.ok(scenarios.invalid.length > 0);
    assert.equal(scenarios.details.filter(item => item.availability === 'unavailable').length, 4);
    for (const detail of scenarios.details) {
      assert.deepEqual(detail.metrics.map(metric => metric.metric_id), detail.metric_ids);
      for (const metric of detail.metrics) {
        const definition = catalog.items.find(item => item.metric_id === metric.metric_id);
        assert.equal(metric.definition, definition.definition);
        assert.equal(metric.availability, definition.availability);
      }
    }
    const expectedMetrics = [...metricFixtures.map(([metric, , value]) => [metric, value*5]),
      ['active_users', 5], ['adoption_rate', 5/(role==='owner' ? 7 : 6)], ['telemetry_coverage', 1], ['automation_ratio', 0], ['integration_depth', 1], ['command_prompt_ratio', 0.5]];
    const queryResult = await page.evaluate(async fixtures => {
      const client = await import('/src/api/client.ts');
      return client.request('/query', { method: 'POST', body: JSON.stringify({
        from: 'now-1d', to: 'now', compare: 'none',
        queries: fixtures.map(([metric], index) => ({ ref_id: String.fromCharCode(65+index), metric_id: metric, frame_type: 'scalar' })),
      }) });
    }, expectedMetrics);
    for (let index = 0; index < expectedMetrics.length; index++) {
      const result = queryResult.results[String.fromCharCode(65+index)];
      assert.equal(result.status, 200);
      assert.equal(result.frames.length, 1);
      assert.equal(result.frames[0].data.values[0][0], expectedMetrics[index][1]);
      assert.equal(result.frames[0].schema.fields[0].config.suppressed, false);
    }

    const distribution = await page.evaluate(async () => {
      const client = await import('/src/api/client.ts');
      const { series } = await import('/src/widgets/model.ts');
      const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
        from: 'now-1d', to: 'now', compare: 'previous_period', queries: [{ ref_id: 'A', metric_id: 'prompts_per_session', group_by: ['team', 'product'] }],
      }) });
      return series(response.results.A);
    });
    assert.equal(distribution.state, 'success');
    assert.equal(distribution.frames.length, 2);
    assert.ok(distribution.points.every(point => point.previous?.state === 'missing'));
    assert.deepEqual(Object.fromEntries(distribution.points.map(point => [point.key, point.value.value])),
      { '1': 0, '2–3': 5, '4–7': 0, '8–15': 0, '16+': 0, p50: 2, p90: 2 });

    const tools = await page.evaluate(async () => {
      const client = await import('/src/api/client.ts');
      const { series } = await import('/src/widgets/model.ts');
      const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
        from: 'now-1d', to: 'now', compare: 'none', queries: [
          { ref_id: 'A', metric_id: 'tool_calls', frame_type: 'scalar', group_by: ['team', 'tool_name'] },
          { ref_id: 'B', metric_id: 'tool_failure_rate', group_by: ['team', 'tool_name'] },
          { ref_id: 'C', metric_id: 'read_tool_density', group_by: ['team'] },
          { ref_id: 'D', metric_id: 'tool_calls', frame_type: 'scalar', params: { success: false } },
          { ref_id: 'E', metric_id: 'api_retry_attempts', group_by: ['team', 'model'] },
          { ref_id: 'F', metric_id: 'rate_limit_events', frame_type: 'scalar', group_by: ['team', 'product'] },
          { ref_id: 'G', metric_id: 'auto_approval_ratio', group_by: ['team', 'tool_name'] },
          { ref_id: 'H', metric_id: 'tool_rejections', frame_type: 'scalar', group_by: ['team', 'tool_name'] },
          { ref_id: 'I', metric_id: 'api_error_rate', group_by: ['team', 'model'] },
          { ref_id: 'J', metric_id: 'usage_heatmap', group_by: ['weekday', 'hour'] },
          { ref_id: 'K', metric_id: 'compactions', frame_type: 'scalar', group_by: ['team', 'trigger'] },
          { ref_id: 'L', metric_id: 'compaction_reduction', group_by: ['team'] },
        ],
      }) });
      return Object.fromEntries(Object.entries(response.results).map(([ref, result]) => [ref, series(result)]));
    });
    for (const result of Object.values(tools)) assert.equal(result.state, 'success');
    const toolValues = ref => Object.fromEntries(tools[ref].points.map(point => [point.key, point.value.value]));
    assert.deepEqual(toolValues('A'), { value: 15 });
    assert.deepEqual(toolValues('B'), { value: 0.5, numerator: 5, denominator: 10 });
    assert.deepEqual(toolValues('C'), { p50: 3, p90: 3 });
    assert.deepEqual(toolValues('D'), { value: 5 });
    assert.deepEqual(toolValues('E'), { value: 1/3, numerator: 5, denominator: 15 });
    assert.deepEqual(toolValues('F'), { value: 5 });
    assert.deepEqual(toolValues('G'), { value: 0.5, numerator: 10, denominator: 20 });
    assert.deepEqual(toolValues('H'), { value: 5 });
    assert.deepEqual(toolValues('I'), { value: 1/3, numerator: 5, denominator: 15 });
    assert.deepEqual(toolValues('J'), { value: 10 });
    assert.deepEqual(toolValues('K'), { value: 15 });
    assert.deepEqual(toolValues('L'), { value: 0.81, numerator: 4050, denominator: 5000 });
    const mcp = await page.evaluate(async () => {
      const client = await import('/src/api/client.ts');
      const { series } = await import('/src/widgets/model.ts');
      const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
        from: 'now-1d', to: 'now', queries: [
          { ref_id: 'A', metric_id: 'mcp_connections', frame_type: 'scalar',
            group_by: ['server_name', 'is_plugin'], params: { server_scope: 'user' } },
          { ref_id: 'B', metric_id: 'mcp_failure_ratio', group_by: ['server_name'] },
          { ref_id: 'C', metric_id: 'llm_stop_reasons', group_by: ['model', 'stop_reason'] },
          { ref_id: 'D', metric_id: 'llm_duration_ms', group_by: ['model'] },
          { ref_id: 'E', metric_id: 'turn_duration_ms', group_by: ['product'] },
          { ref_id: 'F', metric_id: 'llm_ttft_ms', group_by: ['product', 'model'] },
          { ref_id: 'G', metric_id: 'gate_wait_ms', group_by: ['team', 'decision'] },
          { ref_id: 'H', metric_id: 'rubber_stamp_ratio', group_by: ['team'] },
          { ref_id: 'I', metric_id: 'rubber_stamp_ratio', params: { threshold_ms: 2001 } },
          { ref_id: 'J', metric_id: 'edit_acceptance_rate', group_by: ['language', 'tool_name'] },
          { ref_id: 'K', metric_id: 'subagent_activity', frame_type: 'scalar', group_by: ['team'] },
          { ref_id: 'L', metric_id: 'hook_blocking', frame_type: 'scalar', group_by: ['hook_event'] },
        ],
      }) });
      return Object.fromEntries(Object.entries(response.results).map(([ref, result]) => [ref, series(result)]));
    });
    for (const result of Object.values(mcp)) assert.equal(result.state, 'success');
    assert.deepEqual(Object.fromEntries(mcp.A.points.map(p => [p.key, p.value.value])), { value: 20 });
    assert.deepEqual(Object.fromEntries(mcp.B.points.map(p => [p.key, p.value.value])),
      { value: 0.75, numerator: 15, denominator: 20 });
    assert.equal(mcp.A.points[0].labels.is_plugin, 'True');
    assert.equal(mcp.B.points[0].labels.server_name, 'github');
    assert.equal(mcp.C.points.length, 3);
    assert.deepEqual(Object.fromEntries(mcp.C.points.map(p => [p.labels.stop_reason, p.value.value])),
      { end_turn: 5, refusal: 5, '': 10 });
    assert.ok(mcp.C.points.every(p => p.labels.model === 'claude-e2e'));
    assert.deepEqual(Object.fromEntries(mcp.D.points.map(p => [p.key, p.value.value])),
      { p50: 900, p95: 900, p99: 900 });
    assert.deepEqual(Object.fromEntries(mcp.E.points.map(p => [p.key, p.value.value])),
      { p50: 1500, p90: 1500 });
    assert.deepEqual(Object.fromEntries(mcp.F.points.map(p => [p.key, p.value.value])),
      { p50: 300, p90: 300 });
    assert.deepEqual(Object.fromEntries(mcp.G.points.map(p => [p.key, p.value.value])),
      { p50: 2000, p90: 3000 });
    assert.deepEqual(Object.fromEntries(mcp.H.points.map(p => [p.key, p.value.value])),
      { value: 0.5, numerator: 10, denominator: 20 });
    assert.deepEqual(Object.fromEntries(mcp.I.points.map(p => [p.key, p.value.value])),
      { value: 0.75, numerator: 15, denominator: 20 });
    assert.equal(mcp.G.points[0].labels.decision, 'accept');
    assert.deepEqual(Object.fromEntries(mcp.J.points.map(p => [p.key, p.value.value])),
      { value: 0.5, numerator: 25, denominator: 50 });
    assert.equal(mcp.J.points[0].labels.language, 'kotlin');
    assert.equal(mcp.J.points[0].labels.tool_name, 'Edit');
    assert.deepEqual(Object.fromEntries(mcp.K.points.map(p => [p.key, p.value.value])),
      { value: 2, ratio: 2/3, numerator: 10, denominator: 15 });
    assert.deepEqual(Object.fromEntries(mcp.L.points.map(p => [p.key, p.value.value])), { value: 25 });
    assert.equal(mcp.L.points[0].labels.hook_event, 'PreToolUse');
    const hookResults = await page.evaluate(async () => {
      const client = await import('/src/api/client.ts');
      const { series } = await import('/src/widgets/model.ts');
      const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
        from: 'now-1d', to: 'now', tz: 'UTC', queries: [
          { ref_id: 'A', metric_id: 'hook_executions', frame_type: 'scalar', group_by: ['hook_event'] },
          { ref_id: 'B', metric_id: 'model_users', group_by: ['model'] },
          { ref_id: 'C', metric_id: 'cache_read_ratio', group_by: ['team', 'model'] },
          { ref_id: 'D', metric_id: 'input_output_ratio', group_by: ['team', 'model'] },
          { ref_id: 'E', metric_id: 'tokens', frame_type: 'scalar', group_by: ['type'] },
          { ref_id: 'F', metric_id: 'tokens', source: 'metrics', frame_type: 'scalar',
            group_by: ['team', 'agent_name'], params: { types: ['output', 'cache_read'] } },
          { ref_id: 'G', metric_id: 'abandoned_session_ratio', group_by: ['team'] },
          { ref_id: 'H', metric_id: 'session_last_event', group_by: ['team'] },
          { ref_id: 'I', metric_id: 'usage_concentration', group_by: ['team'] },
          { ref_id: 'J', metric_id: 'onboarding_ttfu', group_by: ['team', 'platform'] },
          { ref_id: 'K', metric_id: 'onboarding_retention', group_by: ['team'] },
          { ref_id: 'L', metric_id: 'cost', frame_type: 'scalar', group_by: ['team'] },
        ],
      }) });
      return { hooks: series(response.results.A), models: series(response.results.B), cache: series(response.results.C), io: series(response.results.D), tokens: series(response.results.E), metricTokens: series(response.results.F), abandoned: series(response.results.G), last: series(response.results.H), concentration: series(response.results.I), onboarding: series(response.results.J), retention: series(response.results.K), retentionTo: response.resolved_to, cost: series(response.results.L) };
    });
    for (const key of ['cache', 'io']) assert.equal(hookResults[key].state, 'success');
    assert.deepEqual(Object.fromEntries(hookResults.cache.points.map(p => [p.key, p.value.value])),
      { value: 0.5, numerator: 3000, denominator: 6000 });
    assert.deepEqual(Object.fromEntries(hookResults.io.points.map(p => [p.key, p.value.value])),
      { value: 2, numerator: 1500, denominator: 750 });
    assert.equal(hookResults.tokens.state, 'success');
    assert.equal(hookResults.metricTokens.state, 'success');
    assert.deepEqual(Object.fromEntries(hookResults.tokens.points.map(p => [p.labels.type, p.value.value])),
      { input: 1500, output: 750, cache_read: 3000, cache_create: 1500 });
    assert.deepEqual(Object.fromEntries(hookResults.metricTokens.points.map(p => [p.key, p.value.value])), { value: 250 });
    assert.equal(hookResults.metricTokens.points[0].labels.agent_name, 'worker');
    assert.equal(hookResults.abandoned.state, 'success');
    assert.deepEqual(Object.fromEntries(hookResults.abandoned.points.map(p => [p.key, p.value.value])),
      { value: 0, numerator: 0, denominator: 5 });
    assert.equal(hookResults.last.state, 'success');
    assert.equal(hookResults.last.points.length, 1);
    assert.equal(hookResults.last.points[0].value.value, 5);
    assert.equal(hookResults.last.points[0].labels.last_event, 'api_error');
    assert.equal(hookResults.concentration.state, 'success');
    const concentration = hookResults.concentration.points;
    assert.equal(concentration.find(p => p.key === 'value').value.value, 0.2);
    assert.equal(concentration.find(p => p.key === 'numerator').value.value, 1350);
    assert.equal(concentration.find(p => p.key === 'denominator').value.value, 6750);
    assert.deepEqual(concentration.filter(p => p.key === 'lorenz_cumulative').map(p => p.value.value), [0, 1350, 2700, 4050, 5400, 6750]);
    assert.deepEqual(concentration.filter(p => p.key === 'population_share').map(p => p.value.value), [0, 0.2, 0.4, 0.6, 0.8, 1]);
    assert.equal(hookResults.onboarding.state, 'success');
    assert.equal(hookResults.onboarding.points.find(p => p.key === 'p50').value.value, 3600);
    assert.equal(hookResults.onboarding.points.find(p => p.key === 'p90').value.value, 3600);
    assert.deepEqual(hookResults.onboarding.points.filter(p => !['p50', 'p90'].includes(p.key)).map(p => p.value.value), [0, 5, 0, 0, 0]);
    assert.ok(hookResults.onboarding.points.every(p => p.labels.platform === 'linux'));
    assert.equal(hookResults.retention.state, 'success');
    assert.ok(hookResults.retention.points.filter(p => p.key === 'denominator').every(p => p.value.value === 5));
    assert.ok(hookResults.retention.points.some(p => p.labels.week_index === '0' && p.labels.cohort_week));
    for (const p of hookResults.retention.points.filter(p => p.key === 'value')) {
      const week = Number(p.labels.week_index);
      const end = Date.parse(p.labels.cohort_week + 'T00:00:00Z') + (week + 1)*7*86400000;
      assert.equal(p.value.value, end <= Date.parse(hookResults.retentionTo) ? (week === 0 ? 1 : 0) : null);
    }
    assert.equal(hookResults.cost.state, 'success');
    assert.equal(hookResults.cost.points[0].value.value, 30);
    const priced = await page.evaluate(async () => {
      const client = await import('/src/api/client.ts');
      const { series } = await import('/src/widgets/model.ts');
      const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
        from: 'now-1d', to: 'now', price_basis: 'contract', queries: [
          { ref_id: 'A', metric_id: 'cost', frame_type: 'scalar' },
          { ref_id: 'B', metric_id: 'cost', source: 'metrics', frame_type: 'scalar', group_by: ['agent_name'] },
          { ref_id: 'C', metric_id: 'cost', source: 'metrics', price_basis: 'list', frame_type: 'scalar' },
          { ref_id: 'D', metric_id: 'subagent_cost_ratio', price_basis: 'list', group_by: ['team'] },
          { ref_id: 'E', metric_id: 'subagent_cost_ratio', group_by: ['team'] },
          { ref_id: 'F', metric_id: 'cost_per_active_user', price_basis: 'list', group_by: ['team'] },
          { ref_id: 'G', metric_id: 'cost_per_user_hour', price_basis: 'list', group_by: ['team'] },
          { ref_id: 'H', metric_id: 'cost_per_active_user', group_by: ['team'] },
          { ref_id: 'I', metric_id: 'cost_per_user_hour', group_by: ['team'] },
          { ref_id: 'J', metric_id: 'model_unit_price', price_basis: 'list', group_by: ['model'] },
          { ref_id: 'K', metric_id: 'model_unit_price', group_by: ['model'] },
        ],
      }) });
      return ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K'].map(ref => series(response.results[ref]));
    });
    assert.ok(priced.every(result => result.state === 'success'));
    assert.deepEqual(priced.map(result => result.points[0].value.value), [15, 2.5, 5, 0.4, 0.4, 6, 180, 3, 90, 30/6750, 15/6750]);
    assert.deepEqual(priced[9].points.map(p => p.value.value), [30/6750, 30, 6750]);
    assert.deepEqual(priced[10].points.map(p => p.value.value), [15/6750, 15, 6750]);
    assert.equal(priced[9].points[0].labels.model, 'claude-e2e');
    assert.deepEqual(priced[5].points.map(p => p.value.value), [6, 30, 5]);
    assert.deepEqual(priced[6].points.map(p => p.value.value), [180, 30, 1/6]);
    assert.deepEqual(priced[3].points.map(p => p.value.value), [0.4, 2, 5]);
    assert.deepEqual(priced[4].points.map(p => p.value.value), [0.4, 1, 2.5]);
    assert.equal(priced[1].points[0].labels.agent_name, 'worker');
    if (role === 'admin') {
      // 다른 지표 검증 뒤에 과거 비용을 넣어 첫 사용 코호트 fixture를 보존한다.
      const baseline = points.map(JSON.parse).filter(p => JSON.parse(p.raw_json).type === 'llm_call')
        .map(p => JSON.stringify({ ...p, event_id: randomUUID(), ts: observedAt - 86400,
          raw_json: JSON.stringify({ type: 'llm_call', payload: { cost_usd: 1, model: 'claude-e2e' } }) }));
      run('docker', ['exec', '-i', clickhouse, 'clickhouse-client', '--query', 'INSERT INTO enriched_events FORMAT JSONEachRow'], baseline.join('\n'));
      const anomaly = await page.evaluate(async () => {
        const client = await import('/src/api/client.ts');
        const { series } = await import('/src/widgets/model.ts');
        const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
          from: 'now-1d', to: 'now', tz: 'UTC', queries: [
            { ref_id: 'A', metric_id: 'cost_anomaly', params: { window_days: 1 }, group_by: ['team'] },
          ],
        }) });
        return series(response.results.A);
      });
      assert.equal(anomaly.state, 'success');
      assert.deepEqual(anomaly.points.map(p => p.value.value), [1, 30, 15]);
    }
    const hookExecutions = hookResults.hooks;
    assert.equal(hookResults.models.state, 'success');
    assert.deepEqual(Object.fromEntries(hookResults.models.points.map(p => [p.key, p.value.value])), { value: 5 });
    assert.equal(hookResults.models.points[0].labels.model, 'claude-e2e');
    assert.equal(hookExecutions.state, 'success');
    assert.deepEqual(Object.fromEntries(hookExecutions.points.map(p => [p.key, p.value.value])),
      { value: 10, ratio: 1, numerator: 5, denominator: 5 });
    assert.equal(hookExecutions.points[0].labels.hook_event, 'PreToolUse');
    const localObserved = new Date((observedAt + 9*3600)*1000);
    assert.equal(tools.J.points[0].labels.hour, String(localObserved.getUTCHours()));
    assert.equal(tools.J.points[0].labels.weekday, String(localObserved.getUTCDay() || 7));

    if (role === 'owner') {
      const ownerResults = await page.evaluate(async (termContract) => {
        const client = await import('/src/api/client.ts');
        const { series } = await import('/src/widgets/model.ts');
        const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
          from: 'now-1d', to: 'now', queries: [
            { ref_id: 'A', metric_id: 'refusals', frame_type: 'scalar', group_by: ['category', 'model'] },
            { ref_id: 'B', metric_id: 'vendor_account_mismatch' },
            { ref_id: 'C', metric_id: 'contract_commitment_burn', params: { contract_id: termContract } },
          ],
        }) }, '벤더 계정 불일치 정기 E2E 점검');
        return { refusals: series(response.results.A), mismatch: series(response.results.B), burn: series(response.results.C) };
      }, termContract);
      assert.equal(ownerResults.burn.state, 'success');
      assert.deepEqual(Object.fromEntries(ownerResults.burn.points.map(p => [p.key, p.value.value])),
        { burn_ratio: 0.015, cost_usd: 15, commitment_amount: 1000 });
      await page.getByText('약정 소진률', { exact: true }).scrollIntoViewIfNeeded();
      await page.getByText('1.5%', { exact: true }).waitFor();
      const refusals = ownerResults.refusals;
      assert.equal(ownerResults.mismatch.state, 'success');
      assert.equal(ownerResults.mismatch.points[0].value.value, 5);
      assert.equal(refusals.state, 'success');
      assert.deepEqual(Object.fromEntries(refusals.points.map(p => [p.key, p.value.value])), { value: 5 });
      assert.equal(refusals.points[0].labels.category, 'policy');
      await page.getByRole('cell', { name: 'E2E 별도팀', exact: true }).waitFor();
      await page.getByRole('button', { name: '구성원 조회 · 사유 입력' }).click();
      await page.getByLabel('사유 (10–500자)').fill('정기 계정 점검을 위한 E2E 검증');
      await page.getByRole('button', { name: '사유 기록 후 조회' }).click();
      await page.getByRole('cell', { name: 'owner@e2e.test', exact: true }).waitFor();
      assert.equal(sql('SELECT count(*) FROM dashboard.audit_log'), '2');
      assert.equal(sql("SELECT count(*) FROM dashboard.audit_log WHERE action='query' AND target='vendor_account_mismatch'"), '1');
      const installationVersions = [...new Map(points.map(JSON.parse).map(p => [p.installation_id, p])).values()]
        .map(p => JSON.stringify({ ...p, event_id: randomUUID(), ts: observedAt + 1, signal: 'metric',
          raw_json: JSON.stringify({ envelope: { client: { version: 'fixture-product-1' } } }) }));
      run('docker', ['exec', '-i', clickhouse, 'clickhouse-client', '--query', 'INSERT INTO enriched_events FORMAT JSONEachRow'], installationVersions.join('\n'));
      const installations = await page.evaluate(async () => {
        const client = await import('/src/api/client.ts');
        const items = [];
        let cursor = '';
        for (let page = 0; page < 3; page++) {
          const response = await client.request('/installations?limit=2&cursor=' + encodeURIComponent(cursor),
            {}, '설치 목록 연동 정기 감사 점검');
          items.push(...response.items);
          cursor = response.next_cursor;
          if (!cursor) break;
        }
        return { items, cursor };
      });
      assert.equal(installations.items.length, 5);
      assert.equal(new Set(installations.items.map(i => i.installation_id)).size, 5);
      assert.equal(installations.cursor, null);
      assert.ok(installations.items.every(i => i.member_email_masked === '***@e2e.test' &&
        i.product_versions.claude_code === 'fixture-product-1' &&
        Date.parse(i.last_event_at) === (observedAt + 1)*1000));
      assert.equal(sql("SELECT count(*) FROM dashboard.audit_log WHERE action='installations'"), '3');
      const sessionId = JSON.parse(points[0]).installation_id;
      const expectedEvents = points.map(JSON.parse).filter(p => JSON.parse(p.raw_json).envelope?.session_id === sessionId)
        .sort((a,b) => a.ts-b.ts || (JSON.parse(a.raw_json).sequence || 0)-(JSON.parse(b.raw_json).sequence || 0) || a.event_id.localeCompare(b.event_id));
      const session = await page.evaluate(async (sessionId) => {
        const { opsApi, eventDetails } = await import('/src/api/operations.ts');
        const events = [];
        let cursor = '', pages = 0, summary;
        do {
          const response = await opsApi.events(sessionId,
            new URLSearchParams({ from: 'now-1d', to: 'now', limit: '8', cursor: cursor || '' }),
            '세션 상관 분석 정기 감사 점검');
          events.push(...response.items);
          summary = response.session;
          cursor = response.next_cursor;
          pages++;
          if (pages > 20) throw new Error('세션 페이지가 종료되지 않음');
        } while (cursor);
        return { events, summary, pages, details: events.map(eventDetails) };
      }, sessionId);
      assert.deepEqual(session.events.map(e => e.event_id), expectedEvents.map(e => e.event_id));
      assert.equal(session.summary.event_count, expectedEvents.length);
      assert.equal(session.summary.installation_id, sessionId);
      assert.ok(session.details.some(d => d.includes('$2.0000') && d.includes('100 tok')));
      assert.equal(sql("SELECT count(*) FROM dashboard.audit_log WHERE action='session_events'"), String(session.pages));


    } else {
      assert.equal(await page.getByRole('cell', { name: 'E2E 별도팀', exact: true }).count(), 0);
      await page.getByText('구성원 이메일 목록은 owner 권한으로 제공됩니다.', { exact: true }).waitFor();
    }
    if (role === 'admin') {
      const history = points.map(JSON.parse).filter(p => JSON.parse(p.raw_json).type === 'llm_call')
        .flatMap(p => [2, 3].map(days => JSON.stringify({ ...p, event_id: randomUUID(), ts: observedAt - days*86400,
          raw_json: JSON.stringify({ type: 'llm_call', payload: { cost_usd: 1, model: 'claude-e2e' } }) })));
      run('docker', ['exec', '-i', clickhouse, 'clickhouse-client', '--query', 'INSERT INTO enriched_events FORMAT JSONEachRow'], history.join('\n'));
    }
    const scenarioRun = await page.evaluate(async () => {
      const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
      const { adaptResult } = await import('/src/api/frames.ts');
      const { resultFilters } = await import('/src/pages/scenarios/resultModel.ts');
      const started = await scenarioApi.start('S1-3', { params: { from: 'now-1d', to: 'now', moving_avg_days: 3, spike_threshold_pct: 50 } });
      let run = started.run;
      const deadline = Date.now() + 60000;
      while (activeRun(run) && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 1000));
        run = (await scenarioApi.get(run.run_id)).run;
      }
      return { initial: started.run.status, run, filters: resultFilters(run),
        adapted: Object.fromEntries(Object.entries(run.result?.frames || {}).map(([id, frame]) => [id, adaptResult(frame)])) };
    });
    assert.equal(scenarioRun.initial, 'queued');
    assert.equal(scenarioRun.run.status, 'succeeded');
    assert.deepEqual(Object.keys(scenarioRun.adapted), ['cost', 'cost_anomaly', 'api_retry_attempts']);
    assert.ok(Object.values(scenarioRun.adapted).every(result => result.status !== 'error'));
    assert.ok(scenarioRun.run.result.findings.some(finding => finding.rule_id === 'retry_cost'));
    if (role === 'admin') assert.ok(scenarioRun.run.result.findings.some(finding => finding.rule_id === 'spike_day' && finding.evidence.ratio === 1));
    if (role === 'admin') assert.deepEqual(scenarioRun.filters.filters.team_ids, ['00000000-0000-0000-0000-000000000010']);
    const cancelled = await page.evaluate(async () => {
      const { scenarioApi } = await import('/src/api/scenarios.ts');
      const { run } = await scenarioApi.start('S1-3', { params: { from: 'now-1d', to: 'now' } });
      return (await scenarioApi.cancel(run.run_id)).run;
    });
    assert.equal(cancelled.status, 'cancelled');
    const history = await page.evaluate(async () => {
      const { scenarioApi } = await import('/src/api/scenarios.ts');
      return scenarioApi.list();
    });
    assert.ok(history.items.some(row => row.run_id === scenarioRun.run.run_id && row.status === 'succeeded'));
    assert.ok(history.items.some(row => row.run_id === cancelled.run_id && row.status === 'cancelled'));
    assert.ok(history.items.every(row => !('result' in row) && !('params' in row)));
    if (role === 'admin') assert.equal(history.items.length, 2);

    await page.evaluate(id => {
      window.history.pushState(null, '', `/runs/${id}`);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, scenarioRun.run.run_id);
    await page.getByLabel('시나리오 판정', { exact: true }).waitFor();
    await page.getByRole('heading', { name: '재시도 요청 비율이 5% 이상입니다', exact: true }).waitFor();
    if (role === 'admin') await page.getByRole('heading', { name: '일 비용이 이동평균 대비 임계를 초과했습니다', exact: true }).waitFor();
    const teamCostWidget = page.locator('[data-result-widget="W1.3"][data-result-metric="cost"]');
    await teamCostWidget.getByText('팀별 비용', { exact: true }).waitFor();
    await teamCostWidget.locator('summary').click();
    assert.ok((await teamCostWidget.innerText()).includes('$30.00'));
    await page.screenshot({ path: resolve(artifacts, `${role}-scenario.png`), fullPage: true });

    await page.screenshot({ path: resolve(artifacts, `${role}.png`), fullPage: true });
    await Promise.all(responseReads);
    await context.close();
  }
  const result = { scope: '인증·P5 설정 및 실제 frontend 클라이언트의 카탈로그·공통 지표 50개 및 owner 전용 지표 3개 집계; ingest 및 전체 PROJ-156 수용 검증 아님', passed: true,
    verifiedInstallations: { count: 5, pages: 3, audited: true, actualFrontendCard: false },
    verifiedScenarios: { count: 46, actualFrontendClient: true, parameterFormValidation: true, actualCatalogUI: false, runs: { scenario: 'S1-3', completed: true, cancelled: true, actualResultUI: true, actualHistoryClient: true } },
    verifiedSessionEvents: { actualFrontendClient: true, paginated: true, audited: true, actualSessionSearchUI: false },
    verifiedMetrics: [...metricFixtures.map(([metric, , value]) => ({ metric, expected: value*5 })),
      { metric: 'active_users', expected: 5 }, { metric: 'adoption_rate', owner: 5/7, admin: 5/6 },
      { metric: 'telemetry_coverage', expected: 1 }, { metric: 'automation_ratio', expected: 0 },
      { metric: 'integration_depth', expected: 1 }, { metric: 'command_prompt_ratio', expected: 0.5 },
      { metric: 'prompts_per_session', p50: 2, p90: 2, buckets: [0, 5, 0, 0, 0] },
      { metric: 'tool_calls', expected: 15, failedOnly: 5 }, { metric: 'tool_failure_rate', expected: 0.5 },
      { metric: 'read_tool_density', p50: 3, p90: 3 },
      { metric: 'api_retry_attempts', expected: 1/3 }, { metric: 'rate_limit_events', expected: 5 },
      { metric: 'auto_approval_ratio', expected: 0.5 }, { metric: 'tool_rejections', expected: 5 },
      { metric: 'api_error_rate', expected: 1/3 }, { metric: 'usage_heatmap', expected: 10 },
      { metric: 'compactions', expected: 15 }, { metric: 'compaction_reduction', expected: 0.81 },
      { metric: 'mcp_connections', expected: 20 }, { metric: 'mcp_failure_ratio', expected: 0.75 },
      { metric: 'llm_stop_reasons', end_turn: 5, refusal: 5, missing: 10 },
      { metric: 'llm_duration_ms', p50: 900, p95: 900, p99: 900 },
      { metric: 'turn_duration_ms', p50: 1500, p90: 1500 },
      { metric: 'llm_ttft_ms', p50: 300, p90: 300 },
      { metric: 'gate_wait_ms', p50: 2000, p90: 3000 },
      { metric: 'rubber_stamp_ratio', expected: 0.5, threshold2001: 0.75 },
      { metric: 'edit_acceptance_rate', expected: 0.5 },
      { metric: 'subagent_activity', count: 2, ratio: 2/3 },
      { metric: 'hook_blocking', expected: 25 },
      { metric: 'hook_executions', expected: 10, ratio: 1, sessions: 5 },
      { metric: 'refusals', owner: 5 },
      { metric: 'model_users', expected: 5 },
      { metric: 'cache_read_ratio', expected: 0.5 }, { metric: 'input_output_ratio', expected: 2 },
      { metric: 'tokens', events: 6750, selectedMetrics: 250 },
      { metric: 'abandoned_session_ratio', expected: 0, sessions: 5 },
      { metric: 'session_last_event', expected: 5, last_event: 'api_error' },
      { metric: 'usage_concentration', expected: 0.2, total: 6750 },
      { metric: 'onboarding_ttfu', p50: 3600, p90: 3600 },
      { metric: 'onboarding_retention', cohort_installations: 5 },
      { metric: 'vendor_account_mismatch', expected: 5, scope: 'owner' },
      { metric: 'cost', eventsList: 30, eventsContract: 15, metricsList: 5, metricsContract: 2.5 },
      { metric: 'subagent_cost_ratio', expected: 0.4 },
      { metric: 'cost_per_active_user', list: 6, contract: 3 },
      { metric: 'cost_per_user_hour', list: 180, contract: 90 },
      { metric: 'model_unit_price', list: 30/6750, contract: 15/6750 },
      { metric: 'cost_anomaly', expected: 1, actual: 30, baseline: 15 },
      { metric: 'contract_commitment_burn', expected: 0.015, costUsd: 15, commitmentAmount: 1000 }],
    backend: run('git', ['rev-parse', 'HEAD']),
    jarSha256: createHash('sha256').update(readFileSync(resolve(backend, 'apps/dashboard-api/build/libs/dashboard-api-0.0.1-SNAPSHOT.jar'))).digest('hex'), frontend: run('git', ['-C', frontend, 'rev-parse', 'HEAD']),
    unexpectedOrUnimplementedResponses: failures };
  writeFileSync(resolve(artifacts, 'result.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
} catch (error) {
  writeFileSync(resultFile, JSON.stringify({ passed: false, status: 'failed', error: String(error), finishedAt: new Date().toISOString() }, null, 2));
  throw error;
} finally {
  if (browser) await browser.close();
  for (const child of processes.reverse()) child.kill('SIGTERM');
  for (const stream of logStreams) stream.end();
  if (container) run('docker', ['stop', container]);
  if (clickhouse) run('docker', ['stop', clickhouse]);
  rmSync(work, { recursive: true, force: true });
}
