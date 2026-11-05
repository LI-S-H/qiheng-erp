<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import '@/styles/ai-pilot.css';
import { useElementBounding, useMediaQuery } from '@vueuse/core';
import { useRouter } from 'vue-router';
import {
  ArrowRight,
  MessageSquare,
  Home,
  LoaderCircle,
  Menu,
  TriangleAlert,
} from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { getApiErrorMessage } from '@/api/http';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import AiClarificationForm from '../clarification/AiClarificationForm.vue';
import AiComposerDock from '../components/AiComposerDock.vue';
import AiConversationSidebar from '../components/AiConversationSidebar.vue';
import AiMessageList from '../components/AiMessageList.vue';
import AiMessageNavigator from '../components/AiMessageNavigator.vue';
import { usePanelResize } from '../composables/use-panel-resize';
import { useConversationRuns, type ConversationRun, type LiveItem } from '../composables/use-conversation-runs';
import type { ClarificationDraft } from '../clarification/clarification-types';
import { getMainAgentRuntime, startMainAgentStream, submitMainAgentAnswer, watchMainAgentStream, type MainAgentEvent } from '../stream/main-agent-stream';
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
const answering = ref(false);
const error = runtimeField('error');
const warning = runtimeField('warning');
const querying = runtimeField('querying');
const watching = runtimeField('watching');
const creating = ref(false);
const switching = ref(false);
const steps = runtimeField('steps');
const pendingText = runtimeField('pendingText');
const liveText = runtimeField('liveText');
const liveItems = runtimeField('liveItems');
const liveRunId = runtimeField('runId');
const clarificationDraft = ref<ClarificationDraft>({ answers: {}, note: '' });
const clarificationForm = ref<InstanceType<typeof AiClarificationForm> | null>(null);
const questionDock = ref<HTMLElement | null>(null);
const scrollArea = ref<HTMLElement | null>(null);
const workspaceElement = ref<HTMLElement | null>(null);
const workspaceBounds = useElementBounding(workspaceElement);
/** 横向居中于会话列表与聊天区的整体，纵向保持窗口居中。 */
const conversationDialogStyle = computed(() => workspaceBounds.width.value > 0 ? {
  left: `${workspaceBounds.left.value + workspaceBounds.width.value / 2}px`,
  top: '50%',
  maxWidth: `${Math.max(0, Math.min(440, workspaceBounds.width.value - 32))}px`,
} : {});
const sidebarCollapsed = ref(false);
const mobileSidebarOpen = ref(false);
/** 仅在同账号切换期间显示旧内容；请求失败或账号切换立即丢弃，不能当作新会话数据。 */
const switchSnapshot = ref<{
  messages: AiConversationMessage[];
  welcome: boolean;
  pendingText: string;
  liveText: string;
  liveItems: LiveItem[];
  recentAnswers: ConversationRun['recentAnswers'];
  thinking: boolean;
} | null>(null);
/** 未落库的暂停轮数据：澄清卡在库内出现前先行渲染表单，字段与落库卡 data 同构。 */
const pendingClarification = runtimeField('pendingClarification');
const small = useMediaQuery('(max-width: 900px)');
const phone = useMediaQuery('(max-width: 640px)');
const conversationResize = usePanelResize({
  storageKey: 'erp-ai-conversation-width', min: 200, max: 320, initial: 224,
});
const workspaceStyle = computed(() => ({
  '--conversation-width': `${conversationResize.width.value}px`,
}));
let requestEpoch = 0;
let disposed = false;
const refreshes = new Map<string, Promise<void>>();

const busy = computed(() => creating.value || ((querying.value || watching.value) && !waiting.value));
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
const runStatus = computed(() => currentRun.value.runStatus || 'IDLE');
const currentQuestionData = computed(() => runStatus.value === 'WAITING_USER' ? pendingClarification.value : null);
const waiting = computed(() => Boolean(currentQuestionData.value));
const readingConversation = computed(() => switching.value || (store.historyLoading && !busy.value));
const displayedMessages = computed(() => {
  const messages = switching.value && switchSnapshot.value ? switchSnapshot.value.messages : store.messages;
  if (switching.value) return messages;
  if (!liveRunId.value || currentRun.value.snapshotTruncated) return messages;
  // 当前轮的流式内容尚在展示时，不重复渲染已提前落库的过程文字。
  return messages.filter(message => message.requestId !== liveRunId.value ||
    !((liveItems.value.length > 0 && (message.kind === 'ASSISTANT_STEP' || message.kind === 'ASSISTANT_FINAL')) ||
      (waiting.value && message.kind === 'TOOL_CALL')));
});
const displayedPendingText = computed(() => liveRunId.value && displayedMessages.value.some(
  message => message.requestId === liveRunId.value && message.kind === 'USER_INPUT',
) ? '' : pendingText.value);
const showWelcome = computed(() => switching.value && switchSnapshot.value
  ? switchSnapshot.value.welcome
  : !store.messages.length && !store.historyLoading && !pendingText.value && !busy.value && !error.value);
const statusLabel = computed(() => {
  if (busy.value) {
    return steps.value.find((step) => step.status === 'running')?.label || '正在思考';
  }
  return (
    {
      WAITING_USER: '等待补充',
      FAILED: '执行失败',
      INTERRUPTED: '执行已中断',
      COMPLETED: '准备就绪',
      IDLE: '准备就绪',
      RUNNING: '等待连接',
    } as Record<string, string>
  )[runStatus.value];
});
// 等待模型开始输出时，在当前工作过程内显示提示，不另设底部状态栏。
const thinking = computed(() => {
  if (!busy.value || steps.value.some(step => step.status === 'running')) return false;
  const last = liveItems.value[liveItems.value.length - 1];
  return !last || last.type === 'tool';
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
  questionRequestId: string | null;
  fromNew: boolean;
}
function clearSubmittedDraft(userId: string | undefined, id: string, submission: SubmittedDraft) {
  if (submission.questionRequestId) {
    storageSet(scopedDraftKey(userId, id, 'clarification', submission.questionRequestId), null);
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
  if (!store.detail) return;
  const value = storageRead(draftKey('inflight'));
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  const item = value as Partial<SubmittedDraft>;
  if (typeof item.text !== 'string' || typeof item.afterSeq !== 'string' || !/^\d+$/.test(item.afterSeq) ||
    !(item.questionRequestId === null || typeof item.questionRequestId === 'string') || typeof item.fromNew !== 'boolean') return;
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
  if (!data?.questionRequestId) {
    return;
  }
  const cached = storageRead(draftKey('clarification', data.questionRequestId));
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
      const selectedOptions = Array.isArray(entry.selectedOptions)
        ? [...new Set(entry.selectedOptions.filter((option): option is string =>
            typeof option === 'string' && question.options.includes(option)))]
        : [];
      safe.answers[question.id] = { selected: entry.selected, custom: entry.custom, selectedOptions };
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
    if (!switching.value && data?.questionRequestId && auth.user) {
      storageSet(draftKey('clarification', data.questionRequestId), value);
    }
  },
  { deep: true },
);
watch(() => currentQuestionData.value?.questionRequestId, restoreClarification);

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
function handleMainEvent(state: ConversationRun, valid: () => boolean, id: string, event: MainAgentEvent) {
  if (!valid()) return;
  const data = event.data;
  const sequence = typeof data.eventSeq === 'number' ? data.eventSeq : null;
  if (event.event !== 'run_snapshot' && sequence !== null && sequence <= state.eventSeq) return;
  if (sequence !== null) state.eventSeq = sequence;
  switch (event.event) {
    case 'run_snapshot': {
      if (typeof data.runId !== 'string' || !Array.isArray(data.items)) throw new Error('运行快照格式错误');
      state.runId = data.runId;
      state.snapshotTruncated = data.snapshotTruncated === true;
      state.warning = state.snapshotTruncated ? '运行过程缓存不完整，已保留已保存的历史消息。' : '';
      state.liveItems = data.items as LiveItem[];
      state.completedTextIds = new Set(state.liveItems.filter(item => item.type === 'text' && item.completed).map(item => item.id));
      state.liveText = state.liveItems.filter(item => item.type === 'text').map(item => item.text).join('\n\n');
      state.steps = state.liveItems.filter(item => item.type === 'tool').map(item => ({ node: item.id, label: item.label, status: item.status, durationMs: item.durationMs }));
      if (data.status === 'RUNNING' || data.status === 'WAITING_USER' || data.status === 'COMPLETED' || data.status === 'FAILED') state.runStatus = data.status;
      const question = data.question as ConversationRun['pendingClarification'];
      state.pendingClarification = data.status === 'WAITING_USER' && question?.questionRequestId && Array.isArray(question.questions) ? question : null;
      break;
    }
    case 'run_started':
      state.runStatus = 'RUNNING'; state.runId = String(data.runId || '');
      // 服务端在发出 run_started 前已保存输入；以历史记录替换临时提交气泡。
      if (store.activeId === id && state.pendingText && !switching.value) void reload(id, requestEpoch, auth.user?.userId);
      break;
    case 'text_delta': {
      if (typeof data.delta !== 'string' || typeof data.messageId !== 'string') throw new Error('文本事件格式错误');
      if (state.completedTextIds.has(data.messageId)) break;
      if (state.liveMessageId && state.liveMessageId !== data.messageId) state.liveText += '\n\n';
      state.liveMessageId = data.messageId;
      state.liveText += data.delta;
      const last = state.liveItems[state.liveItems.length - 1];
      if (last?.type === 'text' && last.id === data.messageId) last.text += data.delta;
      else state.liveItems.push({ type: 'text', id: data.messageId, text: data.delta });
      break;
    }
    case 'text_completed': {
      if (typeof data.text !== 'string' || typeof data.messageId !== 'string') throw new Error('完整文本事件格式错误');
      // 完整消息校正同一段流式内容，避免尾部分片缺失或重复追加。
      const item = state.liveItems.find(entry => entry.type === 'text' && entry.id === data.messageId);
      if (item?.type === 'text') item.text = data.text;
      else state.liveItems.push({ type: 'text', id: data.messageId, text: data.text });
      state.completedTextIds.add(data.messageId);
      state.liveMessageId = data.messageId;
      state.liveText = state.liveItems.filter(entry => entry.type === 'text').map(entry => entry.text).join('\n\n');
      break;
    }
    case 'tool_started':
      state.steps.push({ node: String(data.toolCallId), label: String(data.label || '正在处理当前步骤'), status: 'running', durationMs: null });
      state.liveItems.push({ type: 'tool', id: String(data.toolCallId), label: String(data.label || '正在处理当前步骤'), durationMs: null, status: 'running' });
      break;
    case 'tool_finished': {
      const step = state.steps.find(item => item.node === String(data.toolCallId));
      if (step) {
        if (typeof data.label === 'string') step.label = data.label;
        step.status = data.status === 'failed' ? 'failed' : 'done';
        step.durationMs = typeof data.durationMs === 'number' ? data.durationMs : null;
      }
      const tool = state.liveItems.find(item => item.type === 'tool' && item.id === String(data.toolCallId));
      if (tool?.type === 'tool') {
        if (typeof data.label === 'string') tool.label = data.label;
        tool.status = data.status === 'failed' ? 'failed' : 'done';
        tool.durationMs = typeof data.durationMs === 'number' ? data.durationMs : null;
      }
      break;
    }
    case 'question': {
      if (typeof data.questionRequestId !== 'string' || !Array.isArray(data.questions)) throw new Error('追问事件格式错误');
      state.pendingClarification = { questionRequestId: data.questionRequestId, toolCallId: typeof data.toolCallId === 'string' ? data.toolCallId : undefined, questions: data.questions as import('../clarification/clarification-types').ClarificationQuestion[] };
      const callId = typeof data.toolCallId === 'string' ? data.toolCallId : data.questionRequestId;
      if (!state.liveItems.some(item => item.type === 'clarification' && item.id === callId)) {
        state.liveItems.push({ type: 'clarification', id: callId, questions: state.pendingClarification.questions });
      }
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.(); state.settleSuccess = null;
      if (store.activeId === id && state.pendingText && !switching.value) void reload(id, requestEpoch, auth.user?.userId);
      break;
    }
    case 'question_answered':
      if (state.pendingClarification?.questionRequestId === data.questionRequestId) {
        state.pendingClarification = null;
        state.runStatus = 'RUNNING';
      }
      break;
    case 'run_completed': state.runStatus = 'COMPLETED'; state.settleSuccess?.(); state.settleSuccess = null; break;
    case 'run_failed': state.runStatus = 'FAILED'; state.error = String(data.message || '任务执行失败'); state.steps = []; break;
  }
}
async function scrollToLatest() {
  await nextTick();
  if (scrollArea.value) {
    scrollArea.value.scrollTop = scrollArea.value.scrollHeight;
  }
}
// 新错误位于对话末尾，出现时滚动到提示，避免被历史消息遮住。
watch(error, value => {
  if (value && !switching.value) {
    void scrollToLatest();
  }
});
/** 库内澄清卡片出现后，实时暂停数据交回流水渲染，避免两个表单同时占用插槽。 */
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
    await store.loadConversation(id, { silent: true });
    if (isCurrent(epoch, userId, id)) {
      const state = runs.get(id);
      // 只有确认当前轮输入已进入历史，才移除“正在提交”的临时气泡。
      if (state.pendingText && state.runId && store.messages.some(message => message.requestId === state.runId
          && message.kind === 'USER_INPUT' && message.content.type === 'TEXT'
          && message.content.data.text === state.pendingText)) {
        state.pendingText = '';
      }
      if (reconcileSubmittedDraft(id)) {
        state.error = '';
        state.pendingText = '';
      }
      // 本轮结束后历史已经接管展示，避免临时流和数据库消息重复。
      if (!state.streaming) {
        state.liveText = '';
        state.liveItems = [];
        state.completedTextIds.clear();
        state.steps = [];
      }
      state.refreshNeeded = false;
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

/** 接入后端仍在执行的本轮任务；不会再次发送用户消息。 */
async function reconnectRun(id: string, epoch: number, userId: string | undefined) {
  const state = runs.get(id);
  if (state.streaming) return;
  const runtime = await getMainAgentRuntime(id);
  if (!isCurrent(epoch, userId, id) || state.streaming || !runtime) return;
  if (runtime.status === 'INTERRUPTED') {
    state.runStatus = 'INTERRUPTED';
    return;
  }
  if (runtime.status !== 'RUNNING' && runtime.status !== 'WAITING_USER') {
    if (runtime.status === 'COMPLETED' || runtime.status === 'FAILED') await reload(id, epoch, userId);
    return;
  }
  const connection = runs.connect(id, 'watching');
  if (!connection) return;
  const { controller, version } = connection;
  const token = auth.token;
  const valid = () => !disposed && auth.user?.userId === userId && auth.token === token
    && runs.states.get(id) === state && state.version === version;
  state.error = '';
  state.runStatus = 'RUNNING';
  let snapshotReady = () => {};
  const firstSnapshot = new Promise<void>(resolve => { snapshotReady = resolve; });
  const snapshotTimer = window.setTimeout(() => {
    if (valid()) {
      state.warning = '运行快照读取超时，请重新进入会话重试。';
      state.runStatus = 'INTERRUPTED';
      controller.abort();
    }
    snapshotReady();
  }, 15000);
  // 订阅在后台持续，切换会话不会终止其他会话的执行。
  void (async () => {
    try {
      await watchMainAgentStream(id, event => {
        if (controller.signal.aborted) {
          return;
        }
        handleMainEvent(state, valid, id, event);
        if (event.event === 'run_snapshot') {
          window.clearTimeout(snapshotTimer);
          snapshotReady();
        }
      }, controller.signal);
    } catch (cause) {
      if (valid() && !controller.signal.aborted) {
        state.error = getApiErrorMessage(cause) || '连接已中断，请重新打开会话';
        state.runStatus = 'INTERRUPTED';
      }
    } finally {
      window.clearTimeout(snapshotTimer);
      snapshotReady();
      if (!valid()) return;
      state.streaming = false;
      state.refreshNeeded = true;
      if (store.activeId === id && !switching.value) await reload(id, requestEpoch, userId);
      if (valid()) {
        stopSteps(state, state.error ? 'failed' : 'interrupted');
        runs.disconnect(id, version);
        void store.loadConversations().catch(() => undefined);
      }
    }
  })();
  // 切换等待首个运行快照，但不等待后台智能体完成任务。
  await firstSnapshot;
}

async function run(answer?: string) {
  if (answer && waiting.value && store.activeId && currentQuestionData.value) {
    if (answering.value) return;
    answering.value = true;
    const question = currentQuestionData.value;
    const conversationId = store.activeId;
    const answerRun = runs.get(conversationId);
    const previousError = answerRun.error;
    try {
      const answers = question.questions.map(item => {
        const value = clarificationDraft.value.answers[item.id];
        const selected = value?.selected && value.selected !== '__custom_answer__' ? [value.selected] : [];
        return { questionId: item.id, selectedOptions: item.type === 'MULTIPLE_CHOICE' ? (value?.selectedOptions || []) : selected, customText: value?.custom?.trim() || '' };
      });
      const note = clarificationDraft.value.note.trim();
      const answerText = answers.map(item => [...item.selectedOptions, item.customText].filter(Boolean).join('、')).filter(Boolean);
      if (note) answerText.push(note);
      await submitMainAgentAnswer(conversationId, question.questionRequestId, answers, note);
      if (answerRun.error === previousError) answerRun.error = '';
      const callId = question.toolCallId || question.questionRequestId;
      answerRun.recentAnswers[callId] = { callId, questions: question.questions, answer: answerText.join('；') || '已补充信息', questionAnswers: Object.fromEntries(answers.map(item => [item.questionId, [...item.selectedOptions, item.customText].filter(Boolean).join('、')])), note };
      // 运行流可能先于提交响应结束，不能覆盖新的终态或其他追问。
      if (answerRun.runStatus === 'WAITING_USER'
          && answerRun.pendingClarification?.questionRequestId === question.questionRequestId) {
        answerRun.pendingClarification = null;
        answerRun.runStatus = 'RUNNING';
      }
    } catch (cause) { answerRun.error = getApiErrorMessage(cause) || '提交回答失败，请重试'; }
    finally { answering.value = false; }
    return;
  }
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
    storageSet(scopedDraftKey(userId, null, 'activeConversation'), id);
    const connection = runs.connect(id, 'querying');
    if (!connection) return;
    const { state, controller: currentController, version } = connection;
    const valid = () => !disposed && auth.user?.userId === userId && auth.token === authToken &&
      runs.states.get(id!) === state && state.version === version;
    const submission: SubmittedDraft = {
      text, afterSeq: store.messages[store.messages.length - 1]?.seq || '0', questionRequestId: null, fromNew,
    };
    state.pendingText = text;
    state.error = '';
    state.warning = '';
    state.steps = [];
    state.runStatus = 'RUNNING';
    // 清理范围固定在发送时的账号和会话；后台 A 完成不能修改 B 的输入。
    state.settleSuccess = () => clearSubmittedDraft(userId, id!, submission);
    storageSet(draftKey('inflight'), submission);
    store.touchActivity(id);
    await scrollToLatest();
    if (!valid()) return;
    state.liveText = '';
    state.liveMessageId = '';
    state.liveItems = [];
    state.completedTextIds.clear();
    try {
      await startMainAgentStream(id, text, event => handleMainEvent(state, valid, id!, event), currentController.signal);
    } catch (cause) {
      if (valid() && !currentController.signal.aborted) {
        state.error = getApiErrorMessage(cause) || '请求失败，请重试';
        // 连接断开无法确认后台终态，允许用户继续操作但不宣称任务已停止。
        state.runStatus = 'INTERRUPTED';
        stopSteps(state, 'failed');
      }
    } finally {
      if (valid()) {
        const reconnect = Boolean(state.error) && !currentController.signal.aborted;
        state.streaming = false;
        state.refreshNeeded = true;
        if (store.activeId === id && !switching.value) await reload(id, requestEpoch, userId);
        if (valid()) {
          stopSteps(state, state.error ? 'failed' : 'interrupted');
          runs.disconnect(id, version);
          void store.loadConversations().catch(() => undefined);
          if (reconnect && store.activeId === id && !switching.value) {
            await reconnectRun(id, requestEpoch, userId).catch(() => undefined);
          }
          if (store.activeId === id && !switching.value) {
            await scrollToLatest();
          }
        }
      }
    }
  } catch (cause) {
    if (isCurrent(epoch, userId, id || undefined)) error.value = getApiErrorMessage(cause) || '会话创建失败';
  }
}
/** 切换时保留旧会话的完整可见内容，直到新内容准备好。 */
function captureSwitchSnapshot() {
  return {
    messages: [...displayedMessages.value],
    welcome: showWelcome.value,
    pendingText: displayedPendingText.value,
    liveText: liveText.value,
    liveItems: liveItems.value.map(item => ({ ...item })),
    recentAnswers: { ...currentRun.value.recentAnswers },
    thinking: thinking.value,
  };
}

/** 请求很快结束时仍保持统一的过渡时间，避免内容闪现。 */
async function waitForSwitchTransition(started: number) {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    return;
  }
  const remaining = 300 - (performance.now() - started);
  if (remaining > 0) {
    await new Promise(resolve => window.setTimeout(resolve, remaining));
  }
}

async function openConversation(id: string, force = false) {
  if (creating.value || switching.value || (!force && id === store.activeId && store.detail)) {
    return;
  }
  switchSnapshot.value = captureSwitchSnapshot();
  cancelTransient();
  switching.value = true;
  const switchStarted = performance.now();
  // 面板宽度与开合由用户控制；切换只更新内容，不让工作区先收起再展开。
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  try {
    const loaded = await store.loadConversation(id);
    if (!loaded || !isCurrent(epoch, userId, id)) {
      return;
    }
    storageSet(scopedDraftKey(userId, null, 'activeConversation'), id);
    const state = runs.get(id);
    // 切换读取期间后台可能刚好收尾；再取一次最新流水，避免用旧 RUNNING 快照覆盖终态。
    if (state.refreshNeeded && !state.streaming) await reload(id, epoch, userId);
    if (!isCurrent(epoch, userId, id)) return;
    try {
      await reconnectRun(id, epoch, userId);
    } catch (cause) {
      if (isCurrent(epoch, userId, id)) warning.value = getApiErrorMessage(cause) || '运行状态暂时无法读取';
    }
    if (!isCurrent(epoch, userId, id)) return;
    if (reconcileSubmittedDraft(id)) state.error = '';
    restoreQuery();
    restoreClarification();
    mobileSidebarOpen.value = false;
  } catch (cause) {
    if (isCurrent(epoch, userId, id)) {
      error.value = getApiErrorMessage(cause) || '会话读取失败，请重试';
    }
  } finally {
    await waitForSwitchTransition(switchStarted);
    if (isCurrent(epoch, userId)) {
      switching.value = false;
      switchSnapshot.value = null;
      await scrollToLatest();
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
  switchSnapshot.value = captureSwitchSnapshot();
  cancelTransient();
  switching.value = true;
  const switchStarted = performance.now();
  creating.value = true;
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  try {
    const created = await store.createConversation();
    if (isCurrent(epoch, userId)) {
      storageSet(scopedDraftKey(userId, null, 'activeConversation'), created.conversationId);
      query.value = '';
      clarificationDraft.value = { answers: {}, note: '' };
      mobileSidebarOpen.value = false;
    }
  } catch (cause) {
    if (isCurrent(epoch, userId)) {
      error.value = getApiErrorMessage(cause) || '会话创建失败';
    }
  } finally {
    await waitForSwitchTransition(switchStarted);
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
      const remembered = storageRead(scopedDraftKey(userId, null, 'activeConversation'));
      if (typeof remembered === 'string' && remembered) {
        switching.value = false;
        await openConversation(remembered);
        if (!isCurrent(epoch, userId) || (store.activeId === remembered && store.detail)) return;
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
  <section ref="workspaceElement" class="graph-workspace ai-pilot-workspace" :style="workspaceStyle" :class="{ 'is-resizing': conversationResize.dragging.value }" data-testid="ai-graph-workspace">
    <AiConversationSidebar
      v-if="!small"
      :conversations="store.conversations"
      :active-id="store.activeId"
      :loading="store.listLoading"
      :busy="creating || switching"
      :running-ids="runningConversationIds"
      :switching="switching"
      :collapsed="sidebarCollapsed"
      :dialog-style="conversationDialogStyle"
      :has-more="store.hasMoreConversations"
      :loading-more="store.moreListLoading"
      :load-more-error="store.moreListError"
      :load-more-revision="store.moreListRevision"
      @create="createConversation"
      @select="openConversation"
      @rename="renameConversation"
      @remove="removeConversation"
      @toggle-collapse="toggleSidebar"
      @load-more="store.loadMoreConversations()"
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
          <span class="task-status" :class="{ 'agent-running-status': busy }" role="status"
            ><LoaderCircle v-if="busy" class="size-3.5 animate-spin" /><span
              v-else
              class="status-dot"
            />{{ statusLabel }}</span>
        </div>
      </header>
      <div class="conversation-body" :aria-busy="readingConversation" data-ai-conversation-region>
      <AiMessageNavigator :messages="displayedMessages" :scroll-container="scrollArea" :inert="readingConversation || undefined" />
      <div ref="scrollArea" class="conversation-scroll" :inert="readingConversation || undefined">
        <div class="conversation-stream" :class="{ 'conversation-enter': !switching, 'conversation-switching': switching }">
          <div v-if="warning && !switching" class="request-warning" role="status">{{ warning }}</div>
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
            :loading="!switching && store.historyLoading"
            :pending-text="switching ? (switchSnapshot?.pendingText || '') : displayedPendingText"
            :live-text="switching ? (switchSnapshot?.liveText || '') : liveText"
            :live-items="switching ? (switchSnapshot?.liveItems || []) : liveItems"
            :recent-answers="switching ? (switchSnapshot?.recentAnswers || {}) : currentRun.recentAnswers"
            :thinking="switching ? (switchSnapshot?.thinking || false) : thinking"
            :active-question-id="undefined"
            :user-id="auth.user?.userId"
            :conversation-id="store.activeId || undefined"
            >
            <template #clarification>
              <button type="button" class="question-status" data-testid="clarification-pending-status" aria-label="继续回答补充问题" :disabled="switching" @click="focusQuestion">
                <MessageSquare class="size-3.5" aria-hidden="true" />
                <span>等待你补充 {{ currentQuestionData?.questions.length }} 项信息</span>
                <span class="question-status-action">继续回答<ArrowRight class="size-3" aria-hidden="true" /></span>
              </button>
            </template>
          </AiMessageList>
          <p v-if="!switching && runStatus === 'INTERRUPTED' && !busy" class="state-note">
            上次执行已中断。可以重新描述需求后再试。
          </p>
          <p v-if="!switching && runStatus === 'FAILED' && !error && !busy" class="state-note">
            上次执行失败，可以调整需求后再次提交。
          </p>
          <div v-if="error && !switching" class="request-error" role="alert" data-testid="graph-request-error">
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
        </div>
      </div>
      </div>
      <AiComposerDock
        v-model="composerValue"
        :awaiting-answer="waiting && Boolean(currentQuestionData) && !switching"
        :sending="answering || (busy && !waiting)"
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
                :key="currentQuestionData.questionRequestId"
                ref="clarificationForm"
                v-model="clarificationDraft"
                :questions="currentQuestionData.questions"
                :busy="answering || (locked && !waiting)"
                @submit="run"
              />
            </div>
          </Transition>
        </template>
      </AiComposerDock>
    </main>
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
          :dialog-style="conversationDialogStyle"
          :has-more="store.hasMoreConversations"
          :loading-more="store.moreListLoading"
          :load-more-error="store.moreListError"
          :load-more-revision="store.moreListRevision"
          @create="createConversation"
          @select="openConversation"
          @rename="renameConversation"
          @remove="removeConversation"
          @load-more="store.loadMoreConversations()"
          @toggle-collapse="mobileSidebarOpen = false" /></DialogContent
    ></Dialog>
  </section>
</template>

<style scoped>
.conversation-enter { animation: conversation-enter 200ms ease-out; }
.conversation-switching { opacity: .4; transition: opacity 160ms ease; }
@keyframes conversation-enter { from { opacity: .4; } to { opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .conversation-enter, .conversation-switching { animation: none; transition: none; } }
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
.graph-workspace.is-resizing > .conversation-sidebar {
  transition: none;
}
.graph-workspace > .conversation-sidebar :deep(.sidebar-rail),
.graph-workspace > .conversation-sidebar :deep(.sidebar-create),
.graph-workspace > .conversation-sidebar :deep(.sidebar-list) {
  animation: ai-panel-reveal var(--motion-duration-base) ease-out both;
}
@keyframes ai-panel-reveal {
  from { opacity: 0; }
  to { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .graph-workspace > .conversation-sidebar {
    transition: none;
  }
  .graph-workspace :deep(.sidebar-rail),
  .graph-workspace :deep(.sidebar-create),
  .graph-workspace :deep(.sidebar-list) {
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

<style scoped>
.agent-running-status { animation: agent-status-flow 1.8s ease-in-out infinite; }
@keyframes agent-status-flow { 0%, 100% { opacity: .6; } 50% { opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .agent-running-status { animation: none; } }
</style>
