const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

// 直接加载实际 TypeScript 源码，避免用复制的解析逻辑验证自己，也不生成构建文件。
const cache = new Map();
const http = { defaults: { baseURL: '/api' } };
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
const { createSseParser, readSseStream } = loadSource(path.resolve(__dirname, '../src/modules/ai/sse.ts'));
const { sendAiAssistantMessage } = loadSource(path.resolve(__dirname, '../src/modules/ai/api.ts'));
const storage = new Map([['erp_auth_token', 'test-token'], ['erp_auth_token_name', 'x-erp-session']]);
global.localStorage = { getItem: key => storage.get(key) ?? null, removeItem: key => storage.delete(key) };
global.window = { location: { href: '' } };
const originalFetch = global.fetch;
const request = { conversationId: '1960000000000000001', message: ' 测试流式响应 ' };
const baseEvent = { requestId: 'req-test', conversationId: request.conversationId };
const message = { messageId: '1963000000000000002', role: 'assistant', content: '完整最终回复', createdAt: '2026-10-09 01:00:00', charts: [], sources: [], actionCards: [], agentTraces: [], taskCard: null, workbench: null };
const event = (eventType, fields) => `event: ${eventType}\r\ndata: ${JSON.stringify({ ...baseEvent, eventType, ...fields })}\r\n\r\n`;
const delta = event('MESSAGE_DELTA', { contentDelta: '中文增量' });
const final = event('MESSAGE_FINAL', { message });
function responseFor(text, byteByByte = false) {
  const bytes = new TextEncoder().encode(text);
  const body = new ReadableStream({ start(controller) {
    if (byteByByte) for (const byte of bytes) controller.enqueue(Uint8Array.of(byte));
    else controller.enqueue(bytes);
    controller.close();
  } });
  return new Response(body, { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } });
}

async function main() {
  const frames = [];
  const parser = createSseParser(frame => frames.push(frame));
  for (const chunk of [':心跳\r', '\n\r', '\nevent: MESSAGE_DELTA\r\nda', 'ta: {"a":\r\ndata: "中"}\r', '\n\r\n', 'data:\n\n']) parser.push(chunk);
  assert.deepEqual(frames, [{ event: 'MESSAGE_DELTA', data: '{"a":\n"中"}' }]);
  const decoded = [];
  await readSseStream(responseFor(delta, true).body, frame => decoded.push(JSON.parse(frame.data).contentDelta));
  assert.deepEqual(decoded, ['中文增量']);

  let sent;
  global.fetch = async (url, config) => { sent = { url, config }; return responseFor(':ping\n\n' + delta + final, true); };
  const deltas = [];
  const completed = await sendAiAssistantMessage(request, text => deltas.push(text));
  assert.equal(sent.url, '/api/ai/assistant/messages/stream');
  assert.equal(sent.config.method, 'POST');
  assert.equal(sent.config.headers.get('x-erp-session'), 'test-token');
  assert.equal(sent.config.headers.get('Accept'), 'text/event-stream');
  assert.equal(JSON.parse(sent.config.body).message, '测试流式响应');
  assert.deepEqual(deltas, ['中文增量']);
  assert.deepEqual(completed.message, message);

  for (const [text, expected] of [
    [delta, /未收到完整回复/],
    [event('ERROR', { errorMessage: 'AI 服务暂不可用' }), /AI 服务暂不可用/],
    ['data: {非法JSON}\n\n', /JSON 无效/],
    [event('MESSAGE_DELTA', { conversationId: '2', contentDelta: '错误会话' }), /其他会话/],
    [delta + event('MESSAGE_FINAL', { requestId: 'req-other', message }), /请求标识不一致/],
    ['event: ERROR\ndata: ' + JSON.stringify({ ...baseEvent, eventType: 'MESSAGE_FINAL', message }) + '\n\n', /事件名称不一致/],
    [event('MESSAGE_FINAL', { message: { ...message, messageId: 1963000000000000002 } }), /必须使用非空字符串/],
    [final.slice(0, -2), /未收到完整回复/],
  ]) {
    global.fetch = async () => responseFor(text);
    await assert.rejects(sendAiAssistantMessage(request, () => {}), expected);
  }
  global.fetch = async () => new Response(JSON.stringify({ code: 503, message: '模型未配置' }), { status: 503 });
  await assert.rejects(sendAiAssistantMessage(request, () => {}), /模型未配置/);
  global.fetch = async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } });
  await assert.rejects(sendAiAssistantMessage(request, () => {}), /未返回约定的 SSE/);

  const controller = new AbortController();
  let cancelled = false;
  const pendingBody = new ReadableStream({ cancel() { cancelled = true; } });
  const reading = readSseStream(pendingBody, () => {}, controller.signal);
  controller.abort();
  await assert.rejects(reading, error => error.name === 'AbortError');
  assert.equal(cancelled, true);

  http.defaults.baseURL = 'http://example.test/custom/';
  storage.set('erp_auth_token_name', 'custom-token');
  global.fetch = async (url, config) => { sent = { url, config }; return responseFor(final); };
  await sendAiAssistantMessage(request, () => {});
  assert.equal(sent.url, 'http://example.test/custom/ai/assistant/messages/stream');
  assert.equal(sent.config.headers.get('custom-token'), 'test-token');

  global.fetch = async () => { storage.set('erp_auth_token', 'new-session'); return new Response('{}', { status: 401 }); };
  await assert.rejects(sendAiAssistantMessage(request, () => {}));
  assert.equal(storage.get('erp_auth_token'), 'new-session');
  assert.equal(window.location.href, '');
  global.fetch = async () => new Response('{}', { status: 401 });
  await assert.rejects(sendAiAssistantMessage(request, () => {}));
  assert.equal(storage.has('erp_auth_token'), false);
  assert.equal(window.location.href, '/login');
  console.log('AI_SSE_OK: 实际解析器和 API 已验证 UTF-8/CRLF/多行/跨分块/心跳、最终消息、错误/截断、取消、动态认证、地址与旧 401 保护');
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => { global.fetch = originalFetch; });
