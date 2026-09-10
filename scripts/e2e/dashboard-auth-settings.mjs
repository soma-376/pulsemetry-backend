// 실제 frontend + dashboard + 격리 PostgreSQL. HTTP 응답을 가로채거나 목업으로 바꾸지 않는다.
// 이 테스트는 인증·P5와 브라우저 API 클라이언트의 지표 메타와 지표 23개 집계를 검증하며 전체 PROJ-156 E2E를 대체하지 않는다.
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync, createWriteStream, rmSync, readFileSync } from 'node:fs';
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
const processes = [];
const logStreams = [];
let container;
let clickhouse;
let browser;
const tenant = randomUUID();
const api = 'http://127.0.0.1:18081';
const ui = 'http://127.0.0.1:15173';
const run = (cmd, args, input) => {
  const result = spawnSync(cmd, args, { cwd: backend, input, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`${cmd} failed: ${result.stderr}`);
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
  const points = [];
  for (let index = 0; index < 5; index++) {
    const memberId = randomUUID(), installationId = randomUUID(), invitationId = randomUUID();
    sql(`INSERT INTO enrollment.members(id,tenant_id,email) VALUES ('${memberId}','${tenant}','fixture-${index}@e2e.test');
      INSERT INTO enrollment.team_memberships(team_id,member_id)
        VALUES ('00000000-0000-0000-0000-000000000010','${memberId}');
      INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at)
        VALUES ('${invitationId}','${tenant}','${memberId}','00000000-0000-0000-0000-000000000001','${randomUUID()}',now()+interval '1 day');
      INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform)
        VALUES ('${installationId}','${tenant}','${memberId}','${invitationId}','linux');`);
    for (const [, name, value] of metricFixtures) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'metric', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ point: { name, value, aggregation_temporality: 1, attrs: { type: 'user', start_type: 'fresh' } } }),
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
        payload: { tool_name: 'Read', tool_kind: 'function', action: 'read', success } }),
    }));
    for (const [attempt, status_code] of [[1, 200], [2, 429], [null, null]]) points.push(JSON.stringify({
      event_id: randomUUID(), ts: observedAt,
      tenant_id: tenant, installation_id: installationId, signal: 'log', product: 'claude_code',
      team_ids_as_of: ['00000000-0000-0000-0000-000000000010'], enrichment_json: '{}',
      raw_json: JSON.stringify({ type: 'llm_call', envelope: { session_id: installationId },
        payload: { model: 'claude-e2e', attempt, status_code, error_type: status_code === 429 ? 'rate_limit' : null } }),
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
    const localObserved = new Date((observedAt + 9*3600)*1000);
    assert.equal(tools.J.points[0].labels.hour, String(localObserved.getUTCHours()));
    assert.equal(tools.J.points[0].labels.weekday, String(localObserved.getUTCDay() || 7));

    if (role === 'owner') {
      await page.getByRole('cell', { name: 'E2E 별도팀', exact: true }).waitFor();
      await page.getByRole('button', { name: '구성원 조회 · 사유 입력' }).click();
      await page.getByLabel('사유 (10–500자)').fill('정기 계정 점검을 위한 E2E 검증');
      await page.getByRole('button', { name: '사유 기록 후 조회' }).click();
      await page.getByRole('cell', { name: 'owner@e2e.test', exact: true }).waitFor();
      assert.equal(sql('SELECT count(*) FROM dashboard.audit_log'), '1');
    } else {
      assert.equal(await page.getByRole('cell', { name: 'E2E 별도팀', exact: true }).count(), 0);
      await page.getByText('구성원 이메일 목록은 owner 권한으로 제공됩니다.', { exact: true }).waitFor();
    }
    await page.screenshot({ path: resolve(artifacts, `${role}.png`), fullPage: true });
    await Promise.all(responseReads);
    await context.close();
  }
  const result = { scope: '인증·P5 설정 및 실제 frontend 클라이언트의 카탈로그·지표 23개 집계; ingest 및 전체 PROJ-156 수용 검증 아님', passed: true,
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
      { metric: 'compactions', expected: 15 }, { metric: 'compaction_reduction', expected: 0.81 }],
    backend: run('git', ['rev-parse', 'HEAD']),
    jarSha256: createHash('sha256').update(readFileSync(resolve(backend, 'apps/dashboard-api/build/libs/dashboard-api-0.0.1-SNAPSHOT.jar'))).digest('hex'), frontend: run('git', ['-C', frontend, 'rev-parse', 'HEAD']),
    unexpectedOrUnimplementedResponses: failures };
  writeFileSync(resolve(artifacts, 'result.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
} finally {
  if (browser) await browser.close();
  for (const child of processes.reverse()) child.kill('SIGTERM');
  for (const stream of logStreams) stream.end();
  if (container) run('docker', ['stop', container]);
  if (clickhouse) run('docker', ['stop', clickhouse]);
  rmSync(work, { recursive: true, force: true });
}
