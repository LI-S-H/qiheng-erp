import { reactive } from 'vue';
import type { ClarificationQuestion } from '../clarification/clarification-types';
import type { PurchaseDraftRequest } from '../drafts/purchase-draft-types';
import type { PurchaseActionRequest } from '../drafts/purchase-action';
import type { SalesDraftRequest } from '../drafts/sales-draft-types';
import type { SalesActionRequest } from '../drafts/sales-action';
import type { ReturnActionRequest, ReturnDraftRequest } from '../drafts/return-draft-types';
import type { AiAnalysisBlock, AiAnalysisStep, AiProgressStep, AiRunStatus } from '../types';
import type { AiReportCard, AiReportProcess } from '../contracts/report-contract';

/** 运行中的实时条目：文本、追问与工具状态。 */
export type LiveItem =
  | { type: 'text'; id: string; text: string; completed?: boolean }
  | { type: 'clarification'; id: string; questions: ClarificationQuestion[] }
  | { type: 'tool'; id: string; label: string; tool?: string; durationMs: number | null; status: 'running' | 'done' | 'failed' };

/** 运行快照中的条目：实时过程与分析过程共用同一次快照。 */
export type SnapshotItem =
  | LiveItem
  | { type: 'analysis'; id: string; goal: string; steps: AiAnalysisStep[]; completion: string | null; summary: string | null }
  | { type: 'purchase_draft'; id: string; callId?: string; draftRequestId?: string; action?: string; arguments?: string }
  | { type: 'purchase_action'; id: string; callId?: string; actionRequestId?: string; action?: string; purchaseOrderId?: string }
  | { type: 'sales_draft'; id: string; callId?: string; draftRequestId?: string; action?: string; arguments?: string }
  | { type: 'sales_action'; id: string; callId?: string; actionRequestId?: string; action?: string; salesOrderId?: string }
  | { type: 'return_order_draft'; id: string; callId?: string; draftRequestId?: string; action?: string; arguments?: string }
  | { type: 'return_order_action'; id: string; callId?: string; actionRequestId?: string; action?: string; returnOrderId?: string }
  | ({ type: 'report' } & AiReportProcess);

/** 单个会话的运行状态；会话之间彼此隔离。 */
export interface ConversationRun {
  querying: boolean;
  watching: boolean;
  streaming: boolean;
  version: number;
  runStatus: AiRunStatus | null;
  pendingText: string;
  liveText: string;
  liveMessageId: string;
  liveItems: LiveItem[];
  completedTextIds: Set<string>;
  eventSeq: number;
  snapshotTruncated: boolean;
  runId: string;
  error: string;
  warning: string;
  steps: AiProgressStep[];
  /** 本次会话已出现的分析过程；按父工具调用编号区分，跨轮次保持。 */
  analyses: Record<string, AiAnalysisBlock>;
  reportProcesses: Record<string, AiReportProcess>;
  reportCards: Record<string, AiReportCard>;
  finalMessageId: string;
  pendingClarification: { questionRequestId: string; toolCallId?: string; questions: ClarificationQuestion[] } | null;
  /** 等待用户确认的采购草稿；同一时刻只会有一份。 */
  pendingDraft: PurchaseDraftRequest | null;
  /** 等待用户确认的提交或取消动作。 */
  pendingAction: PurchaseActionRequest | null;
  /** 等待用户确认的销售草稿；与采购草稿互斥。 */
  pendingSalesDraft: SalesDraftRequest | null;
  /** 等待用户确认的销售单提交或取消动作。 */
  pendingSalesAction: SalesActionRequest | null;
  /** 等待用户确认的退货草稿；与采购/销售草稿互斥。 */
  pendingReturnDraft: ReturnDraftRequest | null;
  /** 等待用户确认的退货单提交或取消动作。 */
  pendingReturnAction: ReturnActionRequest | null;
  recentAnswers: Record<string, { callId: string; questions: ClarificationQuestion[]; answer: string; questionAnswers: Record<string, string>; note: string }>;
  refreshNeeded: boolean;
  settleSuccess: (() => void) | null;
  /** 本轮失败的错误码；用于区分瞬时故障与业务错误。 */
  failureCode: string;
  /** 连续瞬时故障次数，决定下一次退避时长。 */
  failureCount: number;
  /** 退避截止时间戳；0 表示可以立即再次发送。 */
  retryAt: number;
}

/** 只有瞬时故障才退避；权限、参数这类业务错误改输入就能解决，锁住没有意义。 */
const TRANSIENT_CODES = new Set(['MODEL_CALL_FAILED']);

/** 退避时长（秒）：连续失败时逐步拉长，封顶 5 分钟。 */
const RETRY_DELAYS = [30, 60, 120, 300];

/**
 * 按失败错误码决定是否进入退避。
 *
 * @param state 会话运行状态
 * @returns 进入退避返回 true
 */
export function holdTransientFailure(state: ConversationRun): boolean {
  if (!TRANSIENT_CODES.has(state.failureCode)) {
    state.retryAt = 0;
    return false;
  }
  const index = Math.min(state.failureCount, RETRY_DELAYS.length - 1);
  state.failureCount += 1;
  state.retryAt = Date.now() + RETRY_DELAYS[index] * 1000;
  return true;
}

/** 本轮成功后清除退避，下一次故障从最短间隔重新计算。 */
export function clearFailureHold(state: ConversationRun): void {
  state.failureCode = '';
  state.failureCount = 0;
  state.retryAt = 0;
}

/** 执行连接属于会话；切换页面视图不能取消另一会话的执行或丢掉未落库暂停点。 */
export function useConversationRuns() {
  const states = reactive(new Map<string, ConversationRun>());
  const controllers = new Map<string, AbortController>();

  /** 取得会话的运行状态；首次访问时建立初始状态。 */
  function get(id: string | null) {
    const key = id ?? 'new';
    if (!states.has(key)) {
      states.set(key, {
        querying: false, watching: false, streaming: false, version: 0, runStatus: null,
        pendingText: '', liveText: '', liveMessageId: '', liveItems: [], completedTextIds: new Set(),
        eventSeq: 0, snapshotTruncated: false, runId: '', error: '', warning: '', steps: [], analyses: {},
        reportProcesses: {}, reportCards: {}, finalMessageId: '',
        pendingClarification: null, pendingDraft: null, pendingAction: null, pendingSalesDraft: null, pendingSalesAction: null,
        pendingReturnDraft: null, pendingReturnAction: null, recentAnswers: {}, refreshNeeded: false, settleSuccess: null,
        failureCode: '', failureCount: 0, retryAt: 0,
      });
    }
    return states.get(key)!;
  }

  /** 建立本会话的执行连接；同一会话已有连接时返回 null。 */
  function connect(id: string, mode: 'querying' | 'watching') {
    const state = get(id);
    if (state.querying || state.watching) {
      return null;
    }
    const controller = new AbortController();
    controllers.set(id, controller);
    state.version += 1;
    state[mode] = true;
    state.streaming = true;
    return { state, controller, version: state.version };
  }

  /** 断开本会话的执行连接；版本不一致说明已被新一轮接管，直接忽略。 */
  function disconnect(id: string, version: number, preserveLive = false) {
    const state = states.get(id);
    if (!state || state.version !== version) {
      return;
    }
    state.querying = false;
    state.watching = false;
    state.streaming = false;
    controllers.delete(id);
    if (preserveLive) return;
    state.pendingText = '';
    state.liveText = '';
    state.liveMessageId = '';
    state.finalMessageId = '';
    state.liveItems = [];
    state.completedTextIds.clear();
    state.eventSeq = 0;
    state.snapshotTruncated = false;
    state.runId = '';
  }

  /** 丢弃本会话的状态与连接。 */
  function remove(id: string) {
    const state = states.get(id);
    if (state) {
      state.version += 1;
    }
    controllers.get(id)?.abort();
    controllers.delete(id);
    states.delete(id);
  }

  /** 丢弃全部会话的状态与连接。 */
  function reset() {
    for (const id of states.keys()) {
      remove(id);
    }
  }

  return { states, get, connect, disconnect, remove, reset };
}
