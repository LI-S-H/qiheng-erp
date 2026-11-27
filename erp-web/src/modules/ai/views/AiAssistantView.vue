<script setup lang="ts">
import { computed, markRaw, nextTick, onBeforeUnmount, onScopeDispose, reactive, ref, watch } from 'vue';
import '@/styles/ai-pilot.css';
import { useElementBounding, useMediaQuery } from '@vueuse/core';
import { useRoute, useRouter } from 'vue-router';
import { toast } from 'vue-sonner';
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
import { Toaster } from '@/components/ui/sonner';
import { getApiErrorMessage } from '@/api/http';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import AiClarificationForm from '../clarification/AiClarificationForm.vue';
import AiAnalysisPanel from '../components/AiAnalysisPanel.vue';
import AiReportProcessPanel from '../components/AiReportProcessPanel.vue';
import AiRunNoticeToast from '../components/AiRunNoticeToast.vue';
import { getAiConversationReports } from '../api';
import { normalizeReportCard, normalizeReportStep, type AiReportCard } from '../contracts/report-contract';
import AiComposerDock from '../components/AiComposerDock.vue';
import AiConversationSidebar from '../components/AiConversationSidebar.vue';
import AiMessageList from '../components/AiMessageList.vue';
import AiMessageNavigator from '../components/AiMessageNavigator.vue';
import AiPurchaseDraftDock from '../drafts/AiPurchaseDraftDock.vue';
import PurchaseDraftDialog from '../drafts/PurchaseDraftDialog.vue';
import AiPurchaseActionDock from '../drafts/AiPurchaseActionDock.vue';
import PurchaseActionDialog from '../drafts/PurchaseActionDialog.vue';
import AiSalesDraftDock from '../drafts/AiSalesDraftDock.vue';
import SalesDraftDialog from '../drafts/SalesDraftDialog.vue';
import AiSalesActionDock from '../drafts/AiSalesActionDock.vue';
import SalesActionDialog from '../drafts/SalesActionDialog.vue';
import AiReturnDraftDock from '../drafts/AiReturnDraftDock.vue';
import ReturnDraftDialog from '../drafts/ReturnDraftDialog.vue';
import AiReturnActionDock from '../drafts/AiReturnActionDock.vue';
import ReturnActionDialog from '../drafts/ReturnActionDialog.vue';
import { parsePurchaseDraftRequest, type PurchaseDraftRequest } from '../drafts/purchase-draft-types';
import { parsePurchaseActionRequest, type PurchaseActionRequest } from '../drafts/purchase-action';
import { parseSalesDraftRequest, type SalesDraftRequest } from '../drafts/sales-draft-types';
import { parseSalesActionRequest, type SalesActionRequest } from '../drafts/sales-action';
import {
  parseReturnActionRequest,
  parseReturnDraftRequest,
  type ReturnActionRequest,
  type ReturnDraftRequest,
} from '../drafts/return-draft-types';
import { usePanelResize } from '../composables/use-panel-resize';
import { clearFailureHold, holdTransientFailure, useConversationRuns, type ConversationRun, type LiveItem, type SnapshotItem } from '../composables/use-conversation-runs';
import type { ClarificationDraft, ClarificationQuestion } from '../clarification/clarification-types';
import { getMainAgentRuntime, startMainAgentStream, submitMainAgentAnswer, watchMainAgentStream, type MainAgentEvent } from '../stream/main-agent-stream';
import { useAiConversationStore } from '../stores/aiConversationStore';
import type { AiAnalysisBlock, AiConversationMessage } from '../types';

const router = useRouter();
const route = useRoute();
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
/** 会话读取失败回退提示；此时可能还没有 activeId，不能写运行时字段。 */
const conversationNotice = ref('');
const conversationLoadFailed = ref(false);
/** AI 提醒使用独立 toaster，避免和全局提示互相污染。 */
const AI_NOTICE_TOASTER_ID = 'ai-run-notices';
const AiRunNoticeComponent = markRaw(AiRunNoticeToast);
/** 会话维度已登记的待处理提醒，用于切到该会话或状态结束时清理。 */
const pendingNoticeIds = new Map<string, Set<string>>();
const activeNoticeIds = new Map<string, Set<string>>();
const querying = runtimeField('querying');
const watching = runtimeField('watching');
const creating = ref(false);
const switching = ref(false);
const steps = runtimeField('steps');
const pendingText = runtimeField('pendingText');
const liveText = runtimeField('liveText');
const liveItems = runtimeField('liveItems');
const liveRunId = runtimeField('runId');
const liveAnalyses = runtimeField('analyses');
const retryAt = runtimeField('retryAt');
const nowTick = ref(Date.now());
let retryTimer: number | null = null;
/** 剩余退避秒数；为 0 表示可以再次发送。 */
const retryAfter = computed(() => Math.max(0, Math.ceil((retryAt.value - nowTick.value) / 1000)));
const backingOff = computed(() => retryAfter.value > 0);
// 退避期间每秒刷新倒计时，结束后自动停止计时，避免空转。
watch(backingOff, active => {
  if (active && retryTimer === null) {
    retryTimer = window.setInterval(() => {
      nowTick.value = Date.now();
    }, 1000);
  } else if (!active && retryTimer !== null) {
    window.clearInterval(retryTimer);
    retryTimer = null;
  }
}, { immediate: true });
onScopeDispose(() => {
  if (retryTimer !== null) {
    window.clearInterval(retryTimer);
  }
});
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
  analyses: Record<string, AiAnalysisBlock>;
  reportCards: AiReportCard[];
  reportProcesses: ConversationRun['reportProcesses'];
  reportRunId: string;
  finalMessageId: string;
  runTerminal: boolean;
  recentAnswers: ConversationRun['recentAnswers'];
  thinking: boolean;
} | null>(null);
/** 未落库的暂停轮数据：澄清卡在库内出现前先行渲染表单，字段与落库卡 data 同构。 */
const pendingClarification = runtimeField('pendingClarification');
/** 等待用户处置的采购草稿；与追问互斥，同一时刻只会有一份。 */
const pendingDraft = runtimeField('pendingDraft');
const draftDialogOpen = ref(false);

/** 把草稿事件收敛成可校验的结构；格式异常时按缺少草稿处理。 */
function pendingDraftFrom(value: unknown): PurchaseDraftRequest | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  try {
    return parsePurchaseDraftRequest(value as Record<string, unknown>);
  } catch {
    return null;
  }
}

/** 打开草稿确认页；必须点开详情后才能处置，避免不看内容直接确认。 */
function openDraftDialog() {
  if (!pendingDraft.value) return;
  draftDialogOpen.value = true;
}

/** 处置完成后收起确认页，并接上采购单的恢复运行。 */
function handleDraftSettled(context: { conversationId: string; callId: string }) {
  dismissPendingNotice(context.conversationId, context.callId);
  void finishOrderTransition('draft', context, 'PURCHASE');
}

/** 等待用户确认的提交或取消动作。 */
const pendingAction = runtimeField('pendingAction');
const actionDialogOpen = ref(false);

/** 把动作事件收敛成可校验的结构；格式异常时按缺少动作处理。 */
function pendingActionFrom(value: unknown): PurchaseActionRequest | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  try {
    return parsePurchaseActionRequest(value as Record<string, unknown>);
  } catch {
    return null;
  }
}

/** 打开动作确认页；必须点开详情后才能提交或取消。 */
function openActionDialog() {
  if (!pendingAction.value) return;
  actionDialogOpen.value = true;
}

/** 动作处置完成后收起确认页，并接上采购单的恢复运行。 */
function handleActionSettled(context: { conversationId: string; callId: string }) {
  dismissPendingNotice(context.conversationId, context.callId);
  void finishOrderTransition('action', context, 'PURCHASE');
}
/** 等待用户处置的销售草稿；与采购草稿互斥，同一时刻只会有一份。 */
const pendingSalesDraft = runtimeField('pendingSalesDraft');
const salesDraftDialogOpen = ref(false);
const closingOrder = ref<{ id: string; callId: string } | null>(null);

/** 把销售草稿事件收敛成可校验的结构；格式异常时按缺少草稿处理。 */
function pendingSalesDraftFrom(value: unknown): SalesDraftRequest | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  const item = value as Record<string, unknown>;
  // 旧事件结构：draftRequestId + draft
  if (typeof item.draftRequestId === 'string' && item.draft) {
    try {
      return parseSalesDraftRequest(item);
    } catch {
      return null;
    }
  }
  // 新事件结构：callId + action + arguments（与 ai_message 的 TOOL_CALL 内容一致）
  if (typeof item.callId === 'string' && typeof item.arguments === 'string') {
    try {
      const parsed = JSON.parse(item.arguments);
      const draft = parsed?.request && typeof parsed.request === 'object' ? parsed.request : parsed;
      return {
        draftRequestId: item.callId,
        action: item.action === 'UPDATE' ? 'UPDATE' : 'CREATE',
        draft,
      };
    } catch {
      return null;
    }
  }
  return null;
}

/** 打开销售草稿确认页；必须点开详情后才能处置。 */
function openSalesDraftDialog() {
  if (!pendingSalesDraft.value || closingOrder.value) return;
  salesDraftDialogOpen.value = true;
}

/** 打开历史里的待确认草稿：参数来自工具调用记录，确认仍走原 resolve 接口。 */
function openOrderTool(data: {
  callId: string;
  action?: string;
  arguments?: string;
  orderType?: 'SALES' | 'PURCHASE' | 'RETURN';
  returnType?: 'SALES_RETURN' | 'PURCHASE_RETURN';
  salesOrderId?: string;
  purchaseOrderId?: string;
  returnOrderId?: string;
  orderNo?: string;
  reason?: string;
}) {
  if (closingOrder.value) return;
  if (data.orderType === 'RETURN') {
    if (data.action === 'SUBMIT' || data.action === 'CANCEL') {
      pendingReturnAction.value = {
        actionRequestId: data.callId,
        toolCallId: data.callId,
        action: data.action as 'SUBMIT' | 'CANCEL',
        returnType: data.returnType,
        returnOrderId: data.returnOrderId || '',
        orderNo: data.orderNo || '',
        reason: data.reason || '',
      };
      returnActionDialogOpen.value = true;
      return;
    }
    try {
      pendingReturnDraft.value = parseReturnDraftRequest({
        callId: data.callId,
        action: data.action,
        arguments: data.arguments,
        returnType: data.returnType,
        returnOrderId: data.returnOrderId,
      });
      returnDraftDialogOpen.value = true;
    } catch {
      // 参数无法解析时不打开弹窗，避免展示不完整数据。
    }
    return;
  }
  if (data.orderType === 'PURCHASE') {
    if (data.action === 'SUBMIT' || data.action === 'CANCEL') {
      pendingAction.value = {
        actionRequestId: data.callId,
        toolCallId: data.callId,
        action: data.action,
        purchaseOrderId: data.purchaseOrderId || '',
        orderNo: data.orderNo || '',
        reason: data.reason || '',
      };
      actionDialogOpen.value = true;
      return;
    }
    if (!data.arguments) return;
    try {
      const parsed = JSON.parse(data.arguments);
      const draft = parsed?.request && typeof parsed.request === 'object' ? parsed.request : parsed;
      pendingDraft.value = {
        draftRequestId: data.callId,
        action: data.action === 'UPDATE' ? 'UPDATE' : 'CREATE',
        draft,
      };
      draftDialogOpen.value = true;
    } catch {
      // 参数无法解析时不打开弹窗，避免展示不完整数据。
    }
    return;
  }
  if (data.action === 'SUBMIT' || data.action === 'CANCEL') {
    pendingSalesAction.value = {
      actionRequestId: data.callId,
      toolCallId: data.callId,
      action: data.action,
      salesOrderId: data.salesOrderId || '',
      orderNo: data.orderNo || '',
      reason: data.reason || '',
    };
    salesActionDialogOpen.value = true;
    return;
  }
  if (!data.arguments) return;
  try {
    const parsed = JSON.parse(data.arguments);
    const draft = parsed?.request && typeof parsed.request === 'object' ? parsed.request : parsed;
    pendingSalesDraft.value = {
      draftRequestId: data.callId,
      action: data.action === 'UPDATE' ? 'UPDATE' : 'CREATE',
      draft,
    };
    salesDraftDialogOpen.value = true;
  } catch {
    // 参数无法解析时不打开弹窗，避免展示不完整数据。
  }
}

/** 销售草稿处置完成后收起确认页，并接上后端已经启动的恢复运行。 */
// 按会话和调用编号保留已提交状态，旧历史不能重新恢复同一张卡片；需要响应式，确认后立即结束等待提示。
const settledOrderCalls = reactive(new Map<string, Set<string>>());

function settleOrderCall(id: string, callId: string) {
  if (!callId) return;
  const calls = settledOrderCalls.get(id) || new Set<string>();
  calls.add(callId);
  settledOrderCalls.set(id, calls);
}

async function reconnectAfterOrder(id: string, callId: string, epoch: number, userId: string | undefined,
                                   orderType: 'SALES' | 'PURCHASE') {
  try {
    for (let attempt = 0; attempt < 40 && isCurrent(epoch, userId, id); attempt++) {
      const state = runs.get(id);
      if (!state.streaming && !state.querying && !state.watching) {
        const runtime = await getMainAgentRuntime(id);
        if (!isCurrent(epoch, userId, id)) return;
        const draft = orderType === 'PURCHASE'
          ? pendingDraftFrom(runtime?.draft)
          : pendingSalesDraftFrom(runtime?.salesDraft);
        const action = orderType === 'PURCHASE'
          ? pendingActionFrom(runtime?.action)
          : pendingSalesActionFrom(runtime?.salesAction);
        const pendingId = draft?.draftRequestId || action?.actionRequestId;
        if (runtime && (runtime.status !== 'WAITING_USER' || (pendingId && pendingId !== callId))) {
          await reconnectRun(id, epoch, userId);
          return;
        }
      }
      await new Promise(resolve => window.setTimeout(resolve, 250));
    }
    if (isCurrent(epoch, userId, id)) warning.value = '审批已提交，运行状态暂未更新，请重新进入会话查看。';
  } catch (cause) {
    if (isConversationMissing(cause)) {
      await handleMissingConversation(id);
      return;
    }
    if (isCurrent(epoch, userId, id)) warning.value = `审批已提交，恢复连接失败：${getApiErrorMessage(cause) || '请重新进入会话查看'}`;
  }
}

async function reconnectAfterAnswer(id: string, epoch: number, userId: string | undefined) {
  try {
    for (let attempt = 0; attempt < 40 && isCurrent(epoch, userId, id); attempt++) {
      const state = runs.get(id);
      if (!state.streaming && !state.querying && !state.watching) {
        const runtime = await getMainAgentRuntime(id);
        if (!isCurrent(epoch, userId, id)) return;
        const nextQuestionId = (runtime?.question as { questionRequestId?: unknown } | undefined)?.questionRequestId;
        // 恢复运行已开始或模型又提出了新问题，都需要重新订阅事件通道。
        if (runtime && (runtime.status !== 'WAITING_USER' || typeof nextQuestionId === 'string')) {
          await reconnectRun(id, epoch, userId);
          return;
        }
      }
      await new Promise(resolve => window.setTimeout(resolve, 250));
    }
    if (isCurrent(epoch, userId, id)) warning.value = '回答已提交，运行状态暂未更新，请重新进入会话查看。';
  } catch (cause) {
    if (isConversationMissing(cause)) {
      await handleMissingConversation(id);
      return;
    }
    if (isCurrent(epoch, userId, id)) warning.value = `回答已提交，恢复连接失败：${getApiErrorMessage(cause) || '请重新进入会话查看'}`;
  }
}

async function finishOrderTransition(kind: 'draft' | 'action',
                                     context: { conversationId: string; callId: string },
                                     orderType: 'SALES' | 'PURCHASE') {
  const id = context.conversationId;
  const userId = auth.user?.userId;
  const callId = context.callId;
  if (!id || !callId) return;
  const state = runs.get(id);
  const currentDraftId = orderType === 'PURCHASE'
    ? state.pendingDraft?.draftRequestId
    : state.pendingSalesDraft?.draftRequestId;
  const currentActionId = orderType === 'PURCHASE'
    ? state.pendingAction?.actionRequestId
    : state.pendingSalesAction?.actionRequestId;
  const clearSettled = () => {
    if (kind === 'draft' && currentDraftId === callId) {
      if (orderType === 'PURCHASE') state.pendingDraft = null;
      else state.pendingSalesDraft = null;
    }
    if (kind === 'action' && currentActionId === callId) {
      if (orderType === 'PURCHASE') state.pendingAction = null;
      else state.pendingSalesAction = null;
    }
  };
  settleOrderCall(id, callId);
  const currentCallId = kind === 'draft' ? currentDraftId : currentActionId;
  if (!userId || store.activeId !== id || currentCallId !== callId || closingOrder.value) {
    clearSettled();
    return;
  }
  const epoch = requestEpoch;
  closingOrder.value = { id, callId };
  if (kind === 'draft') {
    if (orderType === 'PURCHASE') draftDialogOpen.value = false;
    else salesDraftDialogOpen.value = false;
  } else if (orderType === 'PURCHASE') {
    actionDialogOpen.value = false;
  } else {
    salesActionDialogOpen.value = false;
  }
  // 先让弹窗和遮罩退出，保留请求及背景；后台已恢复执行，稍后订阅不会重复提交。
  await nextTick();
  // 先结束等待状态，关闭动画可以继续播放，不能反过来阻塞提示消失。
  clearSettled();
  const elements = document.querySelectorAll('[data-sales-draft-dialog][data-state="closed"], [data-sales-action-dialog][data-state="closed"], [data-sales-confirm-overlay][data-state="closed"], [data-confirm-dialog][data-state="closed"], .sales-approval-overlay[data-state="closed"]');
  const animations = [...elements].flatMap(element => element.getAnimations().filter(animation => animation.playState === 'running'));
  if (animations.length) {
    await Promise.race([
      Promise.all(animations.map(animation => animation.finished.catch(() => undefined))),
      new Promise(resolve => window.setTimeout(resolve, 500)),
    ]);
  }
  if (closingOrder.value?.id === id && closingOrder.value.callId === callId) closingOrder.value = null;
  if (isCurrent(epoch, userId, id)) void reconnectAfterOrder(id, callId, epoch, userId, orderType);
}

function handleSalesDraftSettled(context: { conversationId: string; callId: string }) {
  dismissPendingNotice(context.conversationId, context.callId);
  void finishOrderTransition('draft', context, 'SALES');
}

/** 等待用户确认的销售单提交或取消动作。 */
const pendingSalesAction = runtimeField('pendingSalesAction');
const salesActionDialogOpen = ref(false);

/** 把销售动作事件收敛成可校验的结构；格式异常时按缺少动作处理。 */
function pendingSalesActionFrom(value: unknown): SalesActionRequest | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  const item = value as Record<string, unknown>;
  // 旧事件结构：actionRequestId + action + salesOrderId
  if (typeof item.actionRequestId === 'string') {
    try {
      return parseSalesActionRequest(item);
    } catch {
      return null;
    }
  }
  // 新事件结构：callId + action + arguments（与 ai_message 的 TOOL_CALL 内容一致）
  if (typeof item.callId === 'string' && (item.action === 'SUBMIT' || item.action === 'CANCEL')) {
    return {
      actionRequestId: item.callId,
      toolCallId: item.callId,
      action: item.action,
      salesOrderId: typeof item.salesOrderId === 'string' ? item.salesOrderId : '',
      orderNo: typeof item.orderNo === 'string' ? item.orderNo : '',
      reason: typeof item.reason === 'string' ? item.reason : '',
    };
  }
  return null;
}

/** 打开销售动作确认页；必须点开详情后才能提交或取消。 */
function openSalesActionDialog() {
  if (!pendingSalesAction.value || closingOrder.value) return;
  salesActionDialogOpen.value = true;
}

/** 销售动作处置完成后收起确认页并清除等待状态。 */
function handleSalesActionSettled(context: { conversationId: string; callId: string }) {
  dismissPendingNotice(context.conversationId, context.callId);
  void finishOrderTransition('action', context, 'SALES');
}

/** 等待用户处置的退货草稿；与采购/销售草稿互斥。 */
const pendingReturnDraft = runtimeField('pendingReturnDraft');
const returnDraftDialogOpen = ref(false);

function pendingReturnDraftFrom(value: unknown): ReturnDraftRequest | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  try {
    return parseReturnDraftRequest(value as Record<string, unknown>);
  } catch {
    return null;
  }
}

function openReturnDraftDialog() {
  if (!pendingReturnDraft.value || closingOrder.value) return;
  returnDraftDialogOpen.value = true;
}

function handleReturnDraftSettled(context: { conversationId: string; callId: string }) {
  void finishReturnTransition('draft', context);
}

/** 等待用户确认的退货单提交或取消动作。 */
const pendingReturnAction = runtimeField('pendingReturnAction');
const returnActionDialogOpen = ref(false);

function pendingReturnActionFrom(value: unknown): ReturnActionRequest | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  return parseReturnActionRequest(value as Record<string, unknown>);
}

function openReturnActionDialog() {
  if (!pendingReturnAction.value || closingOrder.value) return;
  returnActionDialogOpen.value = true;
}

function handleReturnActionSettled(context: { conversationId: string; callId: string }) {
  void finishReturnTransition('action', context);
}

type PendingBusinessKey = 'pendingDraft' | 'pendingAction' | 'pendingSalesDraft'
  | 'pendingSalesAction' | 'pendingReturnDraft' | 'pendingReturnAction';

/** 实时事件进入新业务卡片前，清掉其他仍在等待的卡片，避免同时出现多张过期确认页。 */
function clearBusinessPending(state: ConversationRun, keep: PendingBusinessKey) {
  if (keep !== 'pendingDraft') state.pendingDraft = null;
  if (keep !== 'pendingAction') state.pendingAction = null;
  if (keep !== 'pendingSalesDraft') state.pendingSalesDraft = null;
  if (keep !== 'pendingSalesAction') state.pendingSalesAction = null;
  if (keep !== 'pendingReturnDraft') state.pendingReturnDraft = null;
  if (keep !== 'pendingReturnAction') state.pendingReturnAction = null;
  state.pendingClarification = null;
}

/** 退货确认完成后收起弹窗，并接上后端已启动的恢复运行。 */
async function finishReturnTransition(kind: 'draft' | 'action', context: { conversationId: string; callId: string }) {
  dismissPendingNotice(context.conversationId, context.callId);
  const id = context.conversationId;
  const userId = auth.user?.userId;
  const callId = context.callId;
  if (!id || !callId) return;
  const state = runs.get(id);
  const currentDraftId = state.pendingReturnDraft?.draftRequestId;
  const currentActionId = state.pendingReturnAction?.actionRequestId;
  const currentCallId = kind === 'draft' ? currentDraftId : currentActionId;
  settleOrderCall(id, callId);
  if (kind === 'draft' && currentDraftId === callId) state.pendingReturnDraft = null;
  if (kind === 'action' && currentActionId === callId) state.pendingReturnAction = null;
  if (!userId || store.activeId !== id || currentCallId !== callId || closingOrder.value) {
    return;
  }
  const epoch = requestEpoch;
  closingOrder.value = { id, callId };
  if (kind === 'draft') returnDraftDialogOpen.value = false;
  else returnActionDialogOpen.value = false;
  await nextTick();
  if (closingOrder.value?.id === id && closingOrder.value.callId === callId) closingOrder.value = null;
  if (isCurrent(epoch, userId, id)) void reconnectAfterReturnOrder(id, callId, epoch, userId);
}

async function reconnectAfterReturnOrder(id: string, callId: string, epoch: number, userId: string | undefined) {
  try {
    for (let attempt = 0; attempt < 40 && isCurrent(epoch, userId, id); attempt++) {
      const state = runs.get(id);
      if (!state.streaming && !state.querying && !state.watching) {
        const runtime = await getMainAgentRuntime(id);
        if (!isCurrent(epoch, userId, id)) return;
        const draft = pendingReturnDraftFrom(runtime?.returnDraft);
        const action = pendingReturnActionFrom(runtime?.returnAction);
        const nextQuestionId = (runtime?.question as { questionRequestId?: unknown } | undefined)?.questionRequestId;
        const pendingId = draft?.draftRequestId || action?.actionRequestId;
        if (runtime && (runtime.status !== 'WAITING_USER' || (pendingId && pendingId !== callId)
            || typeof nextQuestionId === 'string')) {
          await reconnectRun(id, epoch, userId);
          return;
        }
      }
      await new Promise(resolve => window.setTimeout(resolve, 250));
    }
    if (isCurrent(epoch, userId, id)) warning.value = '退货确认已提交，运行状态暂未更新，请重新进入会话查看。';
  } catch (cause) {
    if (isConversationMissing(cause)) {
      await handleMissingConversation(id);
      return;
    }
    if (isCurrent(epoch, userId, id)) warning.value = `退货确认已提交，恢复连接失败：${getApiErrorMessage(cause) || '请重新进入会话查看'}`;
  }
}
const small = useMediaQuery('(max-width: 900px)');
const phone = useMediaQuery('(max-width: 640px)');
const conversationResize = usePanelResize({
  storageKey: 'erp-ai-conversation-width', min: 200, max: 320, initial: 224,
});
/** 分析面板位于右侧，手柄在左边缘，向左拖动才是变宽。 */
const analysisResize = usePanelResize({
  storageKey: 'erp-ai-analysis-width', min: 280, max: 520, initial: 360, invert: true,
});
const workspaceStyle = computed(() => ({
  '--conversation-width': `${conversationResize.width.value}px`,
  '--analysis-width': `${analysisResize.width.value}px`,
}));
let requestEpoch = 0;
let disposed = false;
let lastInitKey = '';
const refreshes = new Map<string, Promise<void>>();

/** 是否正在输出：运行状态优先，只有状态未知时才回退到连接状态。 */
function isRunActive(state: ConversationRun) {
  if (state.runStatus === 'RUNNING') return true;
  if (state.runStatus === 'WAITING_USER' || state.runStatus === 'COMPLETED'
      || state.runStatus === 'FAILED' || state.runStatus === 'INTERRUPTED') {
    return false;
  }
  return state.querying || state.watching;
}
const runActive = computed(() => isRunActive(currentRun.value));
const busy = computed(() => creating.value || ((querying.value || watching.value) && currentRun.value.runStatus !== 'WAITING_USER'));
const runningConversationIds = computed(() => Array.from(runs.states.entries())
  .filter(([id, state]) => id !== 'new' && isRunActive(state))
  .map(([id]) => id));
/** 侧栏未读点：最近回复晚于本机已读时间，当前打开的会话不计入。 */
const unreadConversationIds = computed(() => store.conversations
  .filter(item => store.isUnread(item.conversationId))
  .map(item => item.conversationId));
/** 侧栏待处理点：服务端状态与本地暂停状态任一成立即显示。 */
const pendingConversationIds = computed(() => store.conversations
  .filter(item => item.awaitingUser || runs.states.get(item.conversationId)?.runStatus === 'WAITING_USER')
  .map(item => item.conversationId));

/** 侧栏按会话记录提醒，便于切换会话或本轮结束时统一收起。 */
function trackNotice(conversationId: string, toastId: string) {
  const ids = activeNoticeIds.get(conversationId) ?? new Set<string>();
  ids.add(toastId);
  activeNoticeIds.set(conversationId, ids);
}

function untrackNotice(conversationId: string, toastId: string) {
  const ids = activeNoticeIds.get(conversationId);
  if (!ids) return;
  ids.delete(toastId);
  if (!ids.size) activeNoticeIds.delete(conversationId);
}

function conversationTitle(conversationId: string) {
  return store.conversations.find(item => item.conversationId === conversationId)?.title || '会话';
}

/** 同步列表里该会话的待处理状态，避免等待列表接口刷新时的视觉回跳。 */
function setConversationAwaiting(conversationId: string, awaiting: boolean) {
  const item = store.conversations.find(row => row.conversationId === conversationId);
  if (item && item.awaitingUser !== awaiting) item.awaitingUser = awaiting;
}

/** 关闭提醒时同步两套记录，避免重连重放同一个 requestId 又弹一次。 */
function forgetNotice(conversationId: string, toastId: string) {
  untrackNotice(conversationId, toastId);
  const requestIds = pendingNoticeIds.get(conversationId);
  if (!requestIds) return;
  for (const requestId of [...requestIds]) {
    if (`ai-pending-${conversationId}-${requestId}` === toastId) {
      requestIds.delete(requestId);
    }
  }
  if (!requestIds.size) pendingNoticeIds.delete(conversationId);
}

/** 展示提醒；待处理常驻，输出完成 6 秒后自动收起。 */
function showRunNotice(conversationId: string, toastId: string, kind: 'pending' | 'completed', action: string, hint: string) {
  trackNotice(conversationId, toastId);
  toast.custom(AiRunNoticeComponent, {
    id: toastId,
    toasterId: AI_NOTICE_TOASTER_ID,
    duration: kind === 'pending' ? Infinity : 6000,
    onAutoClose: () => forgetNotice(conversationId, toastId),
    onDismiss: () => forgetNotice(conversationId, toastId),
    componentProps: {
      kind,
      title: `「${conversationTitle(conversationId)}」${action}`,
      hint,
      onOpen: () => {
        toast.dismiss(toastId);
        untrackNotice(conversationId, toastId);
        void selectConversation(conversationId);
      },
    },
  });
}

/** 后台会话出现待办时提醒；正在看的会话已经有表单，不重复弹。 */
function showPendingNotice(conversationId: string, requestId: string, action: string, hint = '点击查看并确认') {
  if (!requestId) return;
  setConversationAwaiting(conversationId, true);
  if (conversationId === store.activeId) return;
  const requestIds = pendingNoticeIds.get(conversationId) ?? new Set<string>();
  requestIds.add(requestId);
  pendingNoticeIds.set(conversationId, requestIds);
  showRunNotice(conversationId, `ai-pending-${conversationId}-${requestId}`, 'pending', action, hint);
}

function dismissPendingNotice(conversationId: string, requestId: string) {
  if (!requestId) return;
  setConversationAwaiting(conversationId, false);
  const toastId = `ai-pending-${conversationId}-${requestId}`;
  const requestIds = pendingNoticeIds.get(conversationId);
  requestIds?.delete(requestId);
  if (requestIds && !requestIds.size) pendingNoticeIds.delete(conversationId);
  toast.dismiss(toastId);
  untrackNotice(conversationId, toastId);
}

/** 本轮已经结束或用户已打开会话时，收起该会话的待处理提醒。 */
function dismissPendingNotices(conversationId: string) {
  const requestIds = pendingNoticeIds.get(conversationId);
  if (!requestIds) return;
  requestIds.forEach(requestId => dismissPendingNotice(conversationId, requestId));
}

function dismissCompletedNotices(conversationId: string) {
  const ids = activeNoticeIds.get(conversationId);
  if (!ids) return;
  for (const toastId of [...ids]) {
    if (!toastId.startsWith('ai-completed-')) continue;
    toast.dismiss(toastId);
    untrackNotice(conversationId, toastId);
  }
}

/** 待处理类型对应到卡片文案，四类单据和追问各自独立。 */
function returnOrderName(returnType?: string) {
  if (returnType === 'SALES_RETURN') return '销售退货单';
  if (returnType === 'PURCHASE_RETURN') return '采购退货单';
  return '退货单';
}

function pendingDraftText(returnType?: string) {
  return `等你确认${returnOrderName(returnType)}草稿`;
}

function pendingActionText(orderName: string, action: string) {
  return action === 'CANCEL' ? `等你确认取消${orderName}` : `等你确认提交${orderName}`;
}
watch(
  () => [store.activeId, store.messages[store.messages.length - 1]?.createdAt] as const,
  () => {
    if (store.activeId) store.markRead(store.activeId);
  },
  { flush: 'post' },
);
const locked = computed(
  () =>
    busy.value ||
    switching.value ||
    store.historyLoading ||
    (currentRun.value.refreshNeeded && !currentRun.value.streaming && currentRun.value.liveItems.length > 0) ||
    Boolean(store.activeId && !store.detail),
);
const runStatus = computed(() => currentRun.value.runStatus || 'IDLE');
const runTerminal = computed(() => ['COMPLETED', 'FAILED', 'INTERRUPTED'].includes(runStatus.value));
const currentQuestionData = computed(() => runStatus.value === 'WAITING_USER' ? pendingClarification.value : null);
const waiting = computed(() => Boolean(currentQuestionData.value));
const readingConversation = computed(() => switching.value || (store.historyLoading && !busy.value));
const displayedMessages = computed(() => {
  const messages = switching.value && switchSnapshot.value ? switchSnapshot.value.messages : store.messages;
  if (switching.value) {
    return messages;
  }
  if (!liveRunId.value) {
    return messages;
  }
  // 只隐藏已经由实时条目覆盖的历史行，不能因存在任意实时条目而隐藏整轮历史。
  const liveToolIds = new Set(liveItems.value.filter(item => item.type === 'tool').map(item => item.id));
  const completedLiveTexts = new Map<string, number>();
  for (const item of liveItems.value) {
    if (item.type === 'text' && (item.completed || currentRun.value.completedTextIds.has(item.id))) {
      completedLiveTexts.set(item.text, (completedLiveTexts.get(item.text) || 0) + 1);
    }
  }
  const orderIds = new Set(messages.filter(message => message.content.type === 'ORDER_TOOL')
    .map(message => message.content.type === 'ORDER_TOOL' ? message.content.data.callId : ''));
  return messages.filter(message => {
    if (message.requestId !== liveRunId.value) return true;
    if (message.kind === 'TOOL_CALL' && message.content.type === 'TOOL_STEP' && liveToolIds.has(message.content.data.callId)) return false;
    if (message.kind === 'ASSISTANT_STEP' && message.content.type === 'TEXT'
        && !(message.content.data.callId && orderIds.has(message.content.data.callId))) {
      const count = completedLiveTexts.get(message.content.data.text) || 0;
      if (count > 0) {
        completedLiveTexts.set(message.content.data.text, count - 1);
        return false;
      }
    }
    if (message.kind === 'ASSISTANT_FINAL' && message.content.type === 'TEXT'
        && currentRun.value.streaming && (completedLiveTexts.get(message.content.data.text) || 0) > 0) return false;
    return !(!currentRun.value.snapshotTruncated && waiting.value && message.kind === 'TOOL_CALL');
  });
});
const displayedPendingText = computed(() => liveRunId.value && displayedMessages.value.some(
  message => message.requestId === liveRunId.value && message.kind === 'USER_INPUT',
) ? '' : pendingText.value);
/** 分析面板展示本次会话全部分析块；历史行与实时事件按父工具调用编号合并。 */
const analysisBlocks = computed<AiAnalysisBlock[]>(() => {
  const snapshot = switching.value ? switchSnapshot.value : null;
  const source = snapshot ? snapshot.messages : store.messages;
  const blocks = new Map<string, AiAnalysisBlock>();
  for (const message of source) {
    // 只有分析过程行参与合并，普通消息不进入分析面板。
    if (message.kind !== 'ANALYSIS_STEP' || message.content.type !== 'ANALYSIS') {
      continue;
    }
    const data = message.content.data;
    blocks.set(data.callId, {
      id: data.callId, goal: data.goal, steps: data.steps, completion: data.completion, summary: data.summary,
      status: data.completion === 'FAILED' ? 'failed' : 'done',
    });
  }
  const live = (snapshot ? snapshot.analyses : liveAnalyses.value) || {};
  for (const block of Object.values(live)) {
    const saved = blocks.get(block.id);
    // 运行中的过程以实时数据为准；已结束的历史行内容完整，优先使用历史。
    if (!saved || block.status === 'running' || saved.status === 'running') {
      blocks.set(block.id, block);
    }
  }
  return [...blocks.values()];
});
/** 刷新或服务重启后，从历史里最后一条未回答的追问恢复表单。 */
function restoreClarificationFromHistory(messages: AiConversationMessage[]) {
  if (pendingClarification.value || runStatus.value !== 'WAITING_USER') {
    return;
  }
  // 当前已有草稿或动作卡片时，优先展示当前业务卡片，避免旧问题混入。
  if (pendingDraft.value || pendingAction.value || pendingSalesDraft.value || pendingSalesAction.value
    || pendingReturnDraft.value || pendingReturnAction.value) {
    return;
  }
  const currentRunId = currentRun.value.runId;
  const answered = new Set(messages
    .filter(message => message.kind === 'TOOL_RESULT' && message.content.type === 'TEXT' && message.content.data.callId)
    .map(message => message.content.type === 'TEXT' ? message.content.data.callId : ''));
  const pending = [...messages].reverse().find(message =>
    message.kind === 'TOOL_CALL'
    && message.content.type === 'CLARIFICATION'
    && !answered.has(message.content.data.callId)
    && (!currentRunId || !message.requestId || message.requestId === currentRunId));
  if (!pending || pending.content.type !== 'CLARIFICATION') {
    return;
  }
  pendingClarification.value = {
    questionRequestId: pending.content.data.callId,
    toolCallId: pending.content.data.callId,
    questions: pending.content.data.questions,
  };
}

/** 刷新后从历史里最后一条待确认记录恢复底部操作卡片。 */
watch(displayedMessages, messages => {
  if (closingOrder.value?.id === store.activeId) return;
  const resolved = new Set(messages
    .filter(message => message.kind === 'TOOL_RESULT' && message.content.type === 'ORDER_TOOL')
    .map(message => message.content.type === 'ORDER_TOOL' ? message.content.data.callId : ''));
  const settled = settledOrderCalls.get(store.activeId || '');
  for (const id of resolved) settled?.delete(id);
  const pending = [...messages].reverse().find(message =>
    message.kind === 'TOOL_CALL'
    && message.content.type === 'ORDER_TOOL'
    && !resolved.has(message.content.data.callId)
    && !settled?.has(message.content.data.callId)
    && (!message.content.data.status || message.content.data.status === 'waiting'));
  if (!pending || pending.content.type !== 'ORDER_TOOL') {
    pendingSalesDraft.value = null;
    pendingSalesAction.value = null;
    pendingDraft.value = null;
    pendingAction.value = null;
    pendingReturnDraft.value = null;
    pendingReturnAction.value = null;
    return;
  }
  const data = pending.content.data;
  if (data.orderType === 'RETURN') {
    if (data.action === 'SUBMIT' || data.action === 'CANCEL') {
      pendingDraft.value = null;
      pendingAction.value = null;
      pendingSalesDraft.value = null;
      pendingSalesAction.value = null;
      pendingReturnDraft.value = null;
      pendingReturnAction.value = parseReturnActionRequest({
        callId: data.callId,
        action: data.action,
        returnOrderId: data.returnOrderId,
        returnType: data.returnType,
        orderNo: data.orderNo,
        reason: data.reason,
      });
      return;
    }
    pendingDraft.value = null;
    pendingAction.value = null;
    pendingSalesDraft.value = null;
    pendingSalesAction.value = null;
    pendingReturnAction.value = null;
    try {
      pendingReturnDraft.value = parseReturnDraftRequest({
        callId: data.callId,
        action: data.action,
        arguments: data.arguments,
        returnType: data.returnType,
        returnOrderId: data.returnOrderId,
      });
    } catch {
      pendingReturnDraft.value = null;
    }
    return;
  }
  const purchase = data.orderType === 'PURCHASE';
  if (data.action === 'SUBMIT' || data.action === 'CANCEL') {
    pendingSalesDraft.value = null;
    pendingDraft.value = null;
    pendingReturnDraft.value = null;
    pendingReturnAction.value = null;
    const action = {
      actionRequestId: data.callId,
      toolCallId: data.callId,
      action: data.action,
      orderNo: data.orderNo || '',
      reason: data.reason || '',
    };
    if (purchase) {
      pendingSalesAction.value = null;
      pendingAction.value = { ...action, purchaseOrderId: data.purchaseOrderId || '' };
    } else {
      pendingAction.value = null;
      pendingSalesAction.value = { ...action, salesOrderId: data.salesOrderId || '' };
    }
    return;
  }
  pendingSalesAction.value = null;
  pendingAction.value = null;
  pendingReturnDraft.value = null;
  pendingReturnAction.value = null;
  if (!data.arguments) return;
  try {
    const parsed = JSON.parse(data.arguments);
    const draft = parsed?.request && typeof parsed.request === 'object' ? parsed.request : parsed;
    const request = {
      draftRequestId: data.callId,
      action: data.action === 'UPDATE' ? 'UPDATE' as const : 'CREATE' as const,
      draft,
    };
    if (purchase) {
      pendingSalesDraft.value = null;
      pendingDraft.value = request;
    } else {
      pendingDraft.value = null;
      pendingSalesDraft.value = request;
    }
  } catch {
    // 参数无法解析时不恢复卡片。
  }
}, { immediate: true });
/** 历史、运行状态或业务卡片变化后统一尝试恢复追问，避免旧问题压过当前卡片。 */
watch([displayedMessages, runStatus, pendingDraft, pendingAction, pendingSalesDraft, pendingSalesAction,
  pendingReturnDraft, pendingReturnAction], () => {
  restoreClarificationFromHistory(displayedMessages.value);
}, { immediate: true });
/** 本次会话是否存在分析过程；没有内容时不占用右侧面板位置。 */
const showAnalysisPanel = computed(() => analysisBlocks.value.length > 0);
/** 分析面板默认收起，用户点击“子智能体正在分析”才展开，可随时收回。 */
const analysisOpen = ref(false);
/** 当前查看的分析调用编号；为空时展示全部过程。 */
const selectedAnalysisId = ref('');
/** 只展示用户点开的那次分析，避免多次调用混在同一面板。 */
const visibleAnalysisBlocks = computed(() => {
  const blocks = analysisBlocks.value;
  if (!selectedAnalysisId.value) {
    return blocks;
  }
  const matched = blocks.filter(block => block.id === selectedAnalysisId.value);
  return matched.length > 0 ? matched : blocks;
});
const selectedReportProcessId = ref('');
/** 以轮次和工具调用编号合并历史过程，防止不同轮次的调用编号重用时串线。 */
const reportProcesses = computed<ConversationRun['reportProcesses']>(() => {
  const result: ConversationRun['reportProcesses'] = {};
  for (const message of store.messages) {
    if (message.kind === 'REPORT_STEP' && message.content.type === 'REPORT') {
      const data = message.content.data;
      result[JSON.stringify([message.requestId, data.callId])] = { id: data.callId, steps: data.steps, report: data.report, historical: true };
    }
  }
  for (const [callId, process] of Object.entries(currentRun.value.reportProcesses)) {
    result[JSON.stringify([currentRun.value.runId, callId])] = process;
  }
  return result;
});
const reportProcess = computed(() => reportProcesses.value[selectedReportProcessId.value]);
const cardsInConversation = computed(() => Object.values(currentRun.value.reportCards));
/** 历史列表只批量读取已进入页面的轮次，实时生成的卡片继续由事件补齐。 */
watch(
  () => [store.activeId, auth.token, [...new Set(store.messages.map(message => message.requestId))].filter(Boolean).join('\n')] as const,
  async ([id, , requestKeys], _, onCleanup) => {
    if (!id || !requestKeys) {
      return;
    }
    const controller = new AbortController();
    onCleanup(() => controller.abort());
    try {
      const cards = await getAiConversationReports(id, requestKeys.split('\n'), controller.signal);
      if (!controller.signal.aborted && store.activeId === id) {
        const state = runs.get(id);
        for (const card of cards) {
          state.reportCards[card.reportId] = card;
        }
      }
    } catch {
      if (!controller.signal.aborted && store.activeId === id) {
        warning.value ||= '历史报告卡片暂时无法读取，可重新打开会话重试。';
      }
    }
  },
  { immediate: true },
);

function openReportProcess(id: string) {
  analysisOpen.value = false;
  selectedAnalysisId.value = '';
  selectedReportProcessId.value = selectedReportProcessId.value === id ? '' : id;
}

/** 打开对应分析的过程；再次点击同一次分析时收起面板。 */
function openAnalysis(id?: string) {
  selectedReportProcessId.value = '';
  const target = id || '';
  if (analysisOpen.value && selectedAnalysisId.value === target) {
    closeAnalysis();
    return;
  }
  selectedAnalysisId.value = target;
  analysisOpen.value = true;
}

/** 收起分析面板并清空选择，下次打开回到全部过程。 */
function closeAnalysis() {
  analysisOpen.value = false;
  selectedAnalysisId.value = '';
}

/** 独立报告页返回时恢复对应会话的滚动位置。 */
async function openReport(card: AiReportCard) {
  const id = store.activeId;
  if (!id || switching.value) {
    return;
  }
  storageSet(scopedDraftKey(auth.user?.userId, id, 'reportReturnScroll'), scrollArea.value?.scrollTop || 0);
  await router.push({ name: 'ai-report-detail', params: { conversationId: id, reportId: card.reportId } });
}
/** 切换会话时收起分析面板；新会话的分析过程需要重新打开。 */
watch(() => store.activeId, () => {
  analysisOpen.value = false;
  selectedAnalysisId.value = '';
  selectedReportProcessId.value = '';
  // 草稿确认页属于原会话，切换后不能停留在新会话上方。
  draftDialogOpen.value = false;
  actionDialogOpen.value = false;
  salesDraftDialogOpen.value = false;
  salesActionDialogOpen.value = false;
  returnDraftDialogOpen.value = false;
  returnActionDialogOpen.value = false;
});
const showWelcome = computed(() => switching.value && switchSnapshot.value
  ? switchSnapshot.value.welcome
  : !store.messages.length && !store.historyLoading && !pendingText.value && !busy.value && !error.value);
const statusLabel = computed(() => {
  if (switching.value) {
    return '正在切换会话';
  }
  if (pendingDraft.value) {
    return '等待你确认采购草稿';
  }
  if (pendingAction.value) {
    return pendingAction.value.action === 'SUBMIT' ? '等待你确认提交审核' : '等待你确认取消采购单';
  }
  if (pendingSalesDraft.value) {
    return '等待你确认销售草稿';
  }
  if (pendingSalesAction.value) {
    return pendingSalesAction.value.action === 'SUBMIT' ? '等待你确认提交销售审核' : '等待你确认取消销售单';
  }
  if (pendingReturnDraft.value) {
    return '等待你确认退货草稿';
  }
  if (pendingReturnAction.value) {
    return pendingReturnAction.value.action === 'SUBMIT' ? '等待你确认提交退货审核' : '等待你确认取消退货单';
  }
  if (runActive.value) {
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
  if (!busy.value || steps.value.some(step => step.status === 'running')) {
    return false;
  }
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
function storageRemove(key: string) {
  try {
    localStorage.removeItem(key);
  } catch {
    // 存储受限时忽略，页面仍可用
  }
}
/** 运行状态接口返回会话不存在时，按本地残留会话处理。 */
function isConversationMissing(error: unknown) {
  if (!error || typeof error !== 'object') return false;
  const value = error as { code?: unknown; message?: unknown };
  return value.code === 10002
    || (typeof value.message === 'string' && value.message.includes('会话不存在或无权访问'));
}
/** 当前会话已被删除时清掉残留 ID，并回退到最近回复的会话。 */
async function handleMissingConversation(id: string) {
  if (store.activeId !== id) return;
  store.clearActive();
  storageRemove(scopedDraftKey(auth.user?.userId, null, 'activeConversation'));
  await store.loadConversations();
  const fallback = store.conversations.find(item => item.conversationId !== id);
  if (fallback) {
    await selectConversation(fallback.conversationId);
    conversationNotice.value = '会话已被删除，已切换到最近回复的会话';
  } else {
    conversationNotice.value = '会话已被删除，请新建会话';
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
      if (typeof cached === 'string' && cached.trim() === submission.text) {
        storageSet(key, '');
      }
    }
    if (store.activeId === id && !switching.value && query.value.trim() === submission.text) {
      query.value = '';
    }
  }
  storageSet(scopedDraftKey(userId, id, 'inflight'), null);
}
/** 页面卸载只断开订阅。重进后用新增用户消息确认受理，再清草稿，不能凭终态猜测曾提交成功。 */
function reconcileSubmittedDraft(id: string) {
  if (!store.detail) {
    return false;
  }
  const value = storageRead(draftKey('inflight'));
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return false;
  }
  const item = value as Partial<SubmittedDraft>;
  if (typeof item.text !== 'string' || typeof item.afterSeq !== 'string' || !/^\d+$/.test(item.afterSeq) ||
    !(item.questionRequestId === null || typeof item.questionRequestId === 'string') || typeof item.fromNew !== 'boolean') {
    return false;
  }
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
    // 用户修正答案后清掉上一次服务端错误，避免误以为修改无效。
    if (currentRun.value.pendingClarification && currentRun.value.error) {
      currentRun.value.error = '';
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
  if (!valid()) {
    return;
  }
  const data = event.data;
  const sequence = typeof data.eventSeq === 'number' ? data.eventSeq : null;
  if (event.event !== 'run_snapshot' && sequence !== null && sequence <= state.eventSeq) {
    return;
  }
  if (sequence !== null) {
    state.eventSeq = sequence;
  }
  switch (event.event) {
    case 'run_snapshot': {
      if (typeof data.runId !== 'string' || !Array.isArray(data.items)) {
        throw new Error('运行快照格式错误');
      }
      const previousRunId = state.runId;
      state.runId = data.runId;
      state.snapshotTruncated = data.snapshotTruncated === true;
      state.warning = state.snapshotTruncated ? '运行过程缓存不完整，已保留已保存的历史消息。' : '';
      const snapshotItems = data.items as SnapshotItem[];
      const previousItems = state.refreshNeeded && previousRunId === data.runId ? state.liveItems : [];
      const incoming = snapshotItems.filter((item): item is LiveItem => item.type === 'text' || item.type === 'tool' || item.type === 'clarification');
      state.liveItems = [...previousItems.filter(old => !incoming.some(item => item.type === old.type && item.id === old.id)), ...incoming];
      state.finalMessageId = typeof data.finalMessageId === 'string' ? data.finalMessageId : '';
      state.completedTextIds = new Set(state.liveItems.filter(item => item.type === 'text' && item.completed).map(item => item.id));
      state.liveText = state.liveItems.filter(item => item.type === 'text').map(item => item.text).join('\n\n');
      state.steps = state.liveItems.filter(item => item.type === 'tool').map(item => ({ node: item.id, label: item.label, status: item.status, durationMs: item.durationMs }));
      // 重连后按快照还原分析面板；已总结的块视为结束，其余仍在运行。
      const analyses: Record<string, AiAnalysisBlock> = {};
      for (const item of snapshotItems) {
        if (item.type === 'report') {
          const report = item.report ? normalizeReportCard(item.report) : undefined;
          state.reportProcesses[item.id] = { id: item.id, steps: item.steps.map(normalizeReportStep), report };
          if (report) {
            state.reportCards[report.reportId] = report;
          }
        }
        if (item.type !== 'analysis') {
          continue;
        }
        analyses[item.id] = {
          id: item.id, goal: item.goal || '', steps: Array.isArray(item.steps) ? item.steps : [],
          completion: item.completion || '', summary: item.summary || '',
          status: item.summary ? 'done' : 'running',
        };
      }
      state.analyses = analyses;
      if (data.status === 'RUNNING' || data.status === 'WAITING_USER' || data.status === 'COMPLETED' || data.status === 'FAILED') {
        state.runStatus = data.status;
      }
      const question = data.question as ConversationRun['pendingClarification'];
      state.pendingClarification = data.status === 'WAITING_USER' && question?.questionRequestId && Array.isArray(question.questions) ? question : null;
      state.pendingDraft = data.status === 'WAITING_USER' ? pendingDraftFrom(data.draft) : null;
      state.pendingAction = data.status === 'WAITING_USER' ? pendingActionFrom(data.action) : null;
      state.pendingSalesDraft = data.status === 'WAITING_USER' ? pendingSalesDraftFrom(data.salesDraft) : null;
      state.pendingSalesAction = data.status === 'WAITING_USER' ? pendingSalesActionFrom(data.salesAction) : null;
      state.pendingReturnDraft = data.status === 'WAITING_USER' ? pendingReturnDraftFrom(data.returnDraft) : null;
      state.pendingReturnAction = data.status === 'WAITING_USER' ? pendingReturnActionFrom(data.returnAction) : null;
      break;
    }
    case 'run_started':
      state.runStatus = 'RUNNING';
      state.finalMessageId = '';
      state.runId = String(data.runId || '');
      // 服务端在发出 run_started 前已保存输入；以历史记录替换临时提交气泡。
      if (store.activeId === id && state.pendingText && !switching.value) {
        void reload(id, requestEpoch, auth.user?.userId);
      }
      break;
    case 'text_delta': {
      if (typeof data.delta !== 'string' || typeof data.messageId !== 'string') {
        throw new Error('文本事件格式错误');
      }
      if (state.completedTextIds.has(data.messageId)) {
        break;
      }
      if (state.liveMessageId && state.liveMessageId !== data.messageId) {
        state.liveText += '\n\n';
      }
      state.liveMessageId = data.messageId;
      state.liveText += data.delta;
      const existing = state.liveItems.find(item => item.type === 'text' && item.id === data.messageId);
      if (existing?.type === 'text') {
        existing.text += data.delta;
      } else {
        state.liveItems.push({ type: 'text', id: data.messageId, text: data.delta });
      }
      break;
    }
    case 'text_completed': {
      if (typeof data.text !== 'string' || typeof data.messageId !== 'string') {
        throw new Error('完整文本事件格式错误');
      }
      // 完整消息校正同一段流式内容，避免尾部分片缺失或重复追加。
      const item = state.liveItems.find(entry => entry.type === 'text' && entry.id === data.messageId);
      if (item?.type === 'text') {
        item.text = data.text;
        item.completed = true;
      } else {
        state.liveItems.push({ type: 'text', id: data.messageId, text: data.text, completed: true });
      }
      state.completedTextIds.add(data.messageId);
      state.liveMessageId = data.messageId;
      state.liveText = state.liveItems.filter(entry => entry.type === 'text').map(entry => entry.text).join('\n\n');
      break;
    }
    case 'tool_started':
      state.steps.push({ node: String(data.toolCallId), label: String(data.label || '正在处理当前步骤'), status: 'running', durationMs: null });
      state.liveItems.push({ type: 'tool', id: String(data.toolCallId), tool: typeof data.tool === 'string' ? data.tool : undefined, label: String(data.label || '正在处理当前步骤'), durationMs: null, status: 'running' });
      break;
    case 'tool_finished': {
      const step = state.steps.find(item => item.node === String(data.toolCallId));
      if (step) {
        if (typeof data.label === 'string') {
          step.label = data.label;
        }
        step.status = data.status === 'failed' ? 'failed' : 'done';
        step.durationMs = typeof data.durationMs === 'number' ? data.durationMs : null;
      }
      const tool = state.liveItems.find(item => item.type === 'tool' && item.id === String(data.toolCallId));
      if (tool?.type === 'tool') {
        if (typeof data.label === 'string') {
          tool.label = data.label;
        }
        tool.status = data.status === 'failed' ? 'failed' : 'done';
        tool.durationMs = typeof data.durationMs === 'number' ? data.durationMs : null;
      }
      // 分析工具本身失败时，过程块保留已出现的步骤并标记失败。
      const failedAnalysis = state.analyses[String(data.toolCallId)];
      if (failedAnalysis && data.status === 'failed' && failedAnalysis.status === 'running') {
        failedAnalysis.status = 'failed';
        failedAnalysis.completion = 'FAILED';
      }
      break;
    }
    case 'analysis_started': {
      const id = String(data.toolCallId || '');
      if (!id) {
        throw new Error('分析事件格式错误');
      }
      state.analyses[id] = {
        id, goal: typeof data.goal === 'string' ? data.goal : '', steps: [],
        completion: '', summary: '', status: 'running',
      };
      break;
    }
    case 'analysis_step': {
      const block = state.analyses[String(data.toolCallId || '')];
      // 没有起始事件的步骤直接忽略，避免出现无法归属的孤立片段。
      if (!block) {
        break;
      }
      block.steps.push({
        text: typeof data.text === 'string' ? data.text : '',
        tools: Array.isArray(data.tools) ? data.tools as AiAnalysisBlock['steps'][number]['tools'] : [],
      });
      break;
    }
    case 'analysis_summary': {
      const id = String(data.toolCallId || '');
      if (!id) {
        throw new Error('分析事件格式错误');
      }
      // 总结事件携带完整过程，直接覆盖，避免分片丢失导致面板缺步骤。
      state.analyses[id] = {
        id,
        goal: typeof data.goal === 'string' ? data.goal : '',
        steps: Array.isArray(data.steps) ? data.steps as AiAnalysisBlock['steps'] : [],
        completion: typeof data.completion === 'string' ? data.completion : '',
        summary: typeof data.summary === 'string' ? data.summary : '',
        status: data.completion === 'FAILED' ? 'failed' : 'done',
      };
      break;
    }
    case 'report_progress': {
      const id = String(data.toolCallId || '');
      if (!id) {
        throw new Error('报告事件缺少调用编号');
      }
      const step = normalizeReportStep(data);
      const process = state.reportProcesses[id] ?? { id, steps: [] };
      if (!process.steps.some(item => item.stepSeq === step.stepSeq)) {
        state.reportProcesses[id] = { ...process, steps: [...process.steps, step] };
      }
      break;
    }
    case 'report_ready': {
      const card = normalizeReportCard(data);
      if (card.requestId !== state.runId) {
        break;
      }
      const process = state.reportProcesses[card.toolCallId] ?? { id: card.toolCallId, steps: [] };
      state.reportProcesses[card.toolCallId] = { ...process, report: card };
      state.reportCards[card.reportId] = card;
      break;
    }
    case 'question': {
      if (typeof data.questionRequestId !== 'string' || !Array.isArray(data.questions)) {
        throw new Error('追问事件格式错误');
      }
      state.pendingClarification = { questionRequestId: data.questionRequestId, toolCallId: typeof data.toolCallId === 'string' ? data.toolCallId : undefined, questions: data.questions as ClarificationQuestion[] };
      const callId = typeof data.toolCallId === 'string' ? data.toolCallId : data.questionRequestId;
      showPendingNotice(id, data.questionRequestId, '需要你补充信息', '点击继续回答');
      if (!state.liveItems.some(item => item.type === 'clarification' && item.id === callId)) {
        state.liveItems.push({ type: 'clarification', id: callId, questions: state.pendingClarification.questions });
      }
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.();
      state.settleSuccess = null;
      if (store.activeId === id && state.pendingText && !switching.value) {
        void reload(id, requestEpoch, auth.user?.userId);
      }
      break;
    }
    case 'question_answered':
      dismissPendingNotice(id, String(data.questionRequestId || ''));
      if (state.pendingClarification?.questionRequestId === data.questionRequestId) {
        state.pendingClarification = null;
        state.runStatus = 'RUNNING';
      }
      break;
    case 'purchase_draft': {
      const draft = pendingDraftFrom(data);
      if (!draft) {
        throw new Error('采购草稿事件格式错误');
      }
      state.pendingDraft = draft;
      clearBusinessPending(state, 'pendingDraft');
      showPendingNotice(id, draft.draftRequestId, '等你确认采购单草稿');
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.();
      state.settleSuccess = null;
      break;
    }
    case 'purchase_draft_resolved':
      dismissPendingNotice(id, String(data.draftRequestId || ''));
      if (state.pendingDraft?.draftRequestId === data.draftRequestId) {
        state.pendingDraft = null;
        state.runStatus = 'RUNNING';
      }
      break;
    case 'purchase_action': {
      const action = pendingActionFrom(data);
      if (!action) {
        throw new Error('采购单动作格式错误');
      }
      state.pendingAction = action;
      clearBusinessPending(state, 'pendingAction');
      showPendingNotice(id, action.actionRequestId, pendingActionText('采购单', action.action));
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.();
      state.settleSuccess = null;
      break;
    }
    case 'purchase_action_resolved':
      dismissPendingNotice(id, String(data.actionRequestId || ''));
      if (state.pendingAction?.actionRequestId === data.actionRequestId) {
        state.pendingAction = null;
        state.runStatus = 'RUNNING';
      }
      break;
    case 'sales_order_draft': {
      const draft = pendingSalesDraftFrom(data);
      if (!draft) {
        throw new Error('销售草稿事件格式错误');
      }
      state.pendingSalesDraft = draft;
      clearBusinessPending(state, 'pendingSalesDraft');
      showPendingNotice(id, draft.draftRequestId, '等你确认销售单草稿');
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.();
      state.settleSuccess = null;
      break;
    }
    case 'sales_order_draft_resolved':
      dismissPendingNotice(id, String(data.draftRequestId || ''));
      if (state.pendingSalesDraft?.draftRequestId === data.draftRequestId) {
        state.pendingSalesDraft = null;
        state.runStatus = 'RUNNING';
      }
      break;
    case 'sales_order_action': {
      const action = pendingSalesActionFrom(data);
      if (!action) {
        throw new Error('销售单动作格式错误');
      }
      state.pendingSalesAction = action;
      clearBusinessPending(state, 'pendingSalesAction');
      showPendingNotice(id, action.actionRequestId, pendingActionText('销售单', action.action));
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.();
      state.settleSuccess = null;
      break;
    }
    case 'sales_order_action_resolved':
      dismissPendingNotice(id, String(data.actionRequestId || ''));
      if (state.pendingSalesAction?.actionRequestId === data.actionRequestId) {
        state.pendingSalesAction = null;
        state.runStatus = 'RUNNING';
      }
      break;
    case 'return_order_draft': {
      const draft = pendingReturnDraftFrom(data);
      if (!draft) {
        throw new Error('退货草稿事件格式错误');
      }
      state.pendingReturnDraft = draft;
      clearBusinessPending(state, 'pendingReturnDraft');
      showPendingNotice(id, draft.draftRequestId, pendingDraftText(draft.draft.returnType));
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.();
      state.settleSuccess = null;
      break;
    }
    case 'return_order_action': {
      const action = pendingReturnActionFrom(data);
      if (!action) {
        throw new Error('退货单动作格式错误');
      }
      state.pendingReturnAction = action;
      clearBusinessPending(state, 'pendingReturnAction');
      showPendingNotice(id, action.actionRequestId, pendingActionText(returnOrderName(action.returnType), action.action));
      state.runStatus = 'WAITING_USER';
      state.steps = [];
      state.settleSuccess?.();
      state.settleSuccess = null;
      break;
    }
    case 'run_completed':
      if (data.status === 'WAITING_USER') {
        // 人工确认暂停不是最终答复，保留当前进度文字，等恢复后再收起。
        state.runStatus = 'WAITING_USER';
        break;
      }
      state.runStatus = 'COMPLETED';
      state.pendingSalesDraft = null;
      state.pendingSalesAction = null;
      state.pendingDraft = null;
      state.pendingAction = null;
      state.pendingReturnDraft = null;
      state.pendingReturnAction = null;
      state.pendingClarification = null;
      state.finalMessageId = typeof data.finalMessageId === 'string' ? data.finalMessageId : '';
      clearFailureHold(state);
      state.settleSuccess?.();
      state.settleSuccess = null;
      dismissPendingNotices(id);
      setConversationAwaiting(id, false);
      if (id !== store.activeId) {
        const noticeKey = state.runId || state.finalMessageId || String(Date.now());
        showRunNotice(id, `ai-completed-${id}-${noticeKey}`, 'completed', '输出完成', '点击查看会话');
      }
      break;
    case 'run_failed':
      state.runStatus = 'FAILED';
      state.pendingSalesDraft = null;
      state.pendingSalesAction = null;
      state.pendingDraft = null;
      state.pendingAction = null;
      state.pendingReturnDraft = null;
      state.pendingReturnAction = null;
      state.error = String(data.message || '任务执行失败');
      state.failureCode = String(data.code || '');
      state.steps = [];
      dismissPendingNotices(id);
      setConversationAwaiting(id, false);
      holdTransientFailure(state);
      break;
  }
}
async function scrollToLatest() {
  await nextTick();
  if (scrollArea.value) {
    scrollArea.value.scrollTop = scrollArea.value.scrollHeight;
  }
}
// 只跟随原本靠近底部的用户；人工向上滚动后不强制拉回。
const followLatest = ref(true);
function trackConversationScroll() {
  const area = scrollArea.value;
  if (area) followLatest.value = area.scrollHeight - area.clientHeight - area.scrollTop < 48;
}
watch(scrollArea, (area, _previous, onCleanup) => {
  if (!area || typeof ResizeObserver === 'undefined') return;
  const observer = new ResizeObserver(() => {
    if (followLatest.value && !switching.value) area.scrollTop = area.scrollHeight;
  });
  observer.observe(area);
  onCleanup(() => observer.disconnect());
});
watch([pendingDraft, pendingAction, pendingSalesDraft, pendingSalesAction, pendingReturnDraft, pendingReturnAction], async values => {
  if (!values.some(Boolean)) return;
  const follow = followLatest.value;
  await nextTick();
  if (follow && !switching.value) await scrollToLatest();
}, { flush: 'pre' });
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
  if (pending) {
    return pending;
  }
  const request = performReload(id, epoch, userId);
  refreshes.set(key, request);
  try {
    await request;
  } finally {
    if (refreshes.get(key) === request) {
      refreshes.delete(key);
    }
  }
}
async function performReload(id: string, epoch: number, userId: string | undefined) {
  if (!isCurrent(epoch, userId, id)) {
    return;
  }
  try {
    const loaded = await store.loadConversation(id, { silent: true });
    if (!loaded) throw new Error('历史消息未能接管当前回复');
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
      const savedTexts = new Map<string, number>();
      for (const message of store.messages) {
        if (message.requestId === state.runId && (message.kind === 'ASSISTANT_STEP' || message.kind === 'ASSISTANT_FINAL')
            && message.content.type === 'TEXT') {
          const text = message.content.data.text;
          savedTexts.set(text, (savedTexts.get(text) || 0) + 1);
        }
      }
      const hasSavedFinal = store.messages.some(message => message.requestId === state.runId && message.kind === 'ASSISTANT_FINAL');
      // 服务端终态携带最终文本的 messageId，可明确与已保存的本轮最终回复关联。
      const awaitingHistory = Boolean(state.runId && state.liveItems.some(item => {
        if (item.type !== 'text' || !item.text) return false;
        const count = savedTexts.get(item.text) || 0;
        if (count > 0) {
          savedTexts.set(item.text, count - 1);
          return false;
        }
        return !(state.finalMessageId && item.id === state.finalMessageId && hasSavedFinal);
      }));
      if (awaitingHistory && !state.streaming) {
        state.warning = '部分回复尚未同步到历史，请重新进入会话确认后再继续。';
      } else if (!awaitingHistory && state.warning === '部分回复尚未同步到历史，请重新进入会话确认后再继续。') {
        state.warning = '';
      }
      if (!state.streaming && !awaitingHistory) {
        state.liveText = '';
        state.liveItems = [];
        state.completedTextIds.clear();
        state.steps = [];
      }
      state.refreshNeeded = awaitingHistory;
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
  if (state.streaming) {
    return;
  }
  const runtime = await getMainAgentRuntime(id);
  if (!isCurrent(epoch, userId, id) || state.streaming || !runtime) {
    return;
  }
  if (runtime.status === 'INTERRUPTED') {
    state.runStatus = 'INTERRUPTED';
    return;
  }
  if (runtime.status === 'WAITING_USER') {
    // 人工确认暂停：待确认记录已经在 ai_message 里，刷新历史即可渲染卡片和进度。
    state.runStatus = 'WAITING_USER';
    await reload(id, epoch, userId);
    return;
  }
  if (runtime.status !== 'RUNNING' && runtime.status !== 'WAITING_USER') {
    if (runtime.status === 'COMPLETED' || runtime.status === 'FAILED') {
      await reload(id, epoch, userId);
    }
    return;
  }
  const connection = runs.connect(id, 'watching');
  if (!connection) {
    return;
  }
  const { controller, version } = connection;
  const token = auth.token;
  const valid = () => !disposed && auth.user?.userId === userId && auth.token === token
    && runs.states.get(id) === state && state.version === version;
  state.error = '';
  state.runStatus = 'RUNNING';
  let snapshotReady = () => {};
  const firstSnapshot = new Promise<void>(resolve => {
    snapshotReady = resolve;
  });
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
      if (!valid()) {
        return;
      }
      state.streaming = false;
      state.refreshNeeded = true;
      if (store.activeId === id && !switching.value) {
        await reload(id, requestEpoch, userId);
      }
      if (valid()) {
        stopSteps(state, state.error ? 'failed' : 'interrupted');
        runs.disconnect(id, version, state.refreshNeeded);
        void store.loadConversations().catch(() => undefined);
      }
    }
  })();
  // 切换等待首个运行快照，但不等待后台智能体完成任务。
  await firstSnapshot;
}

async function run(answer?: string) {
  if (answer && waiting.value && store.activeId && currentQuestionData.value) {
    if (answering.value) {
      return;
    }
    answering.value = true;
    const question = currentQuestionData.value;
    const conversationId = store.activeId;
    const answerRun = runs.get(conversationId);
    const previousError = answerRun.error;
    const answerEpoch = requestEpoch;
    const answerUserId = auth.user?.userId;
    try {
      const answers = question.questions.map(item => {
        const value = clarificationDraft.value.answers[item.id];
        const selected = value?.selected && value.selected !== '__custom_answer__' ? [value.selected] : [];
        return { questionId: item.id, selectedOptions: item.type === 'MULTIPLE_CHOICE' ? (value?.selectedOptions || []) : selected, customText: value?.custom?.trim() || '' };
      });
      const note = clarificationDraft.value.note.trim();
      const answerText = answers.map(item => [...item.selectedOptions, item.customText].filter(Boolean).join('、')).filter(Boolean);
      if (note) {
        answerText.push(note);
      }
      await submitMainAgentAnswer(conversationId, question.questionRequestId, answers, note);
      dismissPendingNotice(conversationId, question.questionRequestId);
      if (answerRun.error === previousError) {
        answerRun.error = '';
      }
      const callId = question.toolCallId || question.questionRequestId;
      answerRun.recentAnswers[callId] = { callId, questions: question.questions, answer: answerText.join('；') || '已补充信息', questionAnswers: Object.fromEntries(answers.map(item => [item.questionId, [...item.selectedOptions, item.customText].filter(Boolean).join('、')])), note };
      // 运行流可能先于提交响应结束，不能覆盖新的终态或其他追问。
      if (answerRun.runStatus === 'WAITING_USER'
          && answerRun.pendingClarification?.questionRequestId === question.questionRequestId) {
        answerRun.pendingClarification = null;
        answerRun.runStatus = 'RUNNING';
      }
      // 答案成功后后端已启动新的恢复运行，必须主动接上新的事件通道。
      void reconnectAfterAnswer(conversationId, answerEpoch, answerUserId);
    } catch (cause) {
      const message = getApiErrorMessage(cause) || '提交回答失败，请重试';
      answerRun.error = message;
      // 检查点已失效时清掉卡片，避免用户被锁在无法提交的问题上。
      if (message.includes('确认已失效')) {
        answerRun.pendingClarification = null;
        answerRun.runStatus = 'IDLE';
        void reload(conversationId, answerEpoch, answerUserId);
      }
    }
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
        if (auth.user?.userId === userId && auth.token === authToken) {
          creating.value = false;
        }
      }
    }
    if (!isCurrent(epoch, userId, id)) {
      return;
    }
    storageSet(scopedDraftKey(userId, null, 'activeConversation'), id);
    const connection = runs.connect(id, 'querying');
    if (!connection) {
      return;
    }
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
    if (!valid()) {
      return;
    }
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
        if (store.activeId === id && !switching.value) {
        await reload(id, requestEpoch, userId);
      }
        if (valid()) {
          stopSteps(state, state.error ? 'failed' : 'interrupted');
          runs.disconnect(id, version, state.refreshNeeded);
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
    if (isCurrent(epoch, userId, id || undefined)) {
      error.value = getApiErrorMessage(cause) || '会话创建失败';
    }
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
    analyses: { ...currentRun.value.analyses },
    reportCards: [...cardsInConversation.value],
    reportProcesses: { ...reportProcesses.value },
    reportRunId: liveRunId.value,
    finalMessageId: currentRun.value.finalMessageId,
    runTerminal: runTerminal.value,
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

async function openConversation(id: string, force = false): Promise<boolean> {
  if (creating.value || switching.value || (!force && id === store.activeId && store.detail)) {
    return false;
  }
  conversationLoadFailed.value = false;
  switchSnapshot.value = captureSwitchSnapshot();
  cancelTransient();
  switching.value = true;
  const switchStarted = performance.now();
  // 面板宽度与开合由用户控制；切换只更新内容，不让工作区先收起再展开。
  const epoch = requestEpoch;
  const userId = auth.user?.userId;
  // 用户切到哪个会话就立即记住，读取过程中页面重建也不会回到上一条。
  storageSet(scopedDraftKey(userId, null, 'activeConversation'), id);
  try {
    const loaded = await store.loadConversation(id);
    if (!loaded || !isCurrent(epoch, userId, id)) {
      return false;
    }
    storageSet(scopedDraftKey(userId, null, 'activeConversation'), id);
    store.markRead(id);
    const state = runs.get(id);
    // 切换读取期间后台可能刚好收尾；再取一次最新流水，避免用旧 RUNNING 快照覆盖终态。
    if (state.refreshNeeded && !state.streaming) {
      await reload(id, epoch, userId);
    }
    if (!isCurrent(epoch, userId, id)) {
      return false;
    }
    try {
      await reconnectRun(id, epoch, userId);
    } catch (cause) {
      if (isConversationMissing(cause)) {
        // 切会话的过渡尚未结束，延后回退，避免 selectConversation 被 switching 拦截。
        window.setTimeout(() => {
          void handleMissingConversation(id);
        }, 350);
        return false;
      }
      if (isCurrent(epoch, userId, id)) {
        warning.value = getApiErrorMessage(cause) || '运行状态暂时无法读取';
      }
    }
    if (!isCurrent(epoch, userId, id)) {
      return false;
    }
    if (reconcileSubmittedDraft(id)) {
      state.error = '';
    }
    restoreQuery();
    restoreClarification();
    mobileSidebarOpen.value = false;
    return true;
  } catch (cause) {
    if (isCurrent(epoch, userId, id)) {
      conversationLoadFailed.value = true;
      if (store.activeId === id) error.value = getApiErrorMessage(cause) || '会话读取失败，请重试';
      if (store.activeId === id) store.clearActive();
    }
    return false;
  } finally {
    await waitForSwitchTransition(switchStarted);
    if (isCurrent(epoch, userId)) {
      switching.value = false;
      switchSnapshot.value = null;
      const restore = route.query.conversationId === id
        ? storageRead(scopedDraftKey(userId, id, 'reportReturnScroll')) : null;
      if (typeof restore === 'number' && scrollArea.value) {
        await nextTick();
        scrollArea.value.scrollTop = restore;
      } else {
        await scrollToLatest();
      }
      // 终态也可能恰好落在上面的检查与切换遮罩退出之间，解除切换锁后补取一次。
      const state = runs.get(id);
      if (store.activeId === id && state.refreshNeeded && !state.streaming) {
        await reload(id, epoch, userId);
      }
    }
  }
}

/** 打开会话；读取失败时自动回退到最近回复的那一条会话。 */
async function selectConversation(id: string) {
  conversationNotice.value = '';
  const ok = await openConversation(id);
  if (ok) return true;
  if (store.activeId === id && store.detail) return true;
  if (!conversationLoadFailed.value) return false;
  const fallback = store.conversations.find(item => item.conversationId !== id);
  if (!fallback) return false;
  conversationNotice.value = '会话读取失败，已切换到最近回复的会话';
  return openConversation(fallback.conversationId);
}

/** 用户打开某个会话时，只收起该会话的完成提醒；待处理提醒保留到真正处理完。 */
watch(() => store.activeId, id => {
  if (id) dismissCompletedNotices(id);
});

onScopeDispose(() => {
  activeNoticeIds.forEach(ids => ids.forEach(toastId => toast.dismiss(toastId)));
  activeNoticeIds.clear();
  pendingNoticeIds.clear();
});

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
    if (!disposed && auth.user?.userId === userId && auth.token === authToken) {
      runs.remove(id);
    }
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
  // 退避期间不接受新的发送请求，避免连续打向上游。
  if (backingOff.value) {
    return;
  }
  if (waiting.value) {
    clarificationForm.value?.submit();
  } else {
    void run();
  }
}
watch(
  () => [auth.user?.userId, auth.token] as const,
  async ([userId]) => {
    const initKey = String(userId || '');
    if (!userId) {
      switching.value = false;
      return;
    }
    if (initKey === lastInitKey) return;
    lastInitKey = initKey;
    cancelTransient();
    runs.reset();
    creating.value = false;
    switchSnapshot.value = null;
    switching.value = true;
    store.reset();
    clarificationDraft.value = { answers: {}, note: '' };
    query.value = '';
    const epoch = requestEpoch;
    try {
      store.setReadScope(String(userId));
      await store.loadConversations();
      if (!isCurrent(epoch, userId)) {
        return;
      }
      store.seedReadAt();
      const remembered = typeof route.query.conversationId === 'string'
        ? route.query.conversationId : storageRead(scopedDraftKey(userId, null, 'activeConversation'));
      // 上次会话可能还没出现在首批列表里（刚创建或列表分页），仍然优先尝试；失败由 selectConversation 回退。
      const target = typeof remembered === 'string' && remembered
        ? remembered : store.conversations[0]?.conversationId;
      if (target) {
        switching.value = false;
        await selectConversation(target);
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
  <section ref="workspaceElement" class="graph-workspace ai-pilot-workspace" :style="workspaceStyle" :class="{ 'is-resizing': conversationResize.dragging.value || analysisResize.dragging.value }" data-testid="ai-graph-workspace">
    <AiConversationSidebar
      v-if="!small"
      :conversations="store.conversations"
      :active-id="store.activeId"
      :unread-ids="unreadConversationIds"
      :pending-ids="pendingConversationIds"
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
      @select="selectConversation"
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
          <span class="task-status" :class="{ 'agent-running-status': runActive || switching }" role="status"
            ><LoaderCircle v-if="runActive || switching" class="size-3.5 animate-spin" /><span
              v-else
              class="status-dot"
            />{{ statusLabel }}</span>
        </div>
      </header>
      <div class="conversation-body" :aria-busy="readingConversation" data-ai-conversation-region>
      <div v-if="switching" class="conversation-switch-progress" role="progressbar" aria-label="正在切换会话"><span /></div>
      <AiMessageNavigator :messages="displayedMessages" :scroll-container="scrollArea" :inert="readingConversation || undefined" />
      <div ref="scrollArea" class="conversation-scroll" :inert="readingConversation || undefined" @scroll="trackConversationScroll">
        <div class="conversation-stream" :class="{ 'conversation-switching': switching }">
          <div v-if="warning && !switching" class="request-warning" role="status">{{ warning }}</div>
        <div v-if="conversationNotice && !switching" class="request-warning" role="status">{{ conversationNotice }}</div>
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
            :report-cards="switching ? switchSnapshot?.reportCards : cardsInConversation"
            :report-processes="switching ? switchSnapshot?.reportProcesses : reportProcesses"
            :report-run-id="switching ? switchSnapshot?.reportRunId : liveRunId"
            :final-message-id="switching ? switchSnapshot?.finalMessageId : currentRun.finalMessageId"
            :run-terminal="switching ? switchSnapshot?.runTerminal : runTerminal"
            :paused="switching ? false : (runStatus === 'WAITING_USER' && Boolean(pendingDraft || pendingAction || pendingSalesDraft || pendingSalesAction || pendingReturnDraft || pendingReturnAction))"
            :settled-order-calls="switching ? undefined : settledOrderCalls.get(store.activeId || '')"
            @open-analysis="openAnalysis"
            @open-report-process="openReportProcess"
            @open-report="openReport"
            @open-order-tool="openOrderTool"
            >
            <template #clarification>
              <button type="button" class="question-status" data-testid="clarification-pending-status" aria-label="继续回答补充问题" :disabled="switching" @click="focusQuestion">
                <MessageSquare class="size-3.5" aria-hidden="true" />
                <span>等待你补充 {{ currentQuestionData?.questions.length }} 项信息</span>
                <span class="question-status-action">继续回答<ArrowRight class="size-3" aria-hidden="true" /></span>
              </button>
            </template>
          </AiMessageList>
          <AiAnalysisPanel v-if="small && showAnalysisPanel" variant="inline" :blocks="visibleAnalysisBlocks" />
          <AiReportProcessPanel v-if="small && reportProcess" :process="reportProcess" @close="selectedReportProcessId = ''" />
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
              <p v-if="backingOff" class="retry-countdown" data-testid="ai-retry-countdown" role="status">
                请 {{ retryAfter }} 秒后再试
              </p>
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
          (currentRun.refreshNeeded && !currentRun.streaming && currentRun.liveItems.length > 0) ||
          backingOff ||
          Boolean(pendingDraft) ||
          Boolean(pendingAction) ||
          Boolean(pendingSalesDraft) ||
          Boolean(pendingSalesAction) ||
          Boolean(pendingReturnDraft) ||
          Boolean(pendingReturnAction) ||
          Boolean(store.activeId && !store.detail) ||
          (runStatus === 'RUNNING' && !watching)
        "
        :error="queryError"
        @submit="submit"
      >
        <template #question>
          <Transition name="question-dock" appear @before-leave="setQuestionInert($event, true)" @before-enter="setQuestionInert($event, false)">
            <div v-if="waiting && currentQuestionData && !switching" ref="questionDock" class="agent-question-dock" data-ai-question-dock>
              <p v-if="currentRun.error" class="px-1 pb-2 text-xs text-destructive" role="alert" data-ai-question-error>{{ currentRun.error }}</p>
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
          <Transition name="question-dock" appear>
            <div v-if="pendingDraft && !switching" class="agent-question-dock" data-ai-purchase-draft-dock>
              <AiPurchaseDraftDock
                :item-count="pendingDraft.draft.items.length"
                :action="pendingDraft.action"
                :disabled="answering || switching"
                @open="openDraftDialog"
              />
            </div>
          </Transition>
          <Transition name="question-dock" appear>
            <div v-if="pendingAction && !switching" class="agent-question-dock" data-ai-purchase-action-dock>
              <AiPurchaseActionDock
                :action="pendingAction.action"
                :order-no="pendingAction.orderNo"
                :disabled="answering || switching"
                @open="openActionDialog"
              />
            </div>
          </Transition>
            <div v-if="pendingSalesDraft && !switching" class="agent-question-dock" data-ai-sales-draft-dock>
              <AiSalesDraftDock
                :item-count="pendingSalesDraft.draft.items.length"
                :action="pendingSalesDraft.action"
                :disabled="answering || switching"
                @open="openSalesDraftDialog"
              />
            </div>
            <div v-if="pendingSalesAction && !switching" class="agent-question-dock" data-ai-sales-action-dock>
              <AiSalesActionDock
                :action="pendingSalesAction.action"
                :order-no="pendingSalesAction.orderNo"
                :disabled="answering || switching"
                @open="openSalesActionDialog"
              />
            </div>
            <div v-if="pendingReturnDraft && !switching" class="agent-question-dock" data-ai-return-draft-dock>
              <AiReturnDraftDock
                :item-count="pendingReturnDraft.draft.items.length"
                :return-type="pendingReturnDraft.draft.returnType"
                :action="pendingReturnDraft.action"
                :disabled="answering || switching"
                @open="openReturnDraftDialog"
              />
            </div>
            <div v-if="pendingReturnAction && !switching" class="agent-question-dock" data-ai-return-action-dock>
              <AiReturnActionDock
                :action="pendingReturnAction.action"
                :order-no="pendingReturnAction.orderNo"
                :return-type="pendingReturnAction.returnType"
                :disabled="answering || switching"
                @open="openReturnActionDialog"
              />
            </div>
        </template>
      </AiComposerDock>
      <Toaster
        :id="AI_NOTICE_TOASTER_ID"
        class="ai-notice-toaster"
        position="top-right"
        :duration="6000"
        :gap="10"
        :visible-toasts="3"
        :close-button="false"
        :style="{ '--width': '336px' }"
      />
    </main>
    <template v-if="!small && (showAnalysisPanel || reportProcess)">
      <div
        v-if="analysisOpen || reportProcess"
        class="panel-resizer analysis-resizer"
        role="separator"
        tabindex="0"
        aria-label="调整分析面板宽度"
        aria-orientation="vertical"
        :aria-valuenow="analysisResize.width.value"
        :aria-valuemin="280"
        :aria-valuemax="520"
        @pointerdown="analysisResize.startResize"
        @keydown.left.prevent="analysisResize.nudge(16)"
        @keydown.right.prevent="analysisResize.nudge(-16)"
      />
      <div
        class="analysis-dock"
        :class="{ 'is-open': analysisOpen || reportProcess }"
        :inert="!analysisOpen && !reportProcess"
        data-testid="ai-analysis-dock"
      >
        <AiReportProcessPanel v-if="reportProcess" :process="reportProcess" @close="selectedReportProcessId = ''" />
        <AiAnalysisPanel v-else variant="panel" :blocks="visibleAnalysisBlocks" @close="closeAnalysis" />
      </div>
    </template>
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
          :unread-ids="unreadConversationIds"
          :pending-ids="pendingConversationIds"
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
          @select="selectConversation"
          @rename="renameConversation"
          @remove="removeConversation"
          @load-more="store.loadMoreConversations()"
          @toggle-collapse="mobileSidebarOpen = false" /></DialogContent
    ></Dialog>
    <PurchaseDraftDialog
      :open="draftDialogOpen"
      :request="pendingDraft"
      :conversation-id="store.activeId || ''"
      :action="pendingDraft?.action"
      @update:open="draftDialogOpen = $event"
      @settled="handleDraftSettled"
    />
    <PurchaseActionDialog
      :open="actionDialogOpen"
      :request="pendingAction"
      :conversation-id="store.activeId || ''"
      @update:open="actionDialogOpen = $event"
      @settled="handleActionSettled"
    />
    <SalesDraftDialog
      :open="salesDraftDialogOpen"
      :request="pendingSalesDraft"
      :conversation-id="store.activeId || ''"
      :action="pendingSalesDraft?.action"
      @update:open="salesDraftDialogOpen = $event"
      @settled="handleSalesDraftSettled"
    />
    <SalesActionDialog
      :open="salesActionDialogOpen"
      :request="pendingSalesAction"
      :conversation-id="store.activeId || ''"
      @update:open="salesActionDialogOpen = $event"
      @settled="handleSalesActionSettled"
    />
    <ReturnDraftDialog
      :open="returnDraftDialogOpen"
      :request="pendingReturnDraft"
      :conversation-id="store.activeId || ''"
      @update:open="returnDraftDialogOpen = $event"
      @settled="handleReturnDraftSettled"
    />
    <ReturnActionDialog
      :open="returnActionDialogOpen"
      :request="pendingReturnAction"
      :conversation-id="store.activeId || ''"
      @update:open="returnActionDialogOpen = $event"
      @settled="handleReturnActionSettled"
    />
  </section>
</template>

<style scoped>
/* 切换时只做透明度过渡：内容高度和滚动位置都会变化，再加位移与模糊会放大跳动感。 */
.conversation-switching {
  opacity: .55;
  transition: opacity 160ms ease-out;
}
.conversation-switch-progress {
  position: absolute;
  inset: 0 0 auto 0;
  z-index: 3;
  height: 3px;
  overflow: hidden;
  pointer-events: none;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
}
.conversation-switch-progress span {
  display: block;
  width: 36%;
  height: 100%;
  background: linear-gradient(90deg, transparent, var(--primary) 30%, var(--primary) 70%, transparent);
  animation: conversation-switch-progress 1.1s ease-in-out infinite;
}
@keyframes conversation-switch-progress { from { transform: translateX(-110%); } to { transform: translateX(330%); } }
@media (prefers-reduced-motion: reduce) {
  .conversation-switching { animation: none; transition: none; }
  .conversation-switch-progress span { animation: none; width: 100%; opacity: .45; }
}
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
  position: relative;
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
.question-dock-leave-active { transition: grid-template-rows 280ms cubic-bezier(0.38, 0, 0.24, 1), margin-bottom 280ms cubic-bezier(0.38, 0, 0.24, 1), opacity 240ms cubic-bezier(0.38, 0, 0.24, 1), transform 280ms cubic-bezier(0.38, 0, 0.24, 1); }
.question-dock-enter-from,
.question-dock-leave-to { grid-template-rows: minmax(0, 0fr); margin-bottom: 0; opacity: 0; transform: translateY(6px); }
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
/* 分析面板收放动效与会话侧栏一致：宽度与透明度同时过渡，内容固定宽度只做裁切。 */
.analysis-dock {
  flex: 0 0 auto;
  width: 0;
  overflow: hidden;
  opacity: 0;
  transform: translateX(14px);
  pointer-events: none;
  transition:
    width var(--motion-duration-slow) var(--motion-ease-standard),
    opacity 180ms ease,
    transform var(--motion-duration-slow) var(--motion-ease-standard);
}
.analysis-dock.is-open {
  width: var(--analysis-width);
  opacity: 1;
  transform: none;
  pointer-events: auto;
}
.analysis-dock :deep(.analysis-panel) {
  width: var(--analysis-width);
  height: 100%;
}
@media (prefers-reduced-motion: reduce) {
  .analysis-dock {
    transition: none;
  }
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
.retry-countdown {
  color: var(--muted-foreground);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.request-warning {
  color: var(--muted-foreground);
}
/* 提醒定位在会话区右上角；折叠、悬停展开和进出动画由 sonner 统一处理。 */
:deep(.ai-notice-toaster[data-sonner-toaster]) {
  --width: 336px;
  position: absolute;
  top: 68px;
  right: 20px;
  bottom: auto;
  left: auto;
}
:deep(.ai-notice-toaster[data-sonner-toaster] [data-sonner-toast]) {
  width: var(--width);
}
@media (max-width: 640px) {
  :deep(.ai-notice-toaster[data-sonner-toaster]) {
    top: 64px;
    right: 12px;
    left: 12px;
    width: auto;
    --width: auto;
  }
  :deep(.ai-notice-toaster[data-sonner-toaster] [data-sonner-toast]) {
    width: 100%;
  }
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
