const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

// 测试实际 SSE 解析和新主智能体客户端，不依赖真实模型。
const cache = new Map();
const requests = [];
const http = { defaults: { baseURL: '/api' }, post: async (...args) => requests.push(args) };
function loadSource(filename) {
  if (cache.has(filename)) return cache.get(filename).exports;
  const module = { exports: {} };
  cache.set(filename, module);
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const sourceRequire = name => {
    if (name === '@/api/http') return { http };
    if (name.startsWith('@/')) return loadSource(path.resolve(__dirname, '../src', `${name.slice(2)}.ts`));
    if (name.startsWith('.')) return loadSource(path.resolve(path.dirname(filename), `${name}.ts`));
    return require(name);
  };
  new Function('require', 'module', 'exports', code)(sourceRequire, module, module.exports);
  return module.exports;
}
const { createSseParser } = loadSource(path.resolve(__dirname, '../src/modules/ai/stream/sse.ts'));
const { startMainAgentStream, submitMainAgentAnswer } = loadSource(path.resolve(__dirname, '../src/modules/ai/stream/main-agent-stream.ts'));
const { normalizeMessage, normalizeHistoryMessage, clarificationAnswers } = loadSource(path.resolve(__dirname, '../src/modules/ai/contracts/message-contract.ts'));
const storage = new Map([['erp_auth_token', 'test-token'], ['erp_auth_token_name', 'satoken']]);
global.localStorage = { getItem: key => storage.get(key) ?? null };
const event = (name, data) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

async function main() {
  const history = result => normalizeMessage({
    messageId: '1', requestId: 'r', seq: '1', role: 'tool', kind: 'TOOL_RESULT', createdAt: '',
    content: { type: 'TOOL_RESULT', data: { callId: 'c1', result: JSON.stringify(result) } },
  }).content.data.text;
  assert.equal(history({ answers: [{ questionId: 'warehouse', selectedOptions: ['一号仓'], customText: '冷库' }], note: '只看上个月' }), '一号仓、冷库；只看上个月');
  assert.equal(history({ answers: [], note: '只看上个月' }), '只看上个月');
  assert.equal(history({ answers: [] }), '已补充信息');
  const entry = (id, kind, data) => normalizeMessage({ messageId: id, requestId: 'r', seq: id, role: kind === 'TOOL_CALL' ? 'assistant' : 'tool', kind, createdAt: '', content: { type: kind, data } });
  const question = id => entry(id, 'TOOL_CALL', { callId: `c${id}`, arguments: JSON.stringify({ questions: [{ id: 'q', question: '范围？', type: 'TEXT', options: [] }] }) });
  const result = (id, callId, note) => entry(id, 'TOOL_RESULT', { callId, result: JSON.stringify({ answers: [], note }) });
  assert.equal(result('4', 'c2', '第二次').role, 'assistant', '追问结果不能归入用户消息');
  const first = question('1'), second = question('2'), unmatched = question('3');
  const paired = clarificationAnswers([result('4', 'c2', '第二次'), first, result('5', 'c1', '第一次'), second, unmatched]);
  assert.equal(paired.get(first.content.data.callId), '第一次');
  assert.equal(paired.get(second.content.data.callId), '第二次');
  assert.equal(paired.get(unmatched.content.data.callId), undefined);
  const invalidCall = { messageId: '6', requestId: 'r', seq: '6', role: 'assistant', kind: 'TOOL_CALL', createdAt: '', content: { type: 'TOOL_CALL', data: { callId: 'bad', arguments: '{' } } };
  const invalidResult = { ...invalidCall, messageId: '7', seq: '7', role: 'tool', kind: 'TOOL_RESULT', content: { type: 'TOOL_RESULT', data: { callId: 'bad', result: '追问格式错误，请重试' } } };
  assert.equal(normalizeHistoryMessage(invalidCall), null);
  assert.equal(normalizeHistoryMessage(invalidResult), null);
  assert.equal(normalizeHistoryMessage({ ...invalidCall, content: { type: 'TOOL_CALL', data: { callId: 'bad', arguments: '{"questions":[]}' } } }), null);
  assert.equal(normalizeHistoryMessage({ ...invalidCall, content: { type: 'TOOL_CALL', data: { callId: 'good', arguments: JSON.stringify({ questions: [{ id: 'q', question: '范围？', type: 'TEXT', options: [] }] }) } } }).content.data.callId, 'good');
  const mixed = [invalidCall, invalidResult, { ...invalidCall, messageId: '8', seq: '8', content: { type: 'TOOL_CALL', data: { callId: 'good', arguments: JSON.stringify({ questions: [{ id: 'q', question: '范围？', type: 'TEXT', options: [] }] }) } } }].map(normalizeHistoryMessage).filter(Boolean);
  assert.equal(mixed.length, 1);
  assert.equal(mixed[0].content.data.callId, 'good');
  const frames = [];
  const parser = createSseParser(frame => frames.push(frame));
  for (const chunk of [':心跳\r', '\n\r', '\nevent: text_delta\r\nda', 'ta: {"delta":\r\ndata: "中"}\r', '\n\r\n']) parser.push(chunk);
  assert.deepEqual(frames, [{ event: 'text_delta', data: '{"delta":\n"中"}' }]);
  let sent;
  global.fetch = async (url, config) => {
    sent = { url, config };
    return new Response(event('run_started', { runId: 'r', conversationId: '123' }) +
      event('text_delta', { messageId: 'm', delta: '查询中' }) +
      event('text_completed', { messageId: 'm', text: '查询中，请稍候。' }) +
      event('tool_started', { runId: 'r', toolCallId: 'c', tool: 'query_sql_data', label: '正在查询业务数据' }) +
      event('tool_finished', { runId: 'r', toolCallId: 'c', status: 'returned', durationMs: 2 }) +
      event('run_completed', { runId: 'r', status: 'COMPLETED' }),
    { headers: { 'content-type': 'text/event-stream' } });
  };
  const seen = [];
  await startMainAgentStream('123', '库存', item => seen.push(item));
  assert.equal(sent.url, '/api/ai/assistant/conversations/123/messages/stream');
  assert.equal(sent.config.headers.get('satoken'), 'test-token');
  assert.deepEqual(JSON.parse(sent.config.body), { content: '库存' });
  assert.deepEqual(seen.map(item => item.event), ['run_started', 'text_delta', 'text_completed', 'tool_started', 'tool_finished', 'run_completed']);
  await submitMainAgentAnswer('123', 'q', [{ questionId: 'w', selectedOptions: [], customText: '一号仓' }], '');
  assert.equal(requests[0][0], '/ai/assistant/conversations/123/questions/q/answer');
  assert.equal(requests[0][1].answers[0].customText, '一号仓');
  global.fetch = async () => new Response(event('run_started', { runId: 'r' }), { headers: { 'content-type': 'text/event-stream' } });
  await assert.rejects(startMainAgentStream('123', '库存', () => {}), /连接已中断/);
  console.log('AI_MAINAGENT_SSE_OK');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
