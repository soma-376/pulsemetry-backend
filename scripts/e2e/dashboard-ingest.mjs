import { createHmac, randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

// 수집 대상 행은 SQL로 넣지 않는다. 인증용 계정은 호출자가 만든 격리 fixture를 사용한다.
export async function verifyDashboardIngest({ page, launch, waitFor, sql, backend, work, port, chPort, installations, tenant, artifacts }) {
  const secret = randomUUID();
  const endpoint = 'http://127.0.0.1:14316';
  launch('java', ['-jar', resolve(backend, 'apps/telemetry-ingest/build/libs/telemetry-ingest-0.0.1-SNAPSHOT.jar')], {
    PULSEMETRY_INGEST_PORT: '14316', PULSEMETRY_DB_URL: `jdbc:postgresql://127.0.0.1:${port}/pulsemetry`,
    PULSEMETRY_DB_USERNAME: 'pulsemetry', PULSEMETRY_DB_PASSWORD: 'e2e-only-password',
    PULSEMETRY_TOKEN_HASH_SECRET: secret, PULSEMETRY_CLICKHOUSE_URL: `http://127.0.0.1:${chPort}`,
    PULSEMETRY_ARCHIVE_DIR: resolve(work, 'archive'),
  }, backend, 'ingest');
  await waitFor(async () => (await fetch(`${endpoint}/v1/healthz`)).ok);
  // 호출자가 이번 테스트만을 위해 생성한 격리 컨테이너다. 이전 직접 주입 fixture를 제거한다.
  const cleared = await fetch(`http://127.0.0.1:${chPort}/?query=TRUNCATE%20TABLE%20enriched_events`, { method: 'POST' });
  assert.equal(cleared.status, 200, await cleared.text());
  const tool = `ingest-e2e-${randomUUID()}`;
  const model = `ingest-model-${randomUUID()}`;
  const scenarioModel = `ingest-scenario-${randomUUID()}`;
  const scenarioFrom = new Date(Math.floor(Date.now() / 1000) * 1000).toISOString();
  const attr = (key, value) => ({ key, value: { stringValue: value } });
  const query = () => page.evaluate(async ({ tool, model }) => {
    const client = await import('/src/api/client.ts');
    const { series } = await import('/src/widgets/model.ts');
    const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
      from: 'now-1d', to: 'now', compare: 'none', queries: [
        { ref_id: 'A', metric_id: 'tool_calls', frame_type: 'scalar', group_by: ['team', 'tool_name'] },
        { ref_id: 'B', metric_id: 'cost', source: 'metrics', price_basis: 'list', frame_type: 'scalar', group_by: ['team', 'model'] },
        { ref_id: 'C', metric_id: 'llm_ttft_ms', frame_type: 'table', group_by: ['product', 'model'] },
      ],
    }) });
    return Object.fromEntries(Object.entries(response.results).map(([ref, result]) => {
      if (result.status !== 200) throw new Error(JSON.stringify(result));
      return [ref, series(result).points.filter(p => p.labels.tool_name === tool || p.labels.model === model)];
    }));
  }, { tool, model });
  for (const [index, installation] of installations.entries()) {
    const token = `ptt_${randomUUID()}`;
    const hash = createHmac('sha256', secret).update(token).digest('hex');
    sql(`INSERT INTO enrollment.telemetry_tokens(installation_id,token_hash) VALUES ('${installation}','${hash}');`);
    const resource = { attributes: [
      attr('service.name', 'claude-code'), attr('tenant.id', randomUUID()),
      attr('developer.installation_id', randomUUID()),
    ] };
    const now = BigInt(Date.now()) * 1000000n;
    const body = JSON.stringify({ resourceLogs: [{ resource, scopeLogs: [{ logRecords: [{ timeUnixNano: String(BigInt(Date.now()) * 1000000n),
      body: { stringValue: 'claude_code.tool_result' }, attributes: [
        attr('session.id', `ingest-${installation}`), attr('tool_use_id', randomUUID()),
        attr('tool_name', tool), { key: 'success', value: { boolValue: true } },
      ],
    }, { timeUnixNano: String(now), body: { stringValue: 'claude_code.api_request' }, attributes: [
      attr('session.id', `ingest-${installation}`), attr('model', scenarioModel),
      attr('request_id', randomUUID()), { key: 'cost_usd', value: { doubleValue: index + 1 } },
      { key: 'attempt', value: { intValue: index === 0 ? '2' : '1' } },
      ...[['input_tokens', 200], ['output_tokens', 10], ['cache_read_tokens', 0], ['cache_creation_tokens', 0]]
        .map(([key, value]) => ({ key, value: { intValue: String(value) } })),
    ] }] }] }] });
    const metrics = JSON.stringify({ resourceMetrics: [{ resource, scopeMetrics: [{ metrics:
      [1, 2].map(temporality => ({ name: 'claude_code.cost.usage', unit: 'USD', sum: {
        aggregationTemporality: temporality, isMonotonic: true, dataPoints: [{
          startTimeUnixNano: String(now - 1000000000n), timeUnixNano: String(now),
          asDouble: temporality === 1 ? index + 1 : 999,
          attributes: [attr('session.id', `ingest-${installation}`), attr('model', model)],
        }],
      } })),
    }] }] });
    const traces = JSON.stringify({ resourceSpans: [{ resource, scopeSpans: [{ spans: [{
      traceId: randomUUID().replaceAll('-', ''), spanId: randomUUID().replaceAll('-', '').slice(0, 16),
      name: 'claude_code.llm_request', kind: 1,
      startTimeUnixNano: String(now), endTimeUnixNano: String(now + 1000000000n),
      attributes: [attr('session.id', `ingest-${installation}`), attr('model', model),
        attr('request_id', randomUUID()), { key: 'ttft_ms', value: { intValue: String((index + 1) * 100) } }],
    }] }] }] });
    for (const [signal, payload] of [['logs', body], ['metrics', metrics], ['traces', traces]]) {
      const push = token => fetch(`${endpoint}/v1/${signal}`, { method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: payload });
      if (index === 0) assert.equal((await push('invalid')).status, 401);
      // 같은 바이트를 두 번 전송해 ReplacingMergeTree FINAL 조회까지 검증한다.
      for (let retry = 0; retry < 2; retry++) {
        const response = await push(token);
        assert.equal(response.status, 200, await response.text());
      }
    }
    if (index === 3) {
      const results = await query();
      for (const points of Object.values(results)) {
        assert.ok(points.length > 0);
        assert.ok(points.every(p => p.value.state === 'masked' && p.value.value === null));
      }
    }
  }
  const results = await query();
  for (const points of [results.A, results.B]) {
    assert.ok(points.length > 0);
    assert.ok(points.every(p => p.labels.team === '00000000-0000-0000-0000-000000000010'));
  }
  assert.equal(results.A.length, 1);
  assert.deepEqual(results.A[0].value, { state: 'value', value: 5 });
  assert.equal(results.B.length, 1);
  assert.deepEqual(results.B[0].value, { state: 'value', value: 15 });
  const quantiles = Object.fromEntries(results.C.map(p => [p.key, p.value.value]));
  assert.equal(quantiles.p50, 300);
  assert.equal(quantiles.p90, 500);

  const response = await fetch(`http://127.0.0.1:${chPort}/?query=${encodeURIComponent(
    `SELECT tenant_id, installation_id, signal, team_ids_as_of FROM enriched_events FINAL WHERE JSONExtractString(raw_json, 'payload', 'tool_name') = '${tool}' OR JSONExtractString(raw_json, 'payload', 'model') = '${model}' OR JSONExtractString(raw_json, 'point', 'attrs', 'model') = '${model}' OR JSONExtractString(raw_json, 'payload', 'model') = '${scenarioModel}' FORMAT JSONEachRow`)}`);
  assert.equal(response.status, 200);
  const rows = (await response.text()).trim().split('\n').map(JSON.parse);
  assert.equal(rows.length, 25);
  assert.ok(rows.every(row => row.tenant_id === tenant));
  assert.ok(rows.every(row => row.team_ids_as_of.includes('00000000-0000-0000-0000-000000000010')));
  for (const [signal, count] of [['log', 2], ['metric', 2], ['span', 1]]) {
    assert.deepEqual(rows.filter(row => row.signal === signal).map(row => row.installation_id).sort(),
      installations.flatMap(id => Array(count).fill(id)).sort());
  }
  // 이전 직접 주입 fixture보다 뒤의 고정 구간으로 수집 데이터만 실행한다.
  const scenario = await page.evaluate(async ({ from }) => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    const started = await scenarioApi.start('S1-3', { params: {
      from, to: new Date(Date.now() + 1000).toISOString(), moving_avg_days: 3, spike_threshold_pct: 50,
    } });
    let run = started.run;
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { initial: started.run.status, run, series: Object.fromEntries(
      Object.entries(run.result?.frames || {}).map(([id, frame]) => [id, series(frame)])) };
  }, { from: scenarioFrom });
  assert.equal(scenario.initial, 'queued');
  assert.equal(scenario.run.status, 'succeeded');
  assert.deepEqual(Object.keys(scenario.series), ['cost', 'cost_anomaly', 'api_retry_attempts']);
  const modelCosts = scenario.series.cost.points.filter(p => p.labels.model);
  assert.equal(modelCosts.length, 1);
  assert.equal(modelCosts[0].labels.model, scenarioModel);
  assert.deepEqual(modelCosts[0].value, { state: 'value', value: 15 });
  const teamCosts = scenario.series.cost.points.filter(p => p.labels.team);
  assert.equal(teamCosts.length, 1);
  assert.deepEqual(teamCosts[0].value, { state: 'value', value: 15 });
  assert.ok(scenario.series.api_retry_attempts.points.some(p => p.value.state === 'value' && p.value.value === 0.2));
  assert.ok(scenario.run.result.findings.some(f => f.rule_id === 'retry_cost'));
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, scenario.run.run_id);
  await page.getByLabel('시나리오 판정', { exact: true }).waitFor();
  await page.getByRole('heading', { name: '재시도 요청 비율이 5% 이상입니다', exact: true }).waitFor();
  const teamWidget = page.locator('[data-result-widget="W1.3"][data-result-metric="cost"]');
  await teamWidget.getByText('팀별 비용', { exact: true }).waitFor();
  await teamWidget.locator('summary').click();
  assert.ok((await teamWidget.innerText()).includes('$15.00'));
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-scenario.png'), fullPage: true });
  const contextRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S1-5', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, ratio: series(run.result?.frames.input_output_ratio) };
  }, scenarioFrom);
  assert.equal(contextRun.run.status, 'succeeded');
  assert.equal(contextRun.run.progress.step, 3);
  assert.equal(contextRun.run.progress.total, 3);
  assert.ok(contextRun.ratio.points.some(p => p.value.state === 'value' && p.value.value === 20));
  assert.equal(contextRun.run.result.findings[0].rule_id, 'high_io_ratio');
  assert.equal(contextRun.run.result.findings[0].evidence.ratio, 20);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, contextRun.run.run_id);
  await page.getByRole('heading', { name: '입력/출력 토큰 비율이 임계값을 초과했습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-context-scenario.png'), fullPage: true });
  return { signals: ['logs', 'metrics', 'traces'], actualFrontendClient: true, admin: true,
    authenticatedIdentity: true, asOfTeam: true, maskedAtFour: true, duplicatePushes: 30, storedRows: 25,
    contextScenario: { id: 'S1-5', runId: contextRun.run.run_id, actualResultUI: true, ratio: 20, threshold: 10 },
    scenario: { id: 'S1-3', runId: scenario.run.run_id, actualResultUI: true, costUsd: 15, retryRatio: 0.2 },
    toolCalls: 5, deltaCostUsd: 15, cumulativeCostExcluded: true, ttftMs: quantiles };
}
