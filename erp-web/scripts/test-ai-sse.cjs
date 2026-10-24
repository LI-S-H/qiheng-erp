const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

// 加载当前真实 TypeScript 实现，覆盖分块读取、契约校验和动态鉴权头。
const cache = new Map();
const http = { defaults: { baseURL: '/api' } };
function loadSource(filename) {
  if (cache.has(filename)) {
    return cache.get(filename).exports;
  }
  const module = { exports: {} };
  cache.set(filename, module);
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const sourceRequire = (name) => {
    if (name === '@/api/http') {
      return { http };
    }
    if (name.startsWith('@/')) {
      return loadSource(path.resolve(__dirname, '../src', `${name.slice(2)}.ts`));
    }
    if (name.startsWith('.')) {
      return loadSource(path.resolve(path.dirname(filename), `${name}.ts`));
    }
    return require(name);
  };
  new Function('require', 'module', 'exports', code)(sourceRequire, module, module.exports);
  return module.exports;
}
const { createSseParser, readSseStream } = loadSource(
  path.resolve(__dirname, '../src/modules/ai/sse.ts'),
);
const { startGraphStream, resumeGraphStream, subscribeConversationProgress } = loadSource(
  path.resolve(__dirname, '../src/modules/ai/graph-stream.ts'),
);
const { normalizeMessage, compareMessages } = loadSource(
  path.resolve(__dirname, '../src/modules/ai/message-contract.ts'),
);
const { submittedAnswerTags } = loadSource(
  path.resolve(__dirname, '../src/modules/ai/clarification-history.ts'),
);
const storage = new Map([
  ['erp_auth_token', 'test-token'],
  ['erp_auth_token_name', 'x-erp-session'],
]);
global.localStorage = {
  getItem: (key) => storage.get(key) ?? null,
  removeItem: (key) => storage.delete(key),
};
global.window = { location: { href: '' } };
const originalFetch = global.fetch;
const id = '1960000000000000001';
const plan = {
  title: '库存分析',
  objective: '明确范围',
  needs_clarification: true,
  clarification_questions: [{ id: 'period', question: '分析多久？', options: ['最近30天'] }],
  steps: [],
};
const message = {
  messageId: '1963000000000000002',
  requestId: 'req-test',
  seq: '9007199254740993',
  role: 'assistant',
  kind: 'ASSISTANT_FINAL',
  content: {
    type: 'CLARIFICATION',
    data: {
      text: '请补充',
      questions: plan.clarification_questions,
      threadId: 'thread',
      pauseId: 'p-1',
    },
  },
  createdAt: '2026-10-16 10:00:00',
};
const event = (name, data) => `event: ${name}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`;
/** PLAN 产物展示内容：与可见消息 content 同构，artifact 与消息流共用。 */
const planArtifact = {
  type: 'PLAN',
  data: {
    text: '库存分析\n明确范围',
    plan: {
      title: '库存分析',
      objective: '汇总各仓库销量',
      needs_clarification: false,
      clarification_questions: [],
      steps: [{ step: 1, type: 'SQL_QUERY', instruction: '汇总各仓库销量' }],
    },
  },
};
const need = {
  id: 'N1', intent: 'DATA', need: '汇总仓库销量', depends: [], status: 'PENDING',
  evidence_plan: [{ id: 'N1-E1', label: '仓库销量', instruction: '查询最近30天各仓销量', source: 'SQL', depends: [] }],
};
// condition 为 null 时服务端 non_null 序列化会省略字段。
const needsArtifact = { type: 'NEEDS', data: { text: '已拆解出1条需求', needs: [need], threadId: 'thread' } };
function responseFor(text, byteByByte = true) {
  const bytes = new TextEncoder().encode(text);
  const body = new ReadableStream({
    start(controller) {
      if (byteByByte) {
        for (const byte of bytes) {
          controller.enqueue(Uint8Array.of(byte));
        }
      } else {
        controller.enqueue(bytes);
      }
      controller.close();
    },
  });
  return new Response(body, { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } });
}
function collector() {
  const events = [];
  return {
    events,
    handlers: {
      onNodeStart: (value) => events.push(['start', value]),
      onNodeEnd: (value) => events.push(['end', value]),
      onArtifact: (value) => events.push(['artifact', value]),
      onClarification: (value) => events.push(['clarification', value]),
      onRunFinished: (value) => events.push(['finished', value]),
      onStopped: (value) => events.push(['stopped', value]),
    },
  };
}
async function main() {
  const frames = [];
  const parser = createSseParser((frame) => frames.push(frame));
  for (const chunk of [
    ':心跳\r',
    '\n\r',
    '\nevent: run_finished\r\nda',
    'ta: {"a":\r\ndata: "中"}\r',
    '\n\r\n',
    'data:\n\n',
  ]) {
    parser.push(chunk);
  }
  assert.deepEqual(frames, [{ event: 'run_finished', data: '{"a":\n"中"}' }]);
  const decoded = [];
  await readSseStream(responseFor(event('run_stopped', { status: 'FAILED', reason: '中文错误' })).body, (frame) =>
    decoded.push(JSON.parse(frame.data).reason),
  );
  assert.deepEqual(decoded, ['中文错误']);

  let sent;
  // 正常完成：节点进度后以薄载荷 run_finished 收尾，正文由流水提供
  global.fetch = async (url, config) => {
    sent = { url, config };
    return responseFor(
      ':ping\n\n' +
        event('node-start', { node: 'planner', label: '需求规划' }) +
        event('node-end', { node: 'planner', label: '需求规划', durationMs: 40 }) +
        event('run_finished', { status: 'NEEDS_READY' }),
    );
  };
  const first = collector();
  await startGraphStream(id, '库存分析', first.handlers);
  assert.equal(sent.url, '/api/ai/graph/stream');
  assert.equal(sent.config.headers.get('x-erp-session'), 'test-token');
  assert.deepEqual(JSON.parse(sent.config.body), { conversationId: id, query: '库存分析' });
  assert.deepEqual(
    first.events.map((item) => item[0]),
    ['start', 'end', 'finished'],
  );
  assert.deepEqual(first.events[2][1], { status: 'NEEDS_READY', warning: null });

  // artifact 事件：产物定稿先于 run_finished 推送，content 复用消息展示协议校验
  global.fetch = async () =>
    responseFor(
      event('node-start', { node: 'planner', label: '需求规划' }) +
        event('artifact', {
          type: 'NEEDS',
          checkpointId: 'cp-artifact',
          content: needsArtifact,
        }) +
        event('run_finished', { status: 'NEEDS_READY', warning: '历史保存失败' }),
    );
  const artifactRun = collector();
  await startGraphStream(id, '库存分析', artifactRun.handlers);
  assert.deepEqual(
    artifactRun.events.map((item) => item[0]),
    ['start', 'artifact', 'finished'],
  );
  const artifactEvent = artifactRun.events[1][1];
  assert.equal(artifactEvent.type, 'NEEDS');
  assert.equal(artifactEvent.checkpointId, 'cp-artifact');
  assert.equal(artifactEvent.content.type, 'NEEDS');
  assert.equal(artifactEvent.content.data.needs[0].need, '汇总仓库销量');
  assert.equal(artifactEvent.content.data.needs[0].condition, null);
  assert.equal(artifactEvent.content.data.needs[0].status, 'PENDING', '就绪事件不能推算需求完成');
  assert.equal('done_when' in artifactEvent.content.data.needs[0], false, '当前真实Need DTO已移除done_when，不得强制提供或伪造');
  assert.equal(artifactRun.events[2][1].warning, '历史保存失败');
  // 旧 PLAN 事件和状态继续兼容；checkpointId 缺失时归一为 null。
  global.fetch = async () =>
    responseFor(event('artifact', { type: 'PLAN', content: planArtifact }) + event('run_finished', { status: 'PLAN_READY' }));
  const missingCheckpointRun = collector();
  await startGraphStream(id, '库存分析', missingCheckpointRun.handlers);
  assert.equal(missingCheckpointRun.events[0][1].checkpointId, null);
  assert.equal(missingCheckpointRun.events[0][1].content.data.plan.title, '库存分析');
  assert.equal(missingCheckpointRun.events.at(-1)[1].status, 'PLAN_READY');

  // 历史归一与 SSE 共用同一需求契约，拒绝缺字段和非法枚举。
  assert.equal(normalizeMessage({ ...message, content: needsArtifact }).content.data.needs[0].condition, null);
  const legacyNeed = { ...need, done_when: { type: 'EVIDENCE_KEYS_PRESENT', keys: ['N1-E1'] } };
  assert.deepEqual(normalizeMessage({ ...message, content: { ...needsArtifact, data: { ...needsArtifact.data, needs: [legacyNeed] } } }).content.data.needs[0].done_when, legacyNeed.done_when);
  for (const status of ['PENDING', 'RUNNING', 'DONE', 'SKIPPED', 'BLOCKED', 'INFEASIBLE']) {
    for (const intent of ['DATA', 'ADVICE']) {
      for (const source of ['SQL', 'PYTHON']) {
        const currentNeed = { ...need, status, intent, evidence_plan: [{ ...need.evidence_plan[0], source }] };
        assert.equal(normalizeMessage({ ...message, content: { ...needsArtifact, data: { ...needsArtifact.data, needs: [currentNeed] } } }).content.data.needs[0].status, status);
      }
    }
  }
  const withCondition = { ...need, condition: { left: 'evidence.N1-E1', op: 'GTE', right: 10 } };
  assert.deepEqual(normalizeMessage({ ...message, content: { ...needsArtifact, data: { ...needsArtifact.data, needs: [withCondition] } } }).content.data.needs[0].condition, withCondition.condition);
  for (const broken of [
    { ...need, intent: 'QUERY' },
    { ...need, status: 'COMPLETED' },
    { ...need, evidence_plan: [{ ...need.evidence_plan[0], source: 'SQL_QUERY' }] },
    { ...need, done_when: { type: 'NATURAL_LANGUAGE', keys: ['N1-E1'] } },
    { ...need, condition: { left: 'evidence.N1-E1', op: 'GE', right: 10 } },
    { ...need, condition: { left: 'evidence.N1-E1', op: 'EQ', right: {} } },
    { ...need, evidence_plan: null },
    { ...need, evidence_plan: [] },
  ]) {
    const content = { ...needsArtifact, data: { ...needsArtifact.data, needs: [broken] } };
    assert.throws(() => normalizeMessage({ ...message, content }), /需求接口返回的数据不符合约定/);
    global.fetch = async () => responseFor(event('artifact', { type: 'NEEDS', content }));
    await assert.rejects(() => startGraphStream(id, '库存分析', collector().handlers), /需求接口返回的数据不符合约定/);
  }
  global.fetch = async () => responseFor(event('artifact', { type: '', content: {} }));
  await assert.rejects(
    () => startGraphStream(id, '库存分析', collector().handlers),
    /缺少字段 type/,
  );

  // 澄清事件：暂停轮的唯一内容推送，校验内容类型；恢复锚点在内容 pauseId，顶层检查点仅作诊断
  global.fetch = async () =>
    responseFor(
      event('node-start', { node: 'plan_check', label: '校验计划格式' }) +
        event('clarification_required', { checkpointId: 'ckpt-pause', content: message.content }),
    );
  const pause = collector();
  await startGraphStream(id, '库存分析', pause.handlers);
  assert.deepEqual(
    pause.events.map((item) => item[0]),
    ['start', 'clarification'],
  );
  assert.equal(pause.events[1][1].checkpointId, 'ckpt-pause');
  assert.equal(pause.events[1][1].content.data.pauseId, 'p-1');
  assert.equal(pause.events[1][1].content.data.questions[0].id, 'period');
  assert.equal('plan' in pause.events[1][1].content.data, false, '当前澄清不依赖旧计划');
  // 内容类型不符时拒绝，不静默放行
  global.fetch = async () =>
    responseFor(event('clarification_required', { checkpointId: 'cp', content: planArtifact }));
  await assert.rejects(
    () => startGraphStream(id, '库存分析', collector().handlers),
    /CLARIFICATION/,
  );
  // 顶层检查点缺失只影响诊断字段；恢复锚点仍在内容 pauseId
  global.fetch = async () =>
    responseFor(event('clarification_required', { content: message.content }));
  const anonymousPause = collector();
  await startGraphStream(id, '库存分析', anonymousPause.handlers);
  assert.equal(anonymousPause.events[0][1].checkpointId, null);
  assert.equal(anonymousPause.events[0][1].content.data.pauseId, 'p-1');

  // 恢复流：同样以 run_finished 收尾
  global.fetch = async (url, config) => {
    sent = { url, config };
    return responseFor(
      event('node-start', { node: 'planner', label: '需求规划' }) +
        event('node-end', { node: 'planner', label: '需求规划', durationMs: 40 }) +
        event('run_finished', { status: 'NEEDS_READY' }),
    );
  };
  const resumed = collector();
  await resumeGraphStream(
    { threadId: 'thread', pauseId: 'p-1', answer: '最近30天' },
    resumed.handlers,
  );
  assert.equal(sent.url, '/api/ai/graph/resume/stream');
  assert.equal(JSON.parse(sent.config.body).pauseId, 'p-1');
  assert.equal(resumed.events.at(-1)[0], 'finished');

  global.fetch = async () => responseFor('');
  await subscribeConversationProgress(id, collector().handlers);
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /结果未知/);

  // 失败终态：run_stopped 承载状态与说明，reason 可直接展示
  global.fetch = async () =>
    responseFor(event('run_stopped', { status: 'FAILED', reason: '服务繁忙' }));
  const failed = collector();
  await startGraphStream(id, '测试', failed.handlers);
  assert.deepEqual(failed.events, [
    ['stopped', { status: 'FAILED', reason: '服务繁忙', warning: null }],
  ]);
  global.fetch = async () => responseFor(event('run_stopped', { status: 'FORMAT_RETRY_EXHAUSTED' }));
  const incomplete = collector();
  await startGraphStream(id, '测试', incomplete.handlers);
  assert.deepEqual(incomplete.events, [['stopped', { status: 'FORMAT_RETRY_EXHAUSTED', reason: null, warning: null }]], 'non_null省略薄终态中的reason与warning');
  const incompleteMessage = normalizeMessage({
    ...message,
    content: { type: 'ERROR', data: { text: '本轮规划未完成，请调整需求后重新发起。', status: 'FORMAT_RETRY_EXHAUSTED', threadId: 'thread-incomplete' } },
  });
  assert.equal(incompleteMessage.content.data.status, 'FORMAT_RETRY_EXHAUSTED');
  assert.equal(incompleteMessage.content.data.threadId, 'thread-incomplete');
  const failureMessage = normalizeMessage({ ...message, content: { type: 'ERROR', data: { text: '服务暂时不可用' } } });
  assert.deepEqual(failureMessage.content.data, { text: '服务暂时不可用' }, '普通失败不伪造状态或任务标识');
  assert.equal(normalizeMessage({ ...message, content: { type: 'ERROR', data: { text: '已有错误', status: null, threadId: null } } }).content.data.status, undefined);
  assert.throws(() => normalizeMessage({ ...message, content: { type: 'UNKNOWN', data: { text: '未知消息' } } }), /未知的用户可见消息类型/, '未知载荷不得伪装成成功消息');
  // 终态状态必须在契约枚举内，未知取值按协议错误处理
  global.fetch = async () => responseFor(event('run_finished', { status: 'WAITING_HUMAN' }));
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /不在约定范围/);
  global.fetch = async () =>
    responseFor(event('run_stopped', { status: 'PLAN_READY', reason: '越界状态' }));
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /不在约定范围/);

  global.fetch = async () =>
    responseFor(event('node-end', { node: 'planner', label: '规划', durationMs: 'NaN' }));
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /非负有限数值/);
  // 旧协议的 result 事件已下线：未知事件名直接报错
  global.fetch = async () => responseFor(event('result', { status: 'WAITING_HUMAN' }));
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /未知的进度事件/);
  global.fetch = async () => responseFor('event: run_finished\ndata: invalid\n\n');
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /合法 JSON/);
  global.fetch = async () =>
    new Response('{}', { headers: { 'Content-Type': 'application/json' } });
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /约定的进度流/);
  global.fetch = async () => new Response('{"message":"登录失效"}', { status: 401 });
  await assert.rejects(() => startGraphStream(id, '测试', collector().handlers), /登录失效/);
  assert.equal(global.window.location.href, '/login');
  assert.equal(storage.has('erp_auth_token'), false);

  // 旧消息归一：缺 pauseId 的澄清与 PLAN 均按无恢复锚点通过，页面只读展示
  const legacyClarification = normalizeMessage({
    ...message,
    content: {
      type: 'CLARIFICATION',
      data: { text: '请补充', plan, questions: plan.clarification_questions, threadId: 'thread' },
    },
  });
  assert.equal(legacyClarification.content.data.pauseId, undefined);
  const legacyPlan = normalizeMessage({
    ...message,
    content: { type: 'PLAN', data: { text: '旧计划', plan } },
  });
  assert.equal(legacyPlan.content.data.pauseId, undefined);
  assert.equal(compareMessages({ ...message, seq: '9007199254740992' }, message), -1);
  assert.throws(() => normalizeMessage({ ...message, seq: 9007199254740992 }), /必须是字符串/);
  const submittedText = '分析多久？\n最近30天';
  const submittedSnapshot = {
    text: submittedText,
    answers: [{ id: 'period', question: '分析多久？', answer: '最近30天' }],
  };
  assert.deepEqual(submittedAnswerTags(submittedSnapshot, plan.clarification_questions, submittedText), ['最近30天']);
  for (const snapshot of [null, [], {}, { ...submittedSnapshot, text: '篡改正文' },
    { ...submittedSnapshot, answers: [] },
    { ...submittedSnapshot, answers: [{ id: 'period', question: '分析多久？', answer: '最近7天' }] },
    { ...submittedSnapshot, answers: [{ id: 'other', question: '分析多久？', answer: '最近30天' }] },
    { ...submittedSnapshot, answers: [{ id: 'period', question: '另一道问题', answer: '最近30天' }] },
    { ...submittedSnapshot, answers: [{ id: 'period', question: '分析多久？', answer: '  ' }] }]) {
    assert.deepEqual(submittedAnswerTags(snapshot, plan.clarification_questions, submittedText), [], '无可信匹配快照时只展示服务端关联全文');
  }
  console.log(
    'AI_SSE_TESTS_PASSED: 分块中文、最新Need无done_when与历史兼容、枚举及非空证据、无plan澄清、薄终态与ERROR分类保留、恢复、鉴权、旧PLAN、快照标签和BIGINT排序',
  );
}
main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => {
    global.fetch = originalFetch;
  });
