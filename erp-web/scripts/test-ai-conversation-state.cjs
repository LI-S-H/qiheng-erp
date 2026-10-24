const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const timeModule = { exports: {} };
new Function('exports', ts.transpileModule(fs.readFileSync(path.resolve(__dirname,
  '../src/modules/ai/conversation-time.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText)(timeModule.exports);
const { conversationTimestamp, conversationDateGroup, compareConversationActivity } = timeModule.exports;
const { createPinia, setActivePinia } = require('pinia');
let token = 'account-a';
global.localStorage = { getItem: () => token };
const deferred = () => {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
let overview = async () => ({ conversations: [] });
let detail = async (id) => ({ conversationId: id, runStatus: 'IDLE' });
let history = async () => ({ records: [], pageNum: 1 });
let older = async () => ({ records: [], pageNum: 1 });
const source = path.resolve(__dirname, '../src/modules/ai/stores/aiConversationStore.ts');
const code = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const moduleShim = { exports: {} };
new Function('require', 'module', 'exports', code)(
  (name) => {
    if (name === '../conversation-time') return timeModule.exports;
    if (name === 'pinia') {
      return require(name);
    }
    if (name.includes('storage')) {
      return { AUTH_TOKEN_STORAGE_KEY: 'token' };
    }
    if (name === '../message-contract') {
      return {
        compareMessages: (a, b) =>
          BigInt(a.seq) < BigInt(b.seq) ? -1 : BigInt(a.seq) > BigInt(b.seq) ? 1 : 0,
      };
    }
    if (name === '../api') {
      return {
        getAiAssistantOverview: (...args) => overview(...args),
        getAiConversationDetail: (...args) => detail(...args),
        getAiConversationHistory: (...args) => history(...args),
        getAiConversationMessagePage: (...args) => older(...args),
      };
    }
    throw new Error(name);
  },
  moduleShim,
  moduleShim.exports,
);
const { useAiConversationStore } = moduleShim.exports;
async function main() {
  setActivePinia(createPinia());
  const store = useAiConversationStore();
  const oldOverview = deferred();
  overview = () => oldOverview.promise;
  const pendingList = store.loadConversations();
  store.reset();
  token = 'account-b';
  overview = async () => ({ conversations: [{ conversationId: 'new-account' }] });
  await store.loadConversations();
  oldOverview.resolve({ conversations: [{ conversationId: 'old-account' }] });
  await pendingList;
  assert.deepEqual(
    store.conversations.map((item) => item.conversationId),
    ['new-account'],
  );

  const slowDetail = deferred();
  detail = (id) =>
    id === 'slow' ? slowDetail.promise : Promise.resolve({ conversationId: id, runStatus: 'IDLE' });
  history = async () => ({ records: [], pageNum: 1 });
  const pendingSlow = store.loadConversation('slow');
  await store.loadConversation('latest');
  slowDetail.resolve({ conversationId: 'slow', runStatus: 'WAITING_HUMAN' });
  await pendingSlow;
  assert.equal(store.activeId, 'latest');
  assert.equal(store.detail.conversationId, 'latest');

  store.oldestPage = 0;
  const delayedHistory = deferred();
  history = () => delayedHistory.promise;
  const refresh = store.loadConversation('latest');
  store.upsertMessage({ messageId: 'new', seq: '9007199254740993' });
  delayedHistory.resolve({ records: [{ messageId: 'old', seq: '9007199254740992' }], pageNum: 3 });
  await refresh;
  assert.deepEqual(
    store.messages.map((item) => item.messageId),
    ['old', 'new'],
  );
  assert.equal(store.oldestPage, 3);
  store.oldestPage = 3;
  const delayedOlder = deferred();
  older = () => delayedOlder.promise;
  const pendingOlder = store.loadOlder();
  history = async () => ({ records: [], pageNum: 3 });
  await store.loadConversation('latest');
  delayedOlder.resolve({ records: [{ messageId: 'stale', seq: '1' }], pageNum: 2 });
  await pendingOlder;
  assert.equal(store.olderLoading, false);
  assert.ok(!store.messages.some((item) => item.messageId === 'stale'));
  store.reset();
  const oldDate = '2026-10-01 09:00:00';
  const nowDate = '2026-10-23 10:00:00';
  overview = async () => ({ conversations: [{ conversationId: '1', title: '旧会话', updatedAt: oldDate }] });
  await store.loadConversations();
  const staleActivity = deferred();
  overview = () => staleActivity.promise;
  const staleList = store.loadConversations();
  store.touchActivity('1', nowDate);
  staleActivity.resolve({ conversations: [{ conversationId: '1', title: '旧会话', updatedAt: oldDate }] });
  await staleList;
  assert.equal(store.conversations[0].updatedAt, nowDate, '发送前发起的旧列表不能把会话退回更早');
  overview = async () => ({ conversations: [{ conversationId: '1', title: '新标题', updatedAt: '2026-10-23 09:58:00' }] });
  await store.loadConversations();
  assert.equal(store.conversations[0].updatedAt, '2026-10-23 09:58:00', '活动确认后接受服务端时间，避免客户端时钟偏快');
  store.touchActivity('1', nowDate);
  store.pendingActivity['1'].touchedAt = Date.now() - 60_001;
  await store.loadConversations();
  assert.equal(store.conversations[0].updatedAt, '2026-10-23 09:58:00', '短期乐观状态超时后恢复服务端时间');
  store.upsertConversationSummary({ conversationId: '1', title: '晚到详情', updatedAt: oldDate });
  assert.equal(store.conversations[0].updatedAt, '2026-10-23 09:58:00', '晚到详情不能回退已确认活动时间');
  assert.equal(conversationTimestamp('2026-02-30'), null);
  assert.equal(conversationTimestamp('2026-10-23'), new Date(2026, 9, 23).getTime());
  assert.equal(conversationTimestamp('2026-10-23T00:30:00+08:00'), Date.parse('2026-10-23T00:30:00+08:00'));
  assert.equal(conversationDateGroup('2026-10-23 00:01:00', new Date(2026, 9, 23, 23, 59)), 'today');
  assert.equal(conversationDateGroup('2026-10-23 23:59:00', new Date(2026, 9, 24, 0, 1)), 'yesterday');
  assert.equal(conversationDateGroup('2026-10-17', new Date(2026, 9, 23)), 'week');
  assert.equal(conversationDateGroup('2026-10-16', new Date(2026, 9, 23)), 'earlier');
  assert.deepEqual([
    { conversationId: '9', updatedAt: '无效' },
    { conversationId: '1', updatedAt: nowDate },
    { conversationId: '2', updatedAt: nowDate },
  ].sort(compareConversationActivity).map(row => row.conversationId), ['2', '1', '9']);
  console.log(
    'AI_CONVERSATION_STATE_PASSED: 账号隔离、切会话、历史合并、分页、日期及时归属、旧响应保护、时钟偏差和跨日',
  );
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
