import { http } from '@/api/http';
import { AUTH_TOKEN_NAME_STORAGE_KEY, AUTH_TOKEN_STORAGE_KEY } from '@/shared/constants/storage';
import { readSseStream } from './sse';

export type MainAgentEventName = 'run_snapshot' | 'run_started' | 'text_delta' | 'text_completed' | 'tool_started' | 'tool_finished' | 'question' | 'question_answered' | 'run_completed' | 'run_failed';
export interface MainAgentEvent { event: MainAgentEventName; data: Record<string, unknown> }
export interface QuestionAnswer { questionId: string; selectedOptions: string[]; customText: string }

const events = new Set<MainAgentEventName>(['run_snapshot', 'run_started', 'text_delta', 'text_completed', 'tool_started', 'tool_finished', 'question', 'question_answered', 'run_completed', 'run_failed']);

/** 事件序号与耗时可能由后端作为数字字符串传输，统一转换后再进入页面状态。 */
function eventNumber(value: unknown): number | null {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return null;
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
}

function normalizeEventData(data: Record<string, unknown>): Record<string, unknown> {
  const normalized = { ...data };
  if ('eventSeq' in data) normalized.eventSeq = eventNumber(data.eventSeq);
  if ('durationMs' in data) normalized.durationMs = eventNumber(data.durationMs);
  if (Array.isArray(data.items)) {
    normalized.items = data.items.map(item => item && typeof item === 'object' && !Array.isArray(item) && item.type === 'tool'
      ? { ...item, durationMs: eventNumber(item.durationMs) } : item);
  }
  return normalized;
}

/** 发起本轮任务，只有完成或失败事件才能代表流正常结束。 */
export async function startMainAgentStream(conversationId: string, content: string, onEvent: (event: MainAgentEvent) => void, signal?: AbortSignal) {
  return receiveStream(conversationId, 'messages/stream', onEvent, signal, content);
}

/** 订阅已存在的任务，不再次提交用户消息。 */
export async function watchMainAgentStream(conversationId: string, onEvent: (event: MainAgentEvent) => void, signal?: AbortSignal) {
  return receiveStream(conversationId, 'events', onEvent, signal);
}

/** 查询当前进程中的运行状态；用户身份由服务端获取。 */
export async function getMainAgentRuntime(conversationId: string): Promise<Record<string, unknown> | null> {
  const response = await http.get(`/ai/assistant/conversations/${encodeURIComponent(conversationId)}/runtime`, {
    skipPageLoading: true,
  });
  const value = response.data?.data;
  return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
}

async function receiveStream(conversationId: string, endpoint: string, onEvent: (event: MainAgentEvent) => void, signal?: AbortSignal, content?: string) {
  const token = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
  const tokenName = localStorage.getItem(AUTH_TOKEN_NAME_STORAGE_KEY) || 'satoken';
  const headers = new Headers({ Accept: 'text/event-stream', 'Content-Type': 'application/json' });
  if (token) headers.set(tokenName, token);
  const baseURL = String(http.defaults.baseURL || '/api').replace(/\/$/, '');
  const response = await fetch(`${baseURL}/ai/assistant/conversations/${encodeURIComponent(conversationId)}/${endpoint}`, {
    method: content === undefined ? 'GET' : 'POST', headers, body: content === undefined ? undefined : JSON.stringify({ content }), signal,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.message || `AI 请求失败（${response.status}）`);
  }
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('AI 服务未返回约定的事件流');
  let finished = false;
  await readSseStream(response.body, (frame) => {
    if (token !== localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)) throw new Error('登录会话已变更，请重新进入页面');
    const name = frame.event as MainAgentEventName;
    if (!events.has(name)) throw new Error(`未知 AI 事件：${frame.event}`);
    const data: unknown = JSON.parse(frame.data);
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('AI 事件数据格式错误');
    if (name === 'run_completed' || name === 'run_failed') finished = true;
    onEvent({ event: name, data: normalizeEventData(data as Record<string, unknown>) });
  }, signal);
  if (!finished && !signal?.aborted) throw new Error('连接已中断，本轮结果未知，请重新打开会话查看。');
}

/** 提交追问答案；本轮运行流保持打开。 */
export async function submitMainAgentAnswer(conversationId: string, questionRequestId: string, answers: QuestionAnswer[], note: string) {
  await http.post(`/ai/assistant/conversations/${encodeURIComponent(conversationId)}/questions/${encodeURIComponent(questionRequestId)}/answer`, { answers, note }, { skipPageLoading: true });
}
