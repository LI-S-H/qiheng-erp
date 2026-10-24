import { http } from '@/api/http';
import { AUTH_TOKEN_NAME_STORAGE_KEY, AUTH_TOKEN_STORAGE_KEY } from '@/shared/constants/storage';
import { normalizeVisibleContent } from './message-contract';
import type { AiMessageContent } from './types';
import { readSseStream, type SseFrame } from './sse';

/** 服务端进度事件名，与后端 RunProgressPublisher 的常量一一对应。 */
const EVENT_NODE_START = 'node-start';
const EVENT_NODE_END = 'node-end';
const EVENT_ARTIFACT = 'artifact';
const EVENT_CLARIFICATION_REQUIRED = 'clarification_required';
const EVENT_RUN_FINISHED = 'run_finished';
const EVENT_RUN_STOPPED = 'run_stopped';

/** 单个节点的进度；durationMs 只在节点结束时才有值。 */
export interface AiNodeProgress {
  node: string;
  label: string;
  durationMs: number | null;
}

/** 产物内容事件：产物定稿即推送（先于 run_finished），content 与可见消息展示协议同构；顶层 checkpointId 仅作诊断。 */
export interface AiArtifactEvent {
  type: string;
  checkpointId: string | null;
  content: AiMessageContent;
}

/** 澄清事件：本轮已保存在等待补充状态，content 与可见消息展示协议同构；恢复锚点取 content.data.pauseId，顶层 checkpointId 仅作诊断。 */
export interface AiClarificationEvent {
  checkpointId: string | null;
  content: AiMessageContent;
}

/** 本轮完成（薄载荷）：正文以流水为准，页面收到后重新拉取消息列表。 */
export interface AiRunFinishedEvent {
  status: 'NEEDS_READY' | 'PLAN_READY';
  warning: string | null;
}

/** 本轮停止（薄载荷）：格式重试耗尽或执行失败，reason 为可直接展示的说明。 */
export interface AiRunStoppedEvent {
  status: 'FORMAT_RETRY_EXHAUSTED' | 'FAILED';
  reason: string | null;
  warning: string | null;
}

export interface AiRunHandlers {
  onNodeStart: (progress: AiNodeProgress) => void;
  onNodeEnd: (progress: AiNodeProgress) => void;
  /** 需求产物与旧计划共用消息协议，可在历史落库前先行展示。 */
  onArtifact?: (artifact: AiArtifactEvent) => void;
  /** 澄清事件（可选）：暂停轮的唯一内容推送。 */
  onClarification?: (clarification: AiClarificationEvent) => void;
  /** 终态：本轮完成或停止；收到后本轮消息以流水为准。 */
  onRunFinished: (ending: AiRunFinishedEvent) => void;
  onStopped: (ending: AiRunStoppedEvent) => void;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function requiredText(value: unknown, field: string): string {
  const normalized = typeof value === 'string' ? value.trim() : '';
  if (!normalized) {
    throw new Error(`进度事件缺少字段 ${field}`);
  }
  return normalized;
}

/** 可缺省文本归一为 null；顶层检查点由服务端补齐、仅作诊断（恢复锚点取澄清内容 pauseId）。 */
function optionalText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null;
}

/** 校验事件状态是否属于契约枚举；未知状态按协议错误处理，不做静默降级。 */
function requiredStatus<S extends string>(value: unknown, allowed: readonly S[]): S {
  const normalized = typeof value === 'string' ? value.trim() : '';
  if (!(allowed as readonly string[]).includes(normalized)) {
    throw new Error(`进度事件状态不在约定范围内：${String(value)}`);
  }
  return normalized as S;
}

/** 把一帧 SSE 翻译成回调；事件名和结构不符时直接报错，不静默跳过。 */
function dispatch(frame: SseFrame, handlers: AiRunHandlers) {
  let payload: unknown;
  try {
    payload = JSON.parse(frame.data);
  } catch {
    throw new Error('进度事件不是合法 JSON');
  }
  if (frame.event === EVENT_NODE_START || frame.event === EVENT_NODE_END) {
    const item = asRecord(payload);
    if (!item) {
      throw new Error('进度事件结构无效');
    }
    const duration = frame.event === EVENT_NODE_END ? Number(item.durationMs ?? 0) : null;
    if (duration !== null && (!Number.isFinite(duration) || duration < 0)) {
      throw new Error('进度事件耗时必须是非负有限数值');
    }
    const progress: AiNodeProgress = {
      node: requiredText(item.node, 'node'),
      label: requiredText(item.label, 'label'),
      durationMs: duration,
    };
    if (frame.event === EVENT_NODE_START) {
      handlers.onNodeStart(progress);
    } else {
      handlers.onNodeEnd(progress);
    }
    return;
  }
  if (frame.event === EVENT_ARTIFACT) {
    // 产物事件：type 与 content 复用可见消息展示协议校验，顶层 checkpointId 仅作诊断
    const item = asRecord(payload);
    if (!item) {
      throw new Error('进度事件结构无效');
    }
    handlers.onArtifact?.({
      type: requiredText(item.type, 'type'),
      checkpointId: optionalText(item.checkpointId),
      content: normalizeVisibleContent(item.content),
    });
    return;
  }
  if (frame.event === EVENT_CLARIFICATION_REQUIRED) {
    // 澄清事件：暂停轮的内容推送，结构与产物事件同形但类型固定；恢复锚点在内容 pauseId
    const item = asRecord(payload);
    if (!item) {
      throw new Error('进度事件结构无效');
    }
    const content = normalizeVisibleContent(item.content);
    if (content.type !== 'CLARIFICATION') {
      throw new Error('澄清事件内容类型必须为 CLARIFICATION');
    }
    handlers.onClarification?.({
      checkpointId: optionalText(item.checkpointId),
      content,
    });
    return;
  }
  if (frame.event === EVENT_RUN_FINISHED) {
    // 终态事件只带状态与告警：消息正文由前端重新拉取流水，不在事件里重复推送
    const item = asRecord(payload);
    if (!item) {
      throw new Error('进度事件结构无效');
    }
    handlers.onRunFinished({
      status: requiredStatus(item.status, ['NEEDS_READY', 'PLAN_READY'] as const),
      warning: optionalText(item.warning),
    });
    return;
  }
  if (frame.event === EVENT_RUN_STOPPED) {
    const item = asRecord(payload);
    if (!item) {
      throw new Error('进度事件结构无效');
    }
    handlers.onStopped({
      status: requiredStatus(item.status, ['FORMAT_RETRY_EXHAUSTED', 'FAILED'] as const),
      reason: optionalText(item.reason),
      warning: optionalText(item.warning),
    });
    return;
  }
  throw new Error(`未知的进度事件：${frame.event}`);
}

/**
 * 打开一条进度长连接。
 *
 * <p>发起一轮时必须以 run_finished、run_stopped 或 clarification_required 收尾，
 * 否则视为连接中断；只订阅进度时任务可能本来就没在跑，正常关闭即可。</p>
 */
async function openProgressStream(
  path: string,
  init: RequestInit,
  handlers: AiRunHandlers,
  signal?: AbortSignal,
  requireResult = true,
) {
  const token = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
  const tokenName = localStorage.getItem(AUTH_TOKEN_NAME_STORAGE_KEY) || 'satoken';
  const headers = new Headers(init.headers);
  headers.set('Accept', 'text/event-stream');
  if (token) {
    headers.set(tokenName, token);
  }
  const baseURL = String(http.defaults.baseURL || '/api').replace(/\/$/, '');
  const response = await fetch(`${baseURL}${path}`, { ...init, headers, signal });
  if (!response.ok) {
    let errorMessage = `AI 请求失败（${response.status}）`;
    const error = await response.json().catch(() => null);
    if (error && typeof error.message === 'string') {
      errorMessage = error.message;
    }
    if (response.status === 401 && token === localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)) {
      localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
      localStorage.removeItem(AUTH_TOKEN_NAME_STORAGE_KEY);
      window.location.href = '/login';
    }
    throw new Error(errorMessage);
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
    await response.body?.cancel();
    throw new Error('AI 服务未返回约定的进度流');
  }

  // 终态帧集合：暂停轮以澄清事件收尾，其余以完成或停止事件收尾
  let settled = false;
  await readSseStream(
    response.body,
    (frame) => {
      if (token !== localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)) {
        throw new Error('登录会话已变更，请重新进入页面');
      }
      dispatch(frame, {
        ...handlers,
        onClarification: (clarification) => {
          settled = true;
          handlers.onClarification?.(clarification);
        },
        onRunFinished: (ending) => {
          settled = true;
          handlers.onRunFinished(ending);
        },
        onStopped: (ending) => {
          settled = true;
          handlers.onStopped(ending);
        },
      });
    },
    signal,
  );

  if (requireResult && !settled) {
    throw new Error('进度连接中断，本轮结果未知，请刷新会话确认。');
  }
}

/** 发起一轮分析；返回时本轮已经结束，消息正文需要重新拉取。 */
export function startGraphStream(
  conversationId: string,
  query: string,
  handlers: AiRunHandlers,
  signal?: AbortSignal,
) {
  return openProgressStream(
    '/ai/graph/stream',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, query }),
    },
    handlers,
    signal,
  );
}

/** 提交补充回答并继续本轮分析。 */
export function resumeGraphStream(
  payload: { threadId: string; pauseId: string; answer: string },
  handlers: AiRunHandlers,
  signal?: AbortSignal,
) {
  return openProgressStream(
    '/ai/graph/resume/stream',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
    handlers,
    signal,
  );
}

/** 重新进入页面时接续订阅正在执行的进度；没有在跑的任务时连接会立即正常关闭。 */
export function subscribeConversationProgress(
  conversationId: string,
  handlers: AiRunHandlers,
  signal?: AbortSignal,
) {
  return openProgressStream(
    `/ai/assistant/conversations/${conversationId}/progress/stream`,
    { method: 'GET' },
    handlers,
    signal,
    false,
  );
}
