const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

// 运行独立测试启动类；真实 HTTP、MySQL、Redis 与生产节点，模型为受控响应。
const base = process.env.AI_GRAPH_BACKEND_URL || 'http://127.0.0.1:18082';
const output = process.env.AI_GRAPH_REAL_RESULT || path.resolve(__dirname, '../../_logs/ai-graph-real-integration.json');
let authHeaders = {};
const checks = [];
const conversations = [];
/** 终态事件集合：暂停轮以澄清事件收尾，其余以完成或停止事件收尾。 */
const TERMINALS = ['run_finished', 'run_stopped', 'clarification_required'];

async function api(method, endpoint, body, authenticated = true) {
  const response = await fetch(base + endpoint, {
    method,
    headers: { 'Content-Type': 'application/json', ...(authenticated ? authHeaders : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}

async function success(method, endpoint, body) {
  const response = await api(method, endpoint, body);
  assert.equal(response.status, 200, endpoint);
  assert.equal(response.body.code, 0, response.body.message);
  return response.body.data;
}

async function create() {
  const conversation = await success('POST', '/ai/assistant/conversations');
  assert.equal(typeof conversation.conversationId, 'string');
  conversations.push(conversation.conversationId);
  return conversation.conversationId;
}

function parseFrames(text) {
  return text.split(/\r?\n\r?\n/).flatMap(frame => {
    const name = frame.match(/^event:\s*(.+)$/m)?.[1]?.trim();
    const data = [...frame.matchAll(/^data:\s?(.*)$/gm)].map(item => item[1]).join('\n');
    return name && data ? [{ event: name, data: JSON.parse(data) }] : [];
  });
}

async function stream(endpoint, payload) {
  const response = await fetch(base + endpoint, {
    method: 'POST',
    headers: { ...authHeaders, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(payload),
  });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /text\/event-stream/);
  const events = parseFrames(await response.text());
  const terminals = events.filter(item => TERMINALS.includes(item.event));
  assert.equal(terminals.length, 1, '每次执行只能有一个终结事件');
  const find = (name) => events.find(item => item.event === name);
  return {
    events,
    terminal: terminals[0],
    finished: find('run_finished'),
    stopped: find('run_stopped'),
    clarification: find('clarification_required'),
  };
}

async function history(id) {
  const page = await success('GET', `/ai/assistant/conversations/${id}/messages?pageNum=1&pageSize=100`);
  return { ...page, total: Number(page.total) };
}

async function waitUntil(read, predicate, timeout = 15000) {
  const deadline = Date.now() + timeout;
  do {
    const value = await read();
    if (predicate(value)) {
      return value;
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  throw new Error('等待后台终态超时');
}

function databaseCount(table, id, extra = '') {
  assert.match(id, /^\d+$/);
  assert.ok(['ai_node_memory', 'ai_node_working_memory', 'ai_message'].includes(table));
  return Number(execFileSync('docker', ['exec', 'erp-ai-it-20261016', 'mysql', '--user=root',
    '--password=ai-integration-only', '--batch', '--skip-column-names', 'erp', '-e',
    `SELECT COUNT(*) FROM ${table} WHERE conversation_id=${id}${extra}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim());
}

async function main() {
  const unauthorized = await api('GET', '/ai/assistant/overview', undefined, false);
  assert.equal(unauthorized.status, 401);
  checks.push('真实认证拒绝未登录请求');
  const login = await api('POST', '/auth/login', { username: 'graph-test', password: 'graph-test' }, false);
  assert.equal(login.body.code, 0);
  authHeaders = { [login.body.data.tokenName]: login.body.data.token };

  const id = await create();
  // 暂停轮：updates 以澄清事件收尾，载荷只有暂停点与展示内容，无 result 副本
  const initial = await stream('/ai/graph/stream', { conversationId: id, query: '分析库存情况，给出补货建议' });
  assert.equal(initial.terminal.event, 'clarification_required');
  const firstClarification = initial.clarification.data;
  assert.equal(firstClarification.type, 'CLARIFICATION');
  assert.ok(firstClarification.content.data.pauseId, '澄清内容必须携带恢复暂停点 pauseId');
  assert.ok(firstClarification.checkpointId, '事件顶层检查点仅作诊断，仍由服务端补齐');
  assert.equal(firstClarification.content.type, 'CLARIFICATION');
  assert.equal(firstClarification.content.data.plan.needs_clarification, true);
  assert.ok(firstClarification.content.data.questions.length > 0);
  assert.equal(firstClarification.content.data.checkpointId, undefined,
    '可见内容不再保存检查点，恢复锚点改用 pauseId');
  const firstThread = firstClarification.content.data.threadId;
  assert.ok(firstThread, '澄清内容应携带恢复所需线程标识');
  assert.equal(initial.finished, undefined, '暂停轮不再推送 run_finished');
  const firstPage = await history(id);
  assert.equal(firstPage.total, 2);
  const firstMessage = firstPage.records.at(-1);
  assert.equal(firstMessage.content.type, 'CLARIFICATION');
  assert.equal(firstMessage.content.data.pauseId, firstClarification.content.data.pauseId,
    '事件暂停点必须与落库澄清卡一致，前端恢复以库为准');
  assert.equal((await success('GET', `/ai/assistant/conversations/${id}`)).runStatus, 'WAITING_HUMAN');
  checks.push('澄清事件薄载荷与真实MySQL落库卡片暂停点一致');

  const second = await stream('/ai/graph/resume/stream', {
    threadId: firstThread, pauseId: firstClarification.content.data.pauseId,
    answer: '本次使用哪个统计周期？\n最近30天\n\n需要分析哪些仓库或商品范围？\n一号仓库，重点关注办公纸品',
  });
  assert.equal(second.terminal.event, 'clarification_required');
  const secondClarification = second.clarification.data;
  assert.ok(secondClarification.content.data.pauseId);
  assert.notEqual(secondClarification.content.data.pauseId, firstClarification.content.data.pauseId);
  const afterSecond = await history(id);
  assert.equal(afterSecond.total, 4);
  assert.equal(afterSecond.records.at(-1).content.data.pauseId, secondClarification.content.data.pauseId,
    '第二轮事件暂停点必须与落库新卡一致');
  assert.equal(afterSecond.records[2].content.data.replyToMessageId, firstMessage.messageId);
  assert.deepEqual(afterSecond.records[1].content, firstMessage.content, '旧问题原文不可被改写');
  const obsolete = await stream('/ai/graph/resume/stream', {
    threadId: firstThread, pauseId: firstClarification.content.data.pauseId, answer: '重复回答',
  });
  assert.equal(obsolete.stopped.data.status, 'FAILED');
  assert.match(obsolete.stopped.data.reason, /过期/, '过期回答应收到 run_stopped 终态');
  assert.equal((await history(id)).total, 4, '过期回答不得写入用户流水');
  checks.push('连续Human、回答关联原问题、过期暂停点以run_stopped拒绝且不新增流水');

  const final = await stream('/ai/graph/resume/stream', {
    threadId: firstThread, pauseId: secondClarification.content.data.pauseId,
    answer: '优先避免缺货，预算控制在两万元以内',
  });
  assert.equal(final.terminal.event, 'run_finished');
  assert.equal(final.finished.data.status, 'PLAN_READY');
  assert.equal(final.finished.data.plan, undefined, '终态薄载荷不再复制计划正文');
  // 发布拍推送 artifact：在 run_finished 之前到达，与落库计划同构且 PLAN 内容不携带 checkpointId
  const artifactFrame = final.events.find(item => item.event === 'artifact');
  assert.ok(artifactFrame, 'PLAN_READY 必须推送 artifact 帧');
  assert.equal(artifactFrame.data.type, 'PLAN');
  assert.ok(artifactFrame.data.checkpointId, 'artifact 顶层检查点仅作诊断，由服务端补齐');
  assert.equal(artifactFrame.data.content.type, 'PLAN');
  assert.equal(artifactFrame.data.content.data.checkpointId, undefined,
    'PLAN 展示内容不再保存 checkpointId');
  assert.ok(final.events.indexOf(artifactFrame) < final.events.indexOf(final.finished),
    'artifact 必须先于 run_finished 到达');
  const saved = await history(id);
  assert.equal(saved.total, 6);
  assert.deepEqual(saved.records.at(-1).content.data.plan, artifactFrame.data.content.data.plan,
    '落库计划必须与推送的 artifact 内容一致');
  assert.equal((await success('GET', `/ai/assistant/conversations/${id}`)).running, false);
  assert.ok(databaseCount('ai_node_memory', id) > 0);
  assert.ok(databaseCount('ai_node_working_memory', id) > 0);
  checks.push('终态薄载荷、artifact先于run_finished、独立流水回读与节点记忆真实保存');

  const repairId = await create();
  const repair = await stream('/ai/graph/stream', {
    conversationId: repairId, query: '统计一号仓库最近30天各商品销量，触发格式修复',
  });
  assert.equal(repair.finished.data.status, 'PLAN_READY');
  assert.equal((await history(repairId)).total, 2);
  assert.ok(databaseCount('ai_node_memory', repairId,
    " AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.raw_output'))='{\"title\": }'") > 0);
  checks.push('坏JSON原文通过JSON_VALID落节点记忆，格式修复过程不进用户流水');

  const failureId = await create();
  const failure = await stream('/ai/graph/stream', { conversationId: failureId, query: '触发模型异常' });
  assert.equal(failure.terminal.event, 'run_stopped');
  assert.equal(failure.stopped.data.status, 'FAILED');
  assert.ok(failure.stopped.data.reason, '失败终态必须给出可展示说明');
  const failedDetail = await success('GET', `/ai/assistant/conversations/${failureId}`);
  assert.equal(failedDetail.running, false);
  assert.equal(failedDetail.runStatus, 'FAILED');
  assert.equal((await history(failureId)).records.at(-1).content.type, 'ERROR');
  checks.push('模型异常真实FAILED收尾，重新读取不假执行中');

  const exhaustedId = await create();
  const exhausted = await stream('/ai/graph/stream', { conversationId: exhaustedId, query: '触发格式耗尽' });
  assert.equal(exhausted.terminal.event, 'run_stopped');
  assert.equal(exhausted.stopped.data.status, 'FORMAT_RETRY_EXHAUSTED');
  assert.ok(exhausted.stopped.data.reason == null, '格式耗尽不携带失败说明，正文由流水提供');
  assert.equal((await history(exhaustedId)).records.at(-1).content.type, 'ERROR');
  checks.push('格式重试耗尽只保存友好错误，不暴露内部解析信息');

  const busyId = await create();
  const running = stream('/ai/graph/stream', {
    conversationId: busyId, query: '统计一号仓库最近30天各商品销量，慢任务',
  });
  await waitUntil(() => success('GET', `/ai/assistant/conversations/${busyId}`), value => value.running);
  // 同步入口已下线：同会话重复发起应通过第二个 stream 的 SSE run_stopped 事件被锁拒绝
  const duplicateResponse = await fetch(base + '/ai/graph/stream', {
    method: 'POST',
    headers: { ...authHeaders, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ conversationId: busyId, query: '不应启动第二次' }),
  });
  const duplicateEvents = parseFrames(await duplicateResponse.text());
  const duplicateTerminal = duplicateEvents.filter(item => TERMINALS.includes(item.event));
  assert.equal(duplicateTerminal.length, 1, '重复启动只能有一个终结事件');
  assert.equal(duplicateTerminal[0].event, 'run_stopped', '重复启动需以 SSE run_stopped 拒绝');
  assert.equal(duplicateTerminal[0].data.status, 'FAILED');
  assert.ok(duplicateTerminal[0].data.reason, '重复启动拒绝必须给出可展示说明');
  const deleting = await api('DELETE', `/ai/assistant/conversations/${busyId}`);
  assert.notEqual(deleting.body.code, 0);
  assert.equal((await running).finished.data.status, 'PLAN_READY');
  assert.equal((await history(busyId)).total, 2);
  checks.push('Redisson真实互斥：同会话重复启动和执行中删除被拒绝');

  const disconnectedId = await create();
  const controller = new AbortController();
  const disconnected = await fetch(base + '/ai/graph/stream', {
    method: 'POST', signal: controller.signal,
    headers: { ...authHeaders, 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId: disconnectedId, query: '统计一号仓库最近30天各商品销量，慢任务' }),
  });
  await disconnected.body.getReader().read();
  controller.abort();
  await waitUntil(() => success('GET', `/ai/assistant/conversations/${disconnectedId}`),
    value => !value.running && value.runStatus === 'PLAN_READY');
  assert.equal((await history(disconnectedId)).records.at(-1).content.type, 'PLAN');
  checks.push('客户端断流不取消后台执行，结果仍落MySQL');

  const memoriesBefore = databaseCount('ai_node_memory', id);
  await success('DELETE', `/ai/assistant/conversations/${id}`);
  assert.equal(databaseCount('ai_message', id, ' AND deleted=0'), 0);
  assert.equal(databaseCount('ai_node_memory', id), memoriesBefore);
  checks.push('删除只作用可见流水及会话，节点记忆保留');

  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, JSON.stringify({ passed: true,
    mode: '真实Java Controller/Service/Mapper/MySQL/Redis/SaToken；受控模型，不调用云模型', checks, conversations }, null, 2));
  console.log(`AI_GRAPH_REAL_INTEGRATION_OK: ${checks.length} 项检查通过；${output}`);
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
