import { createHmac, randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

// 수집 대상 행은 SQL로 넣지 않는다. 인증용 계정은 호출자가 만든 격리 fixture를 사용한다.
export async function verifyDashboardIngest({ page, launch, waitFor, sql, backend, work, port, chPort, installations, tenant }) {
  const secret = randomUUID();
  const endpoint = 'http://127.0.0.1:14316';
  launch('java', ['-jar', resolve(backend, 'apps/telemetry-ingest/build/libs/telemetry-ingest-0.0.1-SNAPSHOT.jar')], {
    PULSEMETRY_INGEST_PORT: '14316', PULSEMETRY_DB_URL: `jdbc:postgresql://127.0.0.1:${port}/pulsemetry`,
    PULSEMETRY_DB_USERNAME: 'pulsemetry', PULSEMETRY_DB_PASSWORD: 'e2e-only-password',
    PULSEMETRY_TOKEN_HASH_SECRET: secret, PULSEMETRY_CLICKHOUSE_URL: `http://127.0.0.1:${chPort}`,
    PULSEMETRY_ARCHIVE_DIR: resolve(work, 'archive'),
  }, backend, 'ingest');
  await waitFor(async () => (await fetch(`${endpoint}/v1/healthz`)).ok);
  const tool = `ingest-e2e-${randomUUID()}`;
  const attr = (key, value) => ({ key, value: { stringValue: value } });
  const query = () => page.evaluate(async tool => {
    const client = await import('/src/api/client.ts');
    const { series } = await import('/src/widgets/model.ts');
    const response = await client.request('/query', { method: 'POST', body: JSON.stringify({
      from: 'now-1d', to: 'now', compare: 'none', queries: [
        { ref_id: 'A', metric_id: 'tool_calls', frame_type: 'scalar', group_by: ['team', 'tool_name'] },
      ],
    }) });
    if (response.results.A.status !== 200) throw new Error(JSON.stringify(response.results.A));
    return series(response.results.A).points.filter(p => p.labels.tool_name === tool);
  }, tool);
  for (const [index, installation] of installations.entries()) {
    const token = `ptt_${randomUUID()}`;
    const hash = createHmac('sha256', secret).update(token).digest('hex');
    sql(`INSERT INTO enrollment.telemetry_tokens(installation_id,token_hash) VALUES ('${installation}','${hash}');`);
    const body = JSON.stringify({ resourceLogs: [{ resource: { attributes: [
      attr('service.name', 'claude-code'), attr('tenant.id', randomUUID()),
      attr('developer.installation_id', randomUUID()),
    ] }, scopeLogs: [{ logRecords: [{ timeUnixNano: String(BigInt(Date.now()) * 1000000n),
      body: { stringValue: 'claude_code.tool_result' }, attributes: [
        attr('session.id', `ingest-${installation}`), attr('tool_use_id', randomUUID()),
        attr('tool_name', tool), { key: 'success', value: { boolValue: true } },
      ],
    }] }] }] });
    const push = token => fetch(`${endpoint}/v1/logs`, { method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body });
    if (index === 0) assert.equal((await push('invalid')).status, 401);
    // 같은 바이트를 두 번 전송해 ReplacingMergeTree FINAL 조회까지 검증한다.
    for (let retry = 0; retry < 2; retry++) {
      const response = await push(token);
      assert.equal(response.status, 200, await response.text());
    }
    if (index === 3) {
      const points = await query();
      assert.equal(points.length, 1);
      assert.equal(points[0].value.state, 'masked');
      assert.equal(points[0].value.value, null);
    }
  }
  const points = await query();
  assert.equal(points.length, 1);
  assert.equal(points[0].labels.team, '00000000-0000-0000-0000-000000000010');
  assert.deepEqual(points[0].value, { state: 'value', value: 5 });
  const response = await fetch(`http://127.0.0.1:${chPort}/?query=${encodeURIComponent(
    `SELECT tenant_id, installation_id FROM enriched_events FINAL WHERE JSONExtractString(raw_json, 'payload', 'tool_name') = '${tool}' FORMAT JSONEachRow`)}`);
  assert.equal(response.status, 200);
  const rows = (await response.text()).trim().split('\n').map(JSON.parse);
  assert.equal(rows.length, 5);
  assert.ok(rows.every(row => row.tenant_id === tenant));
  assert.deepEqual(rows.map(row => row.installation_id).sort(), [...installations].sort());
  return { signal: 'logs', metric: 'tool_calls', actualFrontendClient: true, admin: true,
    authenticatedIdentity: true, asOfTeam: true, maskedAtFour: true, countAtFive: 5, duplicatePushes: 10 };
}
