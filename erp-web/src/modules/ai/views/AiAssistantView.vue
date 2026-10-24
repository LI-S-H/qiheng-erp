<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import '@/styles/ai-pilot.css';
import { useMediaQuery } from '@vueuse/core';
import { useRouter } from 'vue-router';
import {
  ArrowRight,
  MessageSquare,
  ClipboardList,
  Home,
  LoaderCircle,
  Menu,
  TriangleAlert,
} from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { getApiErrorMessage } from '@/api/http';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import AiClarificationForm from '../components/AiClarificationForm.vue';
import AiComposerDock from '../components/AiComposerDock.vue';
import AiConversationSidebar from '../components/AiConversationSidebar.vue';
import AiMessageList from '../components/AiMessageList.vue';
import AiMessageNavigator from '../components/AiMessageNavigator.vue';
import AiPlanPanel from '../components/AiPlanPanel.vue';
import AiProgressFeed from '../components/AiProgressFeed.vue';
import { usePanelResize } from '../composables/use-panel-resize';
import { useConversationRuns, type ConversationRun } from '../composables/use-conversation-runs';
import type { ClarificationDraft, PlanningArtifact } from '../graph-types';
import {
  resumeGraphStream,
  startGraphStream,
  subscribeConversationProgress,
  type AiRunHandlers,
} from '../graph-stream';
import { useAiConversationStore } from '../stores/aiConversationStore';
import type { AiConversationMessage } from '../types';

const router = useRouter();
const auth = useAuthStore();
const store = useAiConversationStore();
const runs = useConversationRuns();
const currentRun = computed(() => runs.get(store.activeId));
const runtimeField = <K extends keyof ConversationRun>(key: K) => computed({
  get: () => currentRun.value[key],
  set: (value: ConversationRun[K]) => { currentRun.value[key] = value; },
});
const query = ref('');
const queryError = ref('');
const error = runtimeField('error');
const warning = runtimeField('warning');
const querying = runtimeField('querying');
const watching = runtimeField('watching');
const creating = ref(false);
const switching = ref(false);
const steps = runtimeField('steps');
const pendingText = runtimeField('pendingText');
const clarificationDraft = ref<ClarificationDraft>({ answers: {}, note: '' });
const clarificationForm = ref<InstanceType<typeof AiClarificationForm> | null>(null);
const questionDock = ref<HTMLElement | null>(null);
const scrollArea = ref<HTMLElement | null>(null);
const workspaceElement = ref<HTMLElement | null>(null);
const sidebarCollapsed = ref(false);
const mobileSidebarOpen = ref(false);
const planOpen = ref(false);
const selectedPlan = runtimeField('selectedPlan');
const livePlan = runtimeField('livePlan');
/** 仅在同账号切换期间显示旧内容；请求失败或账号切换立即丢弃，不能当作新会话数据。 */
const switchSnapshot = ref<{ messages: AiConversationMessage[]; plan: PlanningArtifact | null } | null>(null);
/** 未落库的暂停轮数据：澄清卡在库内出现前先行渲染表单，字段与落库卡 data 同构。 */
const pendingClarification = runtimeField('pendingClarification');
const wide = useMediaQuery('(min-width: 1360px)');
const small = useMediaQuery('(max-width: 900px)');
const phone = useMediaQuery('(max-width: 640px)');
const conversationResize = usePanelResize({
  storageKey: 'erp-ai-conversation-width', min: 200, max: 320, initial: 224,
});
// 两栏最大宽度之和保留桌面断点下至少 400px 的对话正文。
const planResize = usePanelResize({
  storageKey: 'erp-ai-plan-width', min: 280, max: 416, initial: 352, invert: true,
});
const workspaceStyle = computed(() => ({
  '--conversation-width': `${conversationResize.width.value}px`,
  '--plan-width': `${planResize.width.value}px`,
}));
let requestEpoch = 0;
let disposed = false;
const refreshes = new Map<string, Promise<void>>();

const busy = computed(() => creating.value || querying.value || watching.value);
const runningConversationIds = computed(() => Array.from(runs.states.entries())
  .filter(([id, state]) => id !== 'new' && (state.querying || state.watching || state.runStatus === 'RUNNING'))
  .map(([id]) => id));
const locked = computed(
  () =>
    busy.value ||
    switching.value ||
    store.historyLoading ||
    Boolean(store.activeId && !store.detail),
);
const runStatus = computed(() => currentRun.value.runStatus || store.detail?.runStatus || 'IDLE');
const latestQuestion = computed(() => {
  // 当前有未落库的暂停数据时，它比历史流水新，不能让上一轮问题占用可提交插槽。
  if (runStatus.value !== 'WAITING_HUMAN' || pendingClarification.value) {
    return null;
  }
  const messages = store.messages;
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const item = messages[i];
    if (item.role === 'assistant') {
      if (
        item.content.type !== 'CLARIFICATION' ||
        !item.content.data.threadId ||
        !item.content.data.pauseId
      ) {
        return null;
      }
      const answered = messages
        .slice(i + 1)
        .some(
          (message) =>
            message.content.type === 'TEXT' &&
            message.content.data.replyToMessageId === item.messageId,
        );
      return answered ? null : item;
    }
  }
  return null;
});
const currentQuestionData = computed(() => {
  // 未落库暂停数据只在等待补充状态可用：暂停点消费后再失败时表单必须退出
  if (pendingClarification.value && runStatus.value === 'WAITING_HUMAN') {
    return pendingClarification.value;
  }
  return latestQuestion.value?.content.type === 'CLARIFICATION'
    ? latestQuestion.value.content.data
    : null;
});
const waiting = computed(() => Boolean(currentQuestionData.value));
const activePlan = computed(
  // 计划面板展示优先级：人工打开的历史计划 > 预渲染的当轮计划
  () => selectedPlan.value || livePlan.value || null,
);
const readingConversation = computed(() => switching.value || (store.historyLoading && !busy.value));
const displayedMessages = computed(() => switching.value && switchSnapshot.value ? switchSnapshot.value.messages : store.messages);
const displayedPlan = computed(() => switching.value && switchSnapshot.value ? switchSnapshot.value.plan : activePlan.value);
const showWelcome = computed(
  () => !store.messages.length && !store.historyLoading && !pendingText.value && !busy.value && !switching.value && !error.value,
);
const statusLabel = computed(() => {
  if (busy.value) {
    return steps.value.find((step) => step.status === 'running')?.label || '正在规划';
  }
  return (
    {
      WAITING_HUMAN: '等待补充',
      PLAN_READY: '计划已生成',
      NEEDS_READY: '需求已拆解',
      FAILED: '执行失败',
      INTERRUPTED: '执行已中断',
      FORMAT_RETRY_EXHAUSTED: '规划未完成',
      IDLE: '准备就绪',
      RUNNING: '等待连接',
    } as Record<string, string>
  )[runStatus.value];
});
const composerValue = computed({
  get: () => (waiting.value ? clarificationDraft.value.note : query.value),
  set: (value: string) => {
    if (waiting.value) {
      clarificationDraft.value = { ...clarificationDraft.value, note: value };
    } else {
      query.value = value;
    }
  },
});
const examples = [
  { title: '库存与补货', text: '请分析库存情况，给出补货建议' },
  { title: '销售趋势', text: '分析最近30天的销售趋势，找出值得关注的商品' },
  { title: '采购分析', text: '帮我规划一次供应商供货表现分析' },
];

function scopedDraftKey(userId: string | undefined, id: string | null, kind: string, checkpoint = '') {
  return `erp.ai.draft.v2:${userId}:${id || 'new'}:${kind}:${checkpoint}`;
}
function draftKey(kind: string, checkpoint = '') {
  return scopedDraftKey(auth.user?.userId, store.activeId, kind, checkpoint);
}
function storageSet(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // 存储受限仍保留当前页面的输入。
  }
}
function storageRead(key: string): unknown {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null');
  } catch {
    return null;
  }
}
interface SubmittedDraft {
  text: string;
  afterSeq: string;
  pauseId: string | null;
  fromNew: boolean;
}
function clearSubmittedDraft(userId: string | undefined, id: string, submission: SubmittedDraft) {
  if (submission.pauseId) {
    storageSet(scopedDraftKey(userId, id, 'clarification', submission.pauseId), null);
  } else {
    for (const key of [scopedDraftKey(userId, id, 'query'), ...(submission.fromNew ? [scopedDraftKey(userId, null, 'query')] : [])]) {
      const cached = storageRead(key);
      if (typeof cached === 'string' && cached.trim() === submission.text) storageSet(key, '');
    }
    if (store.activeId === id && !switching.value && query.value.trim() === submission.text) query.value = '';
  }
  storageSet(scopedDraftKey(userId, id, 'inflight'), null);
}
/** 页面卸载只断开订阅。重进后用新增用户消息确认受理，再清草稿，不能凭终态猜测曾提交成功。 */
function reconcileSubmittedDraft(id: string) {
  if (!store.detail || !['WAITING_HUMAN', 'NEEDS_READY', 'PLAN_READY'].includes(store.detail.runStatus)) return;
  const value = storageRead(draftKey('inflight'));
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  const item = value as Partial<SubmittedDraft>;
  if (typeof item.text !== 'string' || typeof item.afterSeq !== 'string' || !/^\d+$/.test(item.afterSeq) ||
    !(item.pauseId === null || typeof item.pauseId === 'string') || typeof item.fromNew !== 'boolean') return;
  const accepted = [...store.messages].reverse().find(message => message.role === 'user' && message.content.type === 'TEXT');
  if (accepted?.content.type === 'TEXT' && accepted.content.data.text === item.text &&
    BigInt(accepted.seq) > BigInt(item.afterSeq)) {
    clearSubmittedDraft(auth.user?.userId, id, item as SubmittedDraft);
    return true;
  }
  return false;
}
function restoreQuery() {
  const value = storageRead(draftKey('query'));
  query.value = typeof value === 'string' && value.length <= 10000 ? value : '';
}
function restoreClarification() {
  const data = currentQuestionData.value;
  clarificationDraft.value = { answers: {}, note: '' };
  if (!data?.pauseId) {
    return;
  }
  const cached = storageRead(draftKey('clarification', data.pauseId));
  if (!cached || typeof cached !== 'object' || Array.isArray(cached)) {
    return;
  }
  const value = cached as Record<string, unknown>;
  const answers = value.answers;
  if (
    !answers ||
    typeof answers !== 'object' ||
    Array.isArray(answers) ||
    typeof value.note !== 'string' ||
    value.note.length > 10000
  ) {
    return;
  }
  const safe: ClarificationDraft = { answers: {}, note: value.note };
  if (value.currentQuestionId === null || data.questions.some(question => question.id === value.currentQuestionId)) {
    safe.currentQuestionId = value.currentQuestionId as string | null;
  }
  for (const question of data.questions) {
    const item = (answers as Record<string, unknown>)[question.id];
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      continue;
    }
    const entry = item as Record<string, unknown>;
    if (
      typeof entry.selected === 'string' &&
      typeof entry.custom === 'string' &&
      entry.custom.length <= 10000 &&
      ['', '__custom_answer__', ...question.options].includes(entry.selected)
    ) {
      safe.answers[question.id] = { selected: entry.selected, custom: entry.custom };
    }
  }
  clarificationDraft.value = safe;
}
watch(query, (value) => {
  if (!switching.value && auth.user) {
    storageSet(draftKey('query'), value);
  }
});
watch(
  clarificationDraft,
  (value) => {
    const data = currentQuestionData.value;
    if (!switching.value && data?.pauseId && auth.user) {
      storageSet(draftKey('clarification', data.pauseId), value);
    }
  },
  { deep: true },
);
watch(() => currentQuestionData.value?.pauseId, restoreClarification);

function isCurrent(epoch: number, userId: string | undefined, id?: string) {
  return (
    !disposed &&
    requestEpoch === epoch &&
    auth.user?.userId === userId &&
    (!id || store.activeId === id)
  );
}
function cancelTransient() {
  requestEpoch += 1;
  // 这里只结束旧视图的读取资格；运行连接和暂停数据仍属于原会话。
  queryError.value = '';
}
function stopSteps(state: ConversationRun, status: 'done' | 'failed' | 'interrupted') {
  state.steps.forEach((step) => {
    if (step.status === 'running') {
      step.status = status;
    }
  });
}
function handlers(
  state: ConversationRun,
  valid: () => boolean,
  id: string,
): AiRunHandlers {
  const settle = () => {
    state.refreshNeeded = true;
    state.settleSuccess?.();
    state.settleSuccess = null;
  };
  const syncDetail = () => {
    if (store.activeId === id && store.detail?.conversationId === id && state.runStatus) {
      store.detail = { ...store.detail, running: state.runStatus === 'RUNNING', runStatus: state.runStatus };
    }
  };
  return {
    onNodeStart: (progress) => {
      if (valid()) {
        // 节点已开始代表旧暂停点已消费，不能再用上一轮未落库数据覆盖新终态。
        state.pendingClarification = null;
        state.runStatus = 'RUNNING';
        state.error = '';
        state.steps.push({ ...progress, status: 'running' });
        syncDetail();
      }
    },
    onNodeEnd: (progress) => {
      if (!valid()) {
        return;
      }
      const step = [...state.steps]
        .reverse()
        .find((item) => item.node === progress.node && item.status === 'running');
      if (step) {
        Object.assign(step, progress, { status: 'done' });
      }
    },
    onArtifact: (artifact) => {
      if (!valid()) {
        return;
      }
      // 产物定稿即提前渲染：计划内容先于 run_finished 到达面板
      if (artifact.content.type === 'PLAN' || artifact.content.type === 'NEEDS') {
        state.livePlan = artifact.content;
        state.selectedPlan = null;
      }
    },
    onClarification: (clarification) => {
      if (!valid()) {
        return;
      }
      const data = clarification.content.type === 'CLARIFICATION' ? clarification.content.data : null;
      if (data && data.pauseId && data.threadId) {
        // 澄清卡入库前先用事件数据渲染表单；reload 后库内卡片出现即交回流水
        state.pendingClarification = {
          threadId: data.threadId,
          pauseId: data.pauseId,
          questions: data.questions,
        };
      }
      state.runStatus = 'WAITING_HUMAN';
      state.error = '';
      syncDetail();
      stopSteps(state, 'done');
      settle();
    },
    onRunFinished: (ending) => {
      if (!valid()) {
        return;
      }
      state.runStatus = ending.status;
      state.error = '';
      syncDetail();
      // 提前渲染的当轮计划转正；未收到产物时以库内最新计划为准（刷新后回填）
      if (state.livePlan) {
        state.selectedPlan = state.livePlan;
        state.livePlan = null;
      } else {
        // 中途订阅可能错过产物帧，完成后让历史刷新回填本次最新产物。
        state.selectedPlan = null;
      }
      state.warning = ending.warning || '';
      if (store.activeId === id && state.selectedPlan && wide.value) {
        planOpen.value = true;
      }
      stopSteps(state, 'done');
      settle();
    },
    onStopped: (ending) => {
      if (!valid()) {
        return;
      }
      state.runStatus = ending.status;
      state.refreshNeeded = true;
      syncDetail();
      state.warning = ending.warning || '';
      if (ending.reason) {
        state.error = ending.reason;
      }
      stopSteps(state, 'failed');
    },
  };
}
async function scrollToLatest() {
  await nextTick();
  if (scrollArea.value) {
    scrollArea.value.scrollTop = scrollArea.value.scrollHeight;
  }
}
/** 库内澄清卡片出现后，实时暂停数据交回流水渲染，避免两个表单同时占用插槽。 */
function clearSavedPendingClarification() {
  const pending = pendingClarification.value;
  if (!pending) {
    return;
  }
  const saved = store.messages.some(
    (message) =>
      message.content.type === 'CLARIFICATION' &&
      message.content.data.pauseId === pending.pauseId,
  );
  if (saved) {
    pendingClarification.value = null;
  }
}
async function reload(id: string, epoch: number, userId: string | undefined) {
  const key = `${epoch}:${id}`;
  const pending = refreshes.get(key);
  if (pending) return pending;
  const request = performReload(id, epoch, userId);
  refreshes.set(key, request);
  try {
    await request;
  } finally {
    if (refreshes.get(key) === request) refreshes.delete(key);
  }
}
async function performReload(id: string, epoch: number, userId: string | undefined) {
  if (!isCurrent(epoch, userId, id)) {
    return;
  }
  try {
    await store.loadConversation(id);
    if (isCurrent(epoch, userId, id)) {
      clearSavedPendingClarification();
      const state = runs.get(id);
      if (!state.streaming && store.detail) state.runStatus = store.detail.runStatus;
      if (reconcileSubmittedDraft(id)) state.error = '';
      state.refreshNeeded = false;
      if (!selectedPlan.value && !livePlan.value) {
        const saved = [...store.messages].reverse().find(message => message.content.type === 'NEEDS' || message.content.type === 'PLAN');
        if (saved?.content.type === 'NEEDS' || saved?.content.type === 'PLAN') selectedPlan.value = saved.content;
      }
    }
    if (isCurrent(epoch, userId, id)) {
      await store.loadConversations();
    }
  } catch (cause) {
    if (isCurrent(epoch, userId, id)) {
      warning.value = `历史刷新失败：${getApiErrorMessage(cause) || '请重新打开会话确认'}`;
    }
  }
}
async function run(answer?: string) {
  if (locked.value || runStatus.value === 'RUNNING' || (!answer && waiting.value)) {
    return;
  }
  const text = answer || query.value.trim();
  if (!text || text.length > 10000) {
    queryError.value = '请填写 1 到 10000 字的需求';
    return;
  }
  // 尚未取得会话 ID 时暂时锁住创建；取得后只锁当前会话，不限制切换和并行会话。
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  const authToken = auth.token;
  const question = currentQuestionData.value;
  const submittedDraft: ClarificationDraft = {
    ...clarificationDraft.value,
    answers: Object.fromEntries(Object.entries(clarificationDraft.value.answers).map(([key, value]) => [key, { ...value }])),
  };
  const fromNew = !store.activeId;
  let id = store.activeId;
  queryError.value = '';
  try {
    if (!id) {
      creating.value = true;
      try {
        id = (await store.createConversation()).conversationId;
      } finally {
        if (auth.user?.userId === userId && auth.token === authToken) creating.value = false;
      }
    }
    if (!isCurrent(epoch, userId, id)) {
      return;
    }
    const connection = runs.connect(id, 'querying');
    if (!connection) return;
    const { state, controller: currentController, version } = connection;
    const valid = () => !disposed && auth.user?.userId === userId && auth.token === authToken &&
      runs.states.get(id!) === state && state.version === version;
    const submittedKey = question?.pauseId ? draftKey('submitted', question.pauseId) : '';
    const submission: SubmittedDraft = {
      text, afterSeq: store.messages[store.messages.length - 1]?.seq || '0', pauseId: answer ? question?.pauseId ?? null : null, fromNew,
    };
    state.pendingText = text;
    state.error = '';
    state.warning = '';
    state.steps = [];
    state.livePlan = null;
    state.runStatus = 'RUNNING';
    // 清理范围固定在发送时的账号和会话；后台 A 完成不能修改 B 的输入。
    state.settleSuccess = () => clearSubmittedDraft(userId, id!, submission);
    storageSet(draftKey('inflight'), submission);
    store.touchActivity(id);
    await scrollToLatest();
    if (!valid()) return;
    const callbacks = handlers(state, valid, id);
    try {
    if (answer && question?.threadId && question.pauseId) {
      // 只有与服务端关联回答原文完全相符的快照才能增强历史标签展示。
      storageSet(submittedKey, {
        text: answer,
        note: submittedDraft.note.trim(),
        answers: question.questions.map(item => {
          const value = submittedDraft.answers[item.id];
          const usesText = !item.options.length || value?.selected === '__custom_answer__';
          return { id: item.id, question: item.question, answer: usesText ? value?.custom.trim() : value?.selected };
        }),
      });
      await resumeGraphStream(
        { threadId: question.threadId, pauseId: question.pauseId, answer },
        callbacks,
        currentController.signal,
      );
    } else {
      await startGraphStream(id, text, callbacks, currentController.signal);
    }
    } catch (cause) {
      if (valid() && !currentController.signal.aborted) {
        state.error = getApiErrorMessage(cause) || '请求失败，请重试';
        stopSteps(state, 'failed');
      }
    } finally {
      if (valid()) {
        state.streaming = false;
        state.refreshNeeded = true;
        if (store.activeId === id && !switching.value) await reload(id, requestEpoch, userId);
        if (valid()) {
          stopSteps(state, state.error ? 'failed' : 'interrupted');
          runs.disconnect(id, version);
          void store.loadConversations().catch(() => undefined);
          if (store.activeId === id && !switching.value) {
            await scrollToLatest();
            if (store.detail?.running) void watchIfRunning(id, requestEpoch, userId);
          }
        }
      }
    }
  } catch (cause) {
    if (isCurrent(epoch, userId, id || undefined)) error.value = getApiErrorMessage(cause) || '会话创建失败';
  }
}
async function watchIfRunning(id: string, epoch: number, userId: string | undefined) {
  if (!store.detail?.running || !isCurrent(epoch, userId, id)) {
    return;
  }
  const connection = runs.connect(id, 'watching');
  if (!connection) return;
  const { state, controller: currentController, version } = connection;
  const authToken = auth.token;
  const valid = () => !disposed && auth.user?.userId === userId && auth.token === authToken &&
    runs.states.get(id) === state && state.version === version;
  state.runStatus = 'RUNNING';
  if (store.detail.runNode) {
    state.steps = [
      {
        node: store.detail.runNode,
        label: store.detail.runLabel || store.detail.runNode,
        status: 'running',
        durationMs: null,
      },
    ];
  }
  try {
    await subscribeConversationProgress(
      id,
      handlers(state, valid, id),
      currentController.signal,
    );
  } catch (cause) {
    if (valid() && !currentController.signal.aborted) {
      state.error = `进度连接中断：${getApiErrorMessage(cause) || '请重新读取会话'}`;
    }
  } finally {
    if (valid()) {
      state.streaming = false;
      state.refreshNeeded = true;
      if (store.activeId === id && !switching.value) await reload(id, requestEpoch, userId);
      if (valid()) {
        stopSteps(state, 'interrupted');
        runs.disconnect(id, version);
        void store.loadConversations().catch(() => undefined);
      }
    }
  }
}
async function openConversation(id: string, force = false) {
  if (creating.value || switching.value || (!force && id === store.activeId && store.detail)) {
    return;
  }
  switchSnapshot.value = { messages: [...store.messages], plan: activePlan.value };
  cancelTransient();
  switching.value = true;
  // 面板宽度与开合由用户控制；切换只更新内容，不让工作区先收起再展开。
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  try {
    const loaded = await store.loadConversation(id);
    if (!loaded || !isCurrent(epoch, userId, id)) {
      return;
    }
    const state = runs.get(id);
    // 切换读取期间后台可能刚好收尾；再取一次最新流水，避免用旧 RUNNING 快照覆盖终态。
    if (state.refreshNeeded && !state.streaming) await reload(id, epoch, userId);
    if (!isCurrent(epoch, userId, id)) return;
    if (!state.streaming && store.detail) state.runStatus = store.detail.runStatus;
    if (reconcileSubmittedDraft(id)) state.error = '';
    clearSavedPendingClarification();
    restoreQuery();
    restoreClarification();
    const latestPlan = [...store.messages]
      .reverse()
      .find((message) => message.content.type === 'PLAN' || message.content.type === 'NEEDS');
    if (!state.livePlan && !state.selectedPlan && (latestPlan?.content.type === 'PLAN' || latestPlan?.content.type === 'NEEDS')) {
      selectedPlan.value = latestPlan.content;
    }
    mobileSidebarOpen.value = false;
    // 仍保持读取锁与遮罩，但滚动高度必须来自新会话，不能使用旧快照的高度。
    switchSnapshot.value = null;
    await scrollToLatest();
    void watchIfRunning(id, epoch, userId);
  } catch (cause) {
    if (isCurrent(epoch, userId, id)) {
      error.value = getApiErrorMessage(cause) || '会话读取失败，请重试';
    }
  } finally {
    if (isCurrent(epoch, userId)) {
      switching.value = false;
      switchSnapshot.value = null;
      // 终态也可能恰好落在上面的检查与切换遮罩退出之间，解除切换锁后补取一次。
      const state = runs.get(id);
      if (store.activeId === id && state.refreshNeeded && !state.streaming) await reload(id, epoch, userId);
    }
  }
}
async function createConversation() {
  if (creating.value || switching.value) {
    return;
  }
  switchSnapshot.value = { messages: [...store.messages], plan: activePlan.value };
  cancelTransient();
  switching.value = true;
  creating.value = true;
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  try {
    await store.createConversation();
    if (isCurrent(epoch, userId)) {
      query.value = '';
      clarificationDraft.value = { answers: {}, note: '' };
      selectedPlan.value = null;
      livePlan.value = null;
      planOpen.value = false;
      mobileSidebarOpen.value = false;
    }
  } catch (cause) {
    if (isCurrent(epoch, userId)) {
      error.value = getApiErrorMessage(cause) || '会话创建失败';
    }
  } finally {
    if (isCurrent(epoch, userId)) {
      creating.value = false;
      switching.value = false;
      switchSnapshot.value = null;
    }
  }
}
async function renameConversation(id: string, title: string) {
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  try {
    await store.renameConversation(id, title);
  } catch (cause) {
    if (isCurrent(epoch, userId)) {
      error.value = getApiErrorMessage(cause) || '重命名失败';
    }
  }
}
async function removeConversation(id: string) {
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  const authToken = auth.token;
  try {
    await store.removeConversation(id);
    if (!disposed && auth.user?.userId === userId && auth.token === authToken) runs.remove(id);
    if (isCurrent(epoch, userId) && !store.activeId) {
      cancelTransient();
      selectedPlan.value = null;
      livePlan.value = null;
      planOpen.value = false;
      restoreQuery();
    }
  } catch (cause) {
    if (isCurrent(epoch, userId)) {
      error.value = getApiErrorMessage(cause) || '删除失败';
    }
  }
}
async function loadOlder() {
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  const id = store.activeId || undefined;
  const before = scrollArea.value?.scrollHeight || 0;
  try {
    await store.loadOlder();
    await nextTick();
    if (isCurrent(epoch, userId, id) && scrollArea.value) {
      scrollArea.value.scrollTop += scrollArea.value.scrollHeight - before;
    }
  } catch (cause) {
    if (isCurrent(epoch, userId, id)) {
      error.value = getApiErrorMessage(cause) || '较早消息加载失败';
    }
  }
}
function openPlan(plan: PlanningArtifact) {
  const sameArtifact = activePlan.value && JSON.stringify(activePlan.value) === JSON.stringify(plan);
  selectedPlan.value = plan;
  void setPlanOpen(!(planOpen.value && sameArtifact));
}
async function focusQuestion() {
  await nextTick();
  questionDock.value?.querySelector<HTMLElement>('[data-question-title], [data-summary-title]')?.focus({ preventScroll: true });
}

function setQuestionInert(element: Element, inert: boolean) {
  // 离场动画会短暂保留旧表单，避免其与恢复后的普通输入同时接收操作。
  (element as HTMLElement).inert = inert;
}
async function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
  await nextTick();
  const label = sidebarCollapsed.value ? '展开会话列表' : '收起会话列表';
  workspaceElement.value?.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)?.focus({ preventScroll: true });
}
async function setPlanOpen(open: boolean) {
  planOpen.value = open;
  if (wide.value) {
    await nextTick();
    workspaceElement.value?.querySelector<HTMLButtonElement>('.desktop-plan button')?.focus({ preventScroll: true });
  }
}
function submit() {
  if (waiting.value) {
    clarificationForm.value?.submit();
  } else {
    void run();
  }
}
watch(
  () => [auth.user?.userId, auth.token] as const,
  async ([userId]) => {
    cancelTransient();
    runs.reset();
    creating.value = false;
    switchSnapshot.value = null;
    switching.value = true;
    store.reset();
    selectedPlan.value = null;
    livePlan.value = null;
    clarificationDraft.value = { answers: {}, note: '' };
    query.value = '';
    const epoch = requestEpoch;
    if (!userId) {
      switching.value = false;
      return;
    }
    try {
      await store.loadConversations();
      if (!isCurrent(epoch, userId)) {
        return;
      }
      const first = store.conversations[0];
      if (first) {
        switching.value = false;
        await openConversation(first.conversationId);
      } else {
        restoreQuery();
        switching.value = false;
      }
    } catch (cause) {
      if (isCurrent(epoch, userId)) {
        error.value = getApiErrorMessage(cause) || '会话列表读取失败';
        switching.value = false;
      }
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  runs.reset();
});
</script>

<template>
  <section ref="workspaceElement" class="graph-workspace ai-pilot-workspace" :style="workspaceStyle" :class="{ 'is-resizing': conversationResize.dragging.value || planResize.dragging.value }" data-testid="ai-graph-workspace">
    <AiConversationSidebar
      v-if="!small"
      :conversations="store.conversations"
      :active-id="store.activeId"
      :loading="store.listLoading"
      :busy="creating || switching"
      :running-ids="runningConversationIds"
      :switching="switching"
      :collapsed="sidebarCollapsed"
      @create="createConversation"
      @select="openConversation"
      @rename="renameConversation"
      @remove="removeConversation"
      @toggle-collapse="toggleSidebar"
    />
    <div
      v-if="!small && !sidebarCollapsed"
      class="panel-resizer"
      role="separator"
      tabindex="0"
      aria-label="调整会话栏宽度"
      aria-orientation="vertical"
      :aria-valuenow="conversationResize.width.value"
      :aria-valuemin="200"
      :aria-valuemax="320"
      @pointerdown="conversationResize.startResize"
      @keydown.left.prevent="conversationResize.nudge(-16)"
      @keydown.right.prevent="conversationResize.nudge(16)"
    />
    <main class="conversation-panel" :aria-busy="locked">
      <header class="workspace-header">
        <div class="workspace-title">
          <Button
            v-if="phone"
            variant="ghost"
            size="icon-sm"
            aria-label="返回工作台"
            @click="router.push('/dashboard')"
            ><Home class="size-4" /></Button
          ><Button
            v-if="small"
            variant="ghost"
            size="icon-sm"
            aria-label="打开会话列表"
            @click="mobileSidebarOpen = true"
            ><Menu class="size-4" /></Button
          ><MessageSquare class="size-4 text-primary" />
          <h1>{{ store.activeSummary?.title || '智能经营助手' }}</h1>
        </div>
        <div class="workspace-actions">
          <span class="task-status" role="status"
            ><LoaderCircle v-if="busy" class="size-3.5 animate-spin" /><span
              v-else
              class="status-dot"
            />{{ statusLabel }}</span
          ><Button
            variant="outline"
            size="sm"
            aria-label="查看任务计划"
            :aria-expanded="planOpen"
            @click="setPlanOpen(!planOpen)"
            ><ClipboardList class="size-4" /><span>计划</span></Button
          >
        </div>
      </header>
      <div class="conversation-body" :aria-busy="readingConversation" data-ai-conversation-region>
      <AiMessageNavigator :messages="displayedMessages" :scroll-container="scrollArea" :inert="readingConversation || undefined" />
      <div ref="scrollArea" class="conversation-scroll" :inert="readingConversation || undefined">
        <div class="conversation-stream">
          <div v-if="error" class="request-error" role="alert" data-testid="graph-request-error">
            <TriangleAlert class="size-4" />
            <div>
              <strong>暂时未能完成请求</strong>
              <p>{{ error }}</p>
              <Button
                v-if="store.activeId && !busy && !switching"
                variant="ghost"
                size="sm"
                @click="openConversation(store.activeId, true)"
                >重新读取会话</Button
              >
            </div>
          </div>
          <div v-if="warning" class="request-warning" role="status">{{ warning }}</div>
          <div v-if="showWelcome" class="welcome-state">
            <span class="welcome-symbol"><MessageSquare class="size-7" stroke-width="1.5" /></span>
            <h2>今天，你想分析什么？</h2>
            <p>说说你的目标，我会帮你确认范围和条件，<br />整理成清晰的分析计划。</p>
            <div class="example-list">
              <button
                v-for="example in examples"
                :key="example.title"
                type="button"
                @click="query = example.text"
              >
                <span>{{ example.title }}</span
                ><ArrowRight class="size-3.5" />
              </button>
            </div>
          </div>
          <Button
            v-if="store.hasOlder"
            variant="ghost"
            size="sm"
            :disabled="store.olderLoading || switching"
            class="older-button"
            @click="loadOlder"
            >{{ store.olderLoading ? '正在读取…' : '加载较早消息' }}</Button
          >
          <AiMessageList
            :messages="displayedMessages"
            :loading="store.historyLoading"
            :pending-text="pendingText"
            :active-question-id="latestQuestion?.messageId"
            :user-id="auth.user?.userId"
            :conversation-id="store.activeId || undefined"
            :active-artifact="planOpen ? activePlan : null"
            @open-plan="openPlan"
          >
            <template #clarification>
              <button type="button" class="question-status" data-testid="clarification-pending-status" aria-label="继续回答补充问题" :disabled="switching" @click="focusQuestion">
                <MessageSquare class="size-3.5" aria-hidden="true" />
                <span>等待你补充 {{ currentQuestionData?.questions.length }} 项信息</span>
                <span class="question-status-action">继续回答<ArrowRight class="size-3" aria-hidden="true" /></span>
              </button>
            </template>
          </AiMessageList>
          <AiProgressFeed v-if="steps.length" :steps="steps" :finished="!busy" />
          <p v-if="runStatus === 'INTERRUPTED' && !busy" class="state-note">
            上次执行已中断。可以重新描述需求后再试。
          </p>
          <p v-if="runStatus === 'FAILED' && !error && !busy" class="state-note">
            上次执行失败，可以调整需求后再次提交。
          </p>
        </div>
      </div>
      <ListLoadingOverlay :visible="readingConversation" :initial-delay="0" label="正在读取会话" />
      </div>
      <AiComposerDock
        v-model="composerValue"
        :awaiting-answer="waiting && Boolean(currentQuestionData) && !switching"
        :sending="busy"
        :disabled="
          switching ||
          store.historyLoading ||
          Boolean(store.activeId && !store.detail) ||
          (runStatus === 'RUNNING' && !watching)
        "
        :error="queryError"
        @submit="submit"
      >
        <template #question>
          <Transition name="question-dock" appear @before-leave="setQuestionInert($event, true)" @before-enter="setQuestionInert($event, false)">
            <div v-if="waiting && currentQuestionData && !switching" ref="questionDock" class="agent-question-dock" data-ai-question-dock>
              <AiClarificationForm
                :key="currentQuestionData.pauseId"
                ref="clarificationForm"
                v-model="clarificationDraft"
                :questions="currentQuestionData.questions"
                :busy="locked"
                @submit="run"
              />
            </div>
          </Transition>
        </template>
      </AiComposerDock>
    </main>
    <div
      v-if="wide && planOpen"
      class="panel-resizer"
      role="separator"
      tabindex="0"
      aria-label="调整计划栏宽度"
      aria-orientation="vertical"
      :aria-valuenow="planResize.width.value"
      :aria-valuemin="280"
      :aria-valuemax="416"
      @pointerdown="planResize.startResize"
      @keydown.left.prevent="planResize.nudge(16)"
      @keydown.right.prevent="planResize.nudge(-16)"
    />
    <AiPlanPanel
      v-if="wide"
      class="desktop-plan"
      :class="{ 'is-collapsed': !planOpen }"
      :artifact="displayedPlan"
      :loading="readingConversation && planOpen"
      :collapsed="!planOpen"
      @close="setPlanOpen(false)"
      @expand="setPlanOpen(true)"
    />
    <Dialog :open="planOpen && !wide" @update:open="planOpen = $event"
      ><DialogContent
        data-ai-panel="plan"
        class="ai-panel-dialog !fixed !left-auto !right-0 !top-0 !bottom-0 !m-0 !h-dvh !max-h-dvh !w-[min(100vw,420px)] !max-w-none !translate-x-0 !translate-y-0 !rounded-none !p-0 !gap-0"
        :show-close-button="false"
        ><DialogTitle class="sr-only">任务计划</DialogTitle
        ><DialogDescription class="sr-only">查看分析目标与规划步骤</DialogDescription
        ><AiPlanPanel
          :artifact="displayedPlan"
          :loading="readingConversation"
          @close="planOpen = false" /></DialogContent
    ></Dialog>
    <Dialog :open="mobileSidebarOpen" @update:open="mobileSidebarOpen = $event"
      ><DialogContent
        data-ai-panel="conversations"
        class="!fixed !left-0 !right-auto !top-0 !bottom-0 !m-0 !h-dvh !max-h-dvh !w-[min(86vw,300px)] !max-w-none !translate-x-0 !translate-y-0 !rounded-none !p-0 !gap-0"
        :show-close-button="false"
        ><DialogTitle class="sr-only">会话列表</DialogTitle
        ><DialogDescription class="sr-only">选择已有会话或创建新会话</DialogDescription
        ><AiConversationSidebar
          :conversations="store.conversations"
          :active-id="store.activeId"
          :loading="store.listLoading"
          :busy="creating || switching"
          :running-ids="runningConversationIds"
          :switching="switching"
          :collapsed="false"
          @create="createConversation"
          @select="openConversation"
          @rename="renameConversation"
          @remove="removeConversation"
          @toggle-collapse="mobileSidebarOpen = false" /></DialogContent
    ></Dialog>
  </section>
</template>

<style scoped>
.graph-workspace {
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  color: var(--foreground);
  background: var(--background);
  font-size: 14px;
}
.graph-workspace > .conversation-sidebar {
  width: var(--conversation-width);
  flex-shrink: 0;
  overflow: hidden;
  transition: width var(--motion-duration-slow) var(--motion-ease-standard);
}
.graph-workspace > .conversation-sidebar.is-collapsed {
  width: 52px;
}
.conversation-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: 56px minmax(0, 1fr) auto;
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.agent-question-dock {
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
  margin-bottom: 10px;
}
.question-dock-enter-active,
.question-dock-leave-active { transition: grid-template-rows 280ms cubic-bezier(0.38, 0, 0.24, 1), opacity 240ms cubic-bezier(0.38, 0, 0.24, 1), transform 280ms cubic-bezier(0.38, 0, 0.24, 1); }
.question-dock-enter-from,
.question-dock-leave-to { grid-template-rows: minmax(0, 0fr); opacity: 0; transform: translateY(6px); }
@media (prefers-reduced-motion: reduce) {
  .question-dock-enter-active, .question-dock-leave-active { transition: none; }
}
.question-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  color: var(--muted-foreground);
  font-size: 12px;
  text-align: left;
  border-radius: 6px;
  padding: 5px 0;
}
.question-status:hover { color: var(--foreground); }
.question-status:focus-visible { outline: 2px solid var(--ring); outline-offset: 4px; }
.question-status-action { display: inline-flex; align-items: center; gap: 4px; color: var(--primary); }
.workspace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  border-bottom: 1px solid var(--border);
  background: var(--card);
  padding: 0 24px;
}
.workspace-title,
.workspace-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
h1 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 500;
}
.workspace-title > svg {
  flex-shrink: 0;
}
.workspace-actions {
  flex-shrink: 0;
}
.task-status {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--muted-foreground);
  font-size: 12px;
}
.status-dot {
  width: 6px;
  height: 6px;
  background: var(--primary);
  border-radius: 50%;
}
.conversation-body {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-height: 0;
}
.conversation-scroll {
  overflow-y: auto;
  overscroll-behavior: contain;
  min-height: 0;
  padding: 24px 28px;
  scrollbar-width: thin;
}
.conversation-stream {
  display: grid;
  align-content: start;
  gap: 24px;
  max-width: 820px;
  min-width: 0;
  min-height: 100%;
  margin: 0 auto;
}
.welcome-state {
  align-self: center;
  padding: 56px 12px;
}
.welcome-symbol {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  color: var(--primary);
  margin-bottom: 24px;
}
.welcome-state h2 {
  font-size: 24px;
  line-height: 1.5;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.welcome-state > p {
  margin-top: 12px;
  color: var(--muted-foreground);
  font-size: 14px;
  line-height: 1.9;
}
.example-list {
  display: grid;
  max-width: 480px;
  margin-top: 28px;
}
.example-list button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 0;
  border-bottom: 1px solid var(--border);
  color: var(--foreground);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.example-list button:hover {
  color: var(--primary);
}
.desktop-plan {
  flex: 0 0 var(--plan-width);
  width: var(--plan-width);
  min-width: 0;
  overflow: hidden;
  transition:
    flex-basis var(--motion-duration-slow) var(--motion-ease-standard),
    width var(--motion-duration-slow) var(--motion-ease-standard);
}
.desktop-plan.is-collapsed {
  flex-basis: 52px;
  width: 52px;
}
.panel-resizer {
  position: relative;
  z-index: 1;
  flex: 0 0 0px;
  width: 0;
  cursor: col-resize;
  touch-action: none;
}
.panel-resizer::before {
  content: '';
  position: absolute;
  inset: 0 -4px;
}
.panel-resizer::after {
  content: '';
  position: absolute;
  inset: 0 -1px;
  background: var(--primary);
  opacity: 0;
  transition: opacity 150ms;
}
.panel-resizer:hover::after,
.panel-resizer:focus-visible::after {
  opacity: 0.65;
}
.panel-resizer:focus-visible {
  outline: none;
}
.graph-workspace.is-resizing,
.graph-workspace.is-resizing * {
  cursor: col-resize;
  user-select: none;
}
.graph-workspace.is-resizing > .conversation-sidebar,
.graph-workspace.is-resizing > .desktop-plan {
  transition: none;
}
.graph-workspace > .conversation-sidebar :deep(.sidebar-rail),
.graph-workspace > .conversation-sidebar :deep(.sidebar-create),
.graph-workspace > .conversation-sidebar :deep(.sidebar-list),
.desktop-plan :deep(.plan-rail),
.desktop-plan :deep(.plan-scroll),
.desktop-plan :deep(.plan-empty) {
  animation: ai-panel-reveal var(--motion-duration-base) ease-out both;
}
@keyframes ai-panel-reveal {
  from { opacity: 0; }
  to { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .graph-workspace > .conversation-sidebar,
  .desktop-plan {
    transition: none;
  }
  .graph-workspace :deep(.sidebar-rail),
  .graph-workspace :deep(.sidebar-create),
  .graph-workspace :deep(.sidebar-list),
  .desktop-plan :deep(.plan-rail),
  .desktop-plan :deep(.plan-scroll),
  .desktop-plan :deep(.plan-empty) {
    animation: none;
  }
}
.request-error,
.request-warning {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.8;
  background: var(--card);
}
.request-error {
  color: var(--destructive);
}
.request-error > svg {
  flex-shrink: 0;
  margin-top: 4px;
}
.request-error strong {
  font-weight: 500;
}
.request-error p {
  overflow-wrap: anywhere;
}
.request-warning {
  color: var(--muted-foreground);
}
.state-note {
  color: var(--muted-foreground);
  font-size: 13px;
  line-height: 1.8;
}
.older-button {
  justify-self: center;
}
@media (max-width: 640px) {
  .workspace-header {
    padding: 0 12px;
    gap: 8px;
  }
  .workspace-title {
    gap: 6px;
  }
  .workspace-title > svg {
    display: none;
  }
  .task-status {
    max-width: 94px;
    overflow: hidden;
    white-space: nowrap;
  }
  .conversation-scroll {
    padding: 24px 14px;
  }
  .welcome-state {
    padding: 32px 0;
  }
  .welcome-state h2 {
    font-size: 22px;
  }
}
</style>
