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
    const body = JSON.stringify({ resourceLogs: [{ resource, scopeLogs: [{ logRecords: [{ timeUnixNano: String(now),
      body: { stringValue: 'claude_code.tool_result' }, attributes: [
        attr('session.id', `ingest-${installation}`), attr('tool_use_id', randomUUID()),
        attr('tool_name', tool), attr('agent_id', `worker-${installation}`),
        { key: 'event.sequence', value: { intValue: '1' } },
        { key: 'success', value: { boolValue: index !== 0 } },
      ],
    }, { timeUnixNano: String(now), body: { stringValue: 'claude_code.api_request' }, attributes: [
      attr('session.id', `ingest-${installation}`), attr('model', scenarioModel),
      { key: 'event.sequence', value: { intValue: '2' } },
      attr('request_id', randomUUID()), { key: 'cost_usd', value: { doubleValue: index + 1 } },
      { key: 'attempt', value: { intValue: index === 0 ? '2' : '1' } },
      ...[['input_tokens', 200], ['output_tokens', 10], ['cache_read_tokens', 0], ['cache_creation_tokens', 0]]
        .map(([key, value]) => ({ key, value: { intValue: String(value) } })),
    ] }, ...[0, 1].map(sequence => ({ timeUnixNano: String(now),
      body: { stringValue: 'claude_code.user_prompt' }, attributes: [attr('session.id', `ingest-${installation}`),
        { key: 'event.sequence', value: { intValue: String(sequence) } },
        { key: 'prompt_length', value: { intValue: '42' } }],
    }))] }] }] });
    const metrics = JSON.stringify({ resourceMetrics: [{ resource, scopeMetrics: [{ metrics:
      [1, 2].map(temporality => ({ name: 'claude_code.cost.usage', unit: 'USD', sum: {
        aggregationTemporality: temporality, isMonotonic: true, dataPoints: [{
          startTimeUnixNano: String(now - 1000000000n), timeUnixNano: String(now),
          asDouble: temporality === 1 ? index + 1 : 999,
          attributes: [attr('session.id', `ingest-${installation}`), attr('model', model), attr('query_source', 'subagent'), attr('effort', 'high'), attr('speed', 'fast')],
        }],
      } })).concat([{ name: 'claude_code.session.count', unit: 'count', sum: {
        aggregationTemporality: 1, isMonotonic: true, dataPoints: [{
          startTimeUnixNano: String(now - 1000000000n), timeUnixNano: String(now), asDouble: 1,
          attributes: [attr('session.id', `ingest-${installation}`), attr('start_type', 'fresh')],
        }],
      } }]),
    }] }] });
    const traces = JSON.stringify({ resourceSpans: [{ resource, scopeSpans: [{ spans: [{
      traceId: randomUUID().replaceAll('-', ''), spanId: randomUUID().replaceAll('-', '').slice(0, 16),
      name: 'claude_code.llm_request', kind: 1,
      startTimeUnixNano: String(now), endTimeUnixNano: String(now + 1000000000n),
      attributes: [attr('session.id', `ingest-${installation}`), attr('model', model),
        attr('request_id', randomUUID()), { key: 'ttft_ms', value: { intValue: String((index + 1) * 100) } }],
    }, {
      traceId: randomUUID().replaceAll('-', ''), spanId: randomUUID().replaceAll('-', '').slice(0, 16),
      name: 'claude_code.tool.blocked_on_user', kind: 1,
      startTimeUnixNano: String(now), endTimeUnixNano: String(now + 120000000000n),
      attributes: [attr('session.id', `ingest-${installation}`), attr('decision', 'accept')],
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
    `SELECT tenant_id, installation_id, signal, team_ids_as_of FROM enriched_events FINAL WHERE JSONExtractString(raw_json, 'payload', 'tool_name') = '${tool}' OR JSONExtractString(raw_json, 'payload', 'model') = '${model}' OR JSONExtractString(raw_json, 'point', 'attrs', 'model') = '${model}' OR JSONExtractString(raw_json, 'payload', 'model') = '${scenarioModel}' OR JSONExtractString(raw_json, 'type') IN ('user_prompt','tool_gate') OR JSONExtractString(raw_json, 'point', 'name') = 'claude_code.session.count' FORMAT JSONEachRow`)}`);
  assert.equal(response.status, 200);
  const rows = (await response.text()).trim().split('\n').map(JSON.parse);
  assert.equal(rows.length, 45);
  assert.ok(rows.every(row => row.tenant_id === tenant));
  assert.ok(rows.every(row => row.team_ids_as_of.includes('00000000-0000-0000-0000-000000000010')));
  for (const [signal, count] of [['log', 4], ['metric', 3], ['span', 2]]) {
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
  const agentRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S7-1', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, series: Object.fromEntries(Object.entries(run.result?.frames || {}).map(([id, frame]) => [id, series(frame)])) };
  }, scenarioFrom);
  assert.equal(agentRun.run.status, 'succeeded');
  assert.equal(agentRun.run.progress.step, 4);
  assert.equal(agentRun.run.progress.total, 4);
  assert.ok(agentRun.series.tool_failure_rate.points.some(p => p.value.state === 'value' && p.value.value === 0.2));
  assert.ok(agentRun.series.tool_calls.points.some(p => p.value.state === 'value' && p.value.value === 5));
  assert.ok(agentRun.series.cost.points.some(p => p.value.state === 'value' && p.value.value === 15));
  assert.equal(agentRun.run.result.findings[0].rule_id, 'high_tool_failure_rate');
  assert.equal(agentRun.run.result.findings[0].evidence.ratio, 0.2);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, agentRun.run.run_id);
  await page.getByRole('heading', { name: '도구 실패율이 임계값을 초과했습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-agent-scenario.png'), fullPage: true });
  const budgetEvidence = [];
  for (const budget of [{ usd: 7.5 }, { tokens_m: 0.0005 }]) {
    const budgetRun = await page.evaluate(async ({ from, budget }) => {
      const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
      const { series } = await import('/src/widgets/model.ts');
      let { run } = await scenarioApi.start('S1-1', { params: { from, to: new Date(Date.now() + 1000).toISOString(),
        budget_by_team: { '00000000-0000-0000-0000-000000000010': budget } } });
      const deadline = Date.now() + 60000;
      while (activeRun(run) && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 500));
        run = (await scenarioApi.get(run.run_id)).run;
      }
      return { run, cost: series(run.result?.frames.cost), tokens: series(run.result?.frames.tokens) };
    }, { from: scenarioFrom, budget });
    assert.equal(budgetRun.run.status, 'succeeded');
    assert.equal(budgetRun.run.progress.step, 3);
    assert.equal(budgetRun.run.progress.total, 3);
    assert.ok(budgetRun.cost.points.some(p => p.value.state === 'value' && p.value.value === 15));
    assert.ok(budgetRun.tokens.points.some(p => p.value.state === 'value' && p.value.value === 1050));
    assert.equal(budgetRun.run.result.findings.length, 1);
    const finding = budgetRun.run.result.findings[0];
    assert.equal(finding.rule_id, 'budget_exceeded');
    assert.equal(finding.evidence.unit, budget.usd ? 'USD' : 'million_tokens');
    assert.ok(Math.abs(finding.evidence.ratio - (budget.usd ? 2 : 2.1)) < 1e-10);
    await page.evaluate(id => {
      window.history.pushState(null, '', `/runs/${id}`);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, budgetRun.run.run_id);
    // 연속 예산 실행은 제목이 같으므로 새 실행 ID가 렌더될 때까지 기다린다.
    await page.getByLabel('시나리오 판정', { exact: true }).getByText(budgetRun.run.run_id.slice(0, 8), { exact: false }).waitFor();
    await page.getByRole('heading', { name: '관측 사용량이 입력한 팀 예산을 초과했습니다', exact: true }).waitFor();
    await page.screenshot({ path: resolve(artifacts, `admin-ingest-budget-${budget.usd ? 'usd' : 'tokens'}.png`), fullPage: true });
    budgetEvidence.push({ runId: budgetRun.run.run_id, unit: finding.evidence.unit, ratio: finding.evidence.ratio });
  }
  const abandonedRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S4-2', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, ratio: series(run.result?.frames.abandoned_session_ratio) };
  }, scenarioFrom);
  assert.equal(abandonedRun.run.status, 'succeeded');
  assert.equal(abandonedRun.run.progress.step, 3);
  assert.equal(abandonedRun.run.progress.total, 3);
  assert.deepEqual(Object.keys(abandonedRun.run.result.frames).sort(), ['abandoned_session_ratio','api_error_rate','session_last_event']);
  assert.ok(abandonedRun.ratio.points.some(p => p.value.state === 'value' && p.value.value === 1));
  assert.equal(abandonedRun.run.result.findings.length, 1);
  assert.equal(abandonedRun.run.result.findings[0].rule_id, 'sessions_without_output');
  assert.equal(abandonedRun.run.result.findings[0].evidence.ratio, 1);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, abandonedRun.run.run_id);
  try {
    await page.getByRole('heading', { name: '산출물이 관측되지 않은 세션이 있습니다', exact: true }).waitFor();
  } catch (error) {
    await page.screenshot({ path: resolve(artifacts, 'admin-ingest-abandoned-failed.png'), fullPage: true });
    console.error('S4-2 결과 화면:', await page.locator('body').innerText());
    throw error;
  }
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-abandoned-scenario.png'), fullPage: true });
  const cacheRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S1-4', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, cache: series(run.result?.frames.cache_read_ratio), tokens: series(run.result?.frames.tokens) };
  }, scenarioFrom);
  assert.equal(cacheRun.run.status, 'succeeded');
  assert.equal(cacheRun.run.progress.step, 3);
  assert.equal(cacheRun.run.progress.total, 3);
  assert.ok(cacheRun.cache.points.some(p => p.value.state === 'value' && p.value.value === 0));
  assert.ok(cacheRun.tokens.points.some(p => p.value.state === 'value' && p.value.value === 1050));
  assert.equal(cacheRun.run.result.findings.length, 1);
  assert.equal(cacheRun.run.result.findings[0].rule_id, 'no_cache_reads');
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, cacheRun.run.run_id);
  await page.getByRole('heading', { name: '유효 토큰 요청에서 캐시 읽기가 관측되지 않았습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-cache-scenario.png'), fullPage: true });
  const promptRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S4-1', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, prompts: series(run.result?.frames.prompts_per_session), tokens: series(run.result?.frames.tokens) };
  }, scenarioFrom);
  assert.equal(promptRun.run.status, 'succeeded');
  assert.equal(promptRun.run.progress.step, 2);
  assert.equal(promptRun.run.progress.total, 2);
  assert.ok(promptRun.prompts.points.some(p => p.value.state === 'value' && p.value.value === 2));
  assert.ok(promptRun.tokens.points.some(p => p.value.state === 'value' && p.value.value === 1050));
  assert.equal(promptRun.run.result.findings.length, 1);
  assert.equal(promptRun.run.result.findings[0].rule_id, 'multiple_prompts_per_session');
  assert.equal(promptRun.run.result.findings[0].evidence.p50, 2);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, promptRun.run.run_id);
  await page.getByRole('heading', { name: '세션별 프롬프트 수 중앙값이 1을 초과했습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-prompts-scenario.png'), fullPage: true });
  const gateRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    let { run } = await scenarioApi.start('S4-8', { params: {
      from, to: new Date(Date.now() + 1000).toISOString(), wait_thresholds_min: [1, 2, 3],
    } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return run;
  }, scenarioFrom);
  assert.equal(gateRun.status, 'succeeded');
  assert.equal(gateRun.progress.step, 3);
  assert.equal(gateRun.progress.total, 3);
  assert.deepEqual(Object.keys(gateRun.result.frames).sort(), ['gate_wait_ms', 'tool_rejections', 'usage_heatmap']);
  assert.equal(gateRun.result.findings.length, 1);
  assert.equal(gateRun.result.findings[0].evidence.p90_ms, 120000);
  assert.equal(gateRun.result.findings[0].evidence.threshold_min, 1);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, gateRun.run_id);
  await page.getByRole('heading', { name: '도구 승인 대기 p90이 입력 임계값을 초과했습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-gate-scenario.png'), fullPage: true });
  const actionRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S4-6', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, calls: series(run.result?.frames.tool_calls) };
  }, scenarioFrom);
  assert.equal(actionRun.run.status, 'succeeded');
  assert.equal(actionRun.run.progress.step, 2);
  assert.equal(actionRun.run.progress.total, 2);
  assert.deepEqual(Object.keys(actionRun.run.result.frames).sort(), ['lines_of_code', 'tool_calls']);
  assert.ok(actionRun.calls.points.some(p => p.labels.action === 'other' && p.value.state === 'value' && p.value.value === 5));
  assert.equal(actionRun.run.result.findings.length, 1);
  assert.equal(actionRun.run.result.findings[0].evidence.action, 'other');
  assert.equal(actionRun.run.result.findings[0].evidence.calls, 5);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, actionRun.run.run_id);
  await page.getByRole('heading', { name: '도구 action별 호출이 관측되었습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-action-scenario.png'), fullPage: true });
  const teamUsageRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S3-4', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, sessions: series(run.result?.frames.sessions) };
  }, scenarioFrom);
  assert.equal(teamUsageRun.run.status, 'succeeded');
  assert.equal(teamUsageRun.run.progress.step, 4);
  assert.equal(teamUsageRun.run.progress.total, 4);
  assert.deepEqual(Object.keys(teamUsageRun.run.result.frames).sort(), ['active_time', 'adoption_rate', 'lines_of_code', 'sessions']);
  assert.ok(teamUsageRun.sessions.points.some(p => p.labels.team === '00000000-0000-0000-0000-000000000010' && p.value.value === 5));
  assert.equal(teamUsageRun.run.result.findings.length, 1);
  assert.equal(teamUsageRun.run.result.findings[0].evidence.sessions, 5);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, teamUsageRun.run.run_id);
  await page.getByRole('heading', { name: '팀별 세션 사용량이 관측되었습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-teamusage-scenario.png'), fullPage: true });
  const vendorRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S8-4', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, tokens: series(run.result?.frames.tokens) };
  }, scenarioFrom);
  assert.equal(vendorRun.run.status, 'succeeded');
  assert.equal(vendorRun.run.progress.step, 2);
  assert.equal(vendorRun.run.progress.total, 2);
  assert.deepEqual(Object.keys(vendorRun.run.result.frames).sort(), ['cost', 'tokens']);
  assert.ok(vendorRun.tokens.points.some(p => p.labels.product === 'claude_code' && p.labels.model === scenarioModel && p.value.value === 1050));
  assert.equal(vendorRun.run.result.findings.length, 1);
  assert.equal(vendorRun.run.result.findings[0].evidence.product, 'claude_code');
  assert.equal(vendorRun.run.result.findings[0].evidence.cost_usd, 15);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, vendorRun.run.run_id);
  await page.getByRole('heading', { name: '제품별 비용이 관측되었습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-vendor-scenario.png'), fullPage: true });
  const adoptionRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S3-1', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, users: series(run.result?.frames.active_users), calls: series(run.result?.frames.tool_calls) };
  }, scenarioFrom);
  assert.equal(adoptionRun.run.status, 'succeeded');
  assert.equal(adoptionRun.run.progress.step, 5);
  assert.equal(adoptionRun.run.progress.total, 5);
  assert.deepEqual(Object.keys(adoptionRun.run.result.frames).sort(), ['active_users', 'adoption_rate', 'mcp_connections', 'prompts_per_session', 'tool_calls']);
  assert.ok(adoptionRun.users.points.some(p => p.labels.team === '00000000-0000-0000-0000-000000000010' && p.value.value === 5));
  assert.ok(adoptionRun.calls.points.some(p => p.value.value === 5));
  assert.equal(adoptionRun.run.result.findings.length, 1);
  assert.ok(Math.abs(adoptionRun.run.result.findings[0].evidence.adoption_rate - 5 / 6) < 1e-10);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, adoptionRun.run.run_id);
  await page.getByRole('heading', { name: '팀별 채택률이 관측되었습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-adoption-scenario.png'), fullPage: true });
  const advancedRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S3-5', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, failures: series(run.result?.frames.tool_failure_rate) };
  }, scenarioFrom);
  assert.equal(advancedRun.run.status, 'succeeded');
  assert.equal(advancedRun.run.progress.step, 4);
  assert.equal(advancedRun.run.progress.total, 4);
  assert.deepEqual(Object.keys(advancedRun.run.result.frames).sort(), ['command_prompt_ratio', 'mcp_connections', 'subagent_cost_ratio', 'tool_failure_rate']);
  assert.ok(advancedRun.failures.points.some(p => p.value.value === 0.2));
  assert.equal(advancedRun.run.result.findings.length, 1);
  assert.equal(advancedRun.run.result.findings[0].rule_id, 'observed_subagent_cost');
  assert.equal(advancedRun.run.result.findings[0].evidence.ratio, 1);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, advancedRun.run.run_id);
  await page.getByRole('heading', { name: '서브에이전트 비용이 관측되었습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-advanced-scenario.png'), fullPage: true });
  const hourlyRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S2-1', { tz: 'Asia/Seoul', params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, heatmap: series(run.result?.frames.usage_heatmap) };
  }, scenarioFrom);
  assert.equal(hourlyRun.run.status, 'succeeded');
  assert.equal(hourlyRun.run.progress.step, 3);
  assert.equal(hourlyRun.run.progress.total, 3);
  assert.deepEqual(Object.keys(hourlyRun.run.result.frames).sort(), ['rate_limit_events', 'session_last_event', 'usage_heatmap']);
  const hourlyPoints = hourlyRun.heatmap.points.filter(p => p.value.state === 'value');
  assert.equal(hourlyPoints.reduce((sum, p) => sum + p.value.value, 0), 10);
  assert.ok(hourlyPoints.every(p => Number(p.labels.hour) >= 0 && Number(p.labels.hour) < 24 && Number(p.labels.weekday) >= 1 && Number(p.labels.weekday) <= 7));
  assert.equal(hourlyRun.run.result.findings.length, 0);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, hourlyRun.run.run_id);
  await page.locator('aside[aria-label="시나리오 판정"]').getByText(hourlyRun.run.run_id.slice(0, 8), { exact: false }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-hourly-scenario.png'), fullPage: true });
  const reportingRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    const { series } = await import('/src/widgets/model.ts');
    let { run } = await scenarioApi.start('S8-1', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return { run, tokens: series(run.result?.frames.tokens), unitCost: series(run.result?.frames.cost_per_active_user) };
  }, scenarioFrom);
  assert.equal(reportingRun.run.status, 'succeeded');
  assert.equal(reportingRun.run.progress.step, 7);
  assert.equal(reportingRun.run.progress.total, 7);
  assert.deepEqual(Object.keys(reportingRun.run.result.frames).sort(), ['active_time', 'commits', 'cost_per_active_user', 'lines_of_code', 'pull_requests', 'sessions', 'tokens']);
  assert.ok(reportingRun.tokens.points.some(p => p.value.value === 1050));
  assert.ok(reportingRun.unitCost.points.some(p => p.unit === 'USD' && p.value.state === 'value' && p.value.value === 3));
  assert.equal(reportingRun.run.result.findings.length, 1);
  assert.equal(reportingRun.run.result.findings[0].evidence.count, 5);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, reportingRun.run.run_id);
  await page.getByRole('heading', { name: '경영 보고용 세션 사용량이 관측되었습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-reporting-scenario.png'), fullPage: true });
  const concentrationRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    let { run } = await scenarioApi.start('S3-2', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return run;
  }, scenarioFrom);
  assert.equal(concentrationRun.status, 'succeeded');
  assert.equal(concentrationRun.progress.step, 3);
  assert.equal(concentrationRun.progress.total, 3);
  assert.deepEqual(Object.keys(concentrationRun.result.frames).sort(), ['tokens', 'tool_calls', 'usage_concentration']);
  assert.equal(concentrationRun.result.findings.length, 1);
  assert.equal(concentrationRun.result.findings[0].evidence.top_decile_share, 0.2);
  const curve = concentrationRun.result.frames.usage_concentration.frames.find(f => f.schema.fields[0].name === 'population_share');
  assert.deepEqual(curve.data.values[0], [0, 0.2, 0.4, 0.6, 0.8, 1]);
  assert.deepEqual(curve.data.values[2], [0, 0.2, 0.4, 0.6, 0.8, 1]);
  for (const installation of installations) assert.ok(!JSON.stringify(concentrationRun.result).includes(installation));
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, concentrationRun.run_id);
  await page.getByRole('heading', { name: '익명 사용량 집중도가 관측되었습니다', exact: true }).waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-concentration-scenario.png'), fullPage: true });
  const effortRun = await page.evaluate(async from => {
    const { scenarioApi, activeRun } = await import('/src/api/scenarios.ts');
    let { run } = await scenarioApi.start('S1-6', { params: { from, to: new Date(Date.now() + 1000).toISOString() } });
    const deadline = Date.now() + 60000;
    while (activeRun(run) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      run = (await scenarioApi.get(run.run_id)).run;
    }
    return run;
  }, scenarioFrom);
  assert.equal(effortRun.status, 'succeeded');
  assert.equal(effortRun.progress.step, 4);
  assert.equal(effortRun.progress.total, 4);
  assert.deepEqual(Object.keys(effortRun.result.frames).sort(), ['cost', 'sessions', 'tokens']);
  assert.equal(effortRun.result.findings.length, 2);
  for (const finding of effortRun.result.findings) {
    assert.equal(finding.evidence.model, model);
    assert.equal(finding.evidence.cost_usd, 15);
  }
  assert.deepEqual(effortRun.result.frames.cost.frames.map(f => f.schema.fields[0].labels),
    [{ model, effort: 'high' }, { model, speed: 'fast' }]);
  await page.evaluate(id => {
    window.history.pushState(null, '', `/runs/${id}`);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, effortRun.run_id);
  await page.getByRole('heading', { name: '모델·effort·speed별 비용이 관측되었습니다', exact: true }).first().waitFor();
  await page.screenshot({ path: resolve(artifacts, 'admin-ingest-effort-scenario.png'), fullPage: true });
  return { signals: ['logs', 'metrics', 'traces'], actualFrontendClient: true, admin: true,
    authenticatedIdentity: true, asOfTeam: true, maskedAtFour: true, duplicatePushes: 30, storedRows: 45,
    effortScenario: { id: 'S1-6', runId: effortRun.run_id, actualResultUI: true, costUsd: 15, effort: 'high', speed: 'fast', source: 'metrics' },
    concentrationScenario: { id: 'S3-2', runId: concentrationRun.run_id, actualResultUI: true, topDecileShare: 0.2, anonymousCurve: true },
    reportingScenario: { id: 'S8-1', runId: reportingRun.run.run_id, actualResultUI: true, sessions: 5, tokens: 1050, costPerActiveUserUsd: 3 },
    hourlyScenario: { id: 'S2-1', runId: hourlyRun.run.run_id, actualResultUI: true, prompts: 10, zone: 'Asia/Seoul', rateLimitFindings: 0 },
    advancedScenario: { id: 'S3-5', runId: advancedRun.run.run_id, actualResultUI: true, subagentCostRatio: 1, toolFailureRate: 0.2 },
    adoptionScenario: { id: 'S3-1', runId: adoptionRun.run.run_id, actualResultUI: true, activeUsers: 5, adoptionRate: 5 / 6, toolCalls: 5 },
    vendorScenario: { id: 'S8-4', runId: vendorRun.run.run_id, actualResultUI: true, product: 'claude_code', costUsd: 15, tokens: 1050 },
    teamUsageScenario: { id: 'S3-4', runId: teamUsageRun.run.run_id, actualResultUI: true, sessions: 5 },
    actionScenario: { id: 'S4-6', runId: actionRun.run.run_id, actualResultUI: true, action: 'other', calls: 5 },
    gateScenario: { id: 'S4-8', runId: gateRun.run_id, actualResultUI: true, p90Ms: 120000, thresholdMin: 1 },
    promptScenario: { id: 'S4-1', runId: promptRun.run.run_id, actualResultUI: true, p50: 2, tokens: 1050 },
    cacheScenario: { id: 'S1-4', runId: cacheRun.run.run_id, actualResultUI: true, cacheRatio: 0, tokens: 1050 },
    abandonedScenario: { id: 'S4-2', runId: abandonedRun.run.run_id, actualResultUI: true, ratio: 1 },
    budgetScenario: { id: 'S1-1', actualResultUI: true, costUsd: 15, tokens: 1050, runs: budgetEvidence },
    agentScenario: { id: 'S7-1', runId: agentRun.run.run_id, actualResultUI: true, failureRate: 0.2, calls: 5, costUsd: 15 },
    contextScenario: { id: 'S1-5', runId: contextRun.run.run_id, actualResultUI: true, ratio: 20, threshold: 10 },
    scenario: { id: 'S1-3', runId: scenario.run.run_id, actualResultUI: true, costUsd: 15, retryRatio: 0.2 },
    toolCalls: 5, deltaCostUsd: 15, cumulativeCostExcluded: true, ttftMs: quantiles };
}
