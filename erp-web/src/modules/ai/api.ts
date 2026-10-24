import { normalizeMessage, compareMessages } from './message-contract';
import { http } from '@/api/http';
import { normalizeStringId } from '@/shared/utils/api-normalizers';
import type { Result } from '@/shared/types/api';
import type {
  AiAssistantOverview,
  AiConversationDetail,
  AiConversationMessagePage,
  AiConversationSummary,
  AiConversationUpdateRequest,
  AiScheduledTask,
  AiScheduledTaskPage,
  AiScheduledTaskRunAccepted,
  AiScheduledTaskStatus,
  AiScheduledTaskUpdateRequest,
} from './types';

function record(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`接口字段 ${field} 结构无效`);
  }
  return value as Record<string, unknown>;
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' && value ? value : null;
}

function normalizeConversationSummary(value: unknown): AiConversationSummary {
  const item = record(value, 'conversation');
  return {
    conversationId: normalizeStringId(item.conversationId, 'conversationId'),
    title: String(item.title || ''),
    description: String(item.description || ''),
    updatedAt: String(item.updatedAt || ''),
  };
}

function normalizeRunStatus(value: unknown): AiConversationDetail['runStatus'] {
  const allowed = [
    'IDLE',
    'RUNNING',
    'WAITING_HUMAN',
    'PLAN_READY',
    'NEEDS_READY',
    'FORMAT_RETRY_EXHAUSTED',
    'FAILED',
    'INTERRUPTED',
  ];
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new Error('会话运行状态不符合接口契约');
  }
  return value as AiConversationDetail['runStatus'];
}

function normalizeConversationDetail(value: unknown): AiConversationDetail {
  const item = record(value, 'conversation');
  return {
    ...normalizeConversationSummary(item),
    running: item.running === true,
    runStatus: normalizeRunStatus(item.runStatus),
    runNode: optionalText(item.runNode),
    runLabel: optionalText(item.runLabel),
  };
}

function normalizeMessagePage(value: unknown): AiConversationMessagePage {
  const item = record(value, 'messagePage');
  if (!Array.isArray(item.records)) {
    throw new Error('会话历史缺少消息列表');
  }
  const pageNum = Number(item.pageNum);
  const pageSize = Number(item.pageSize);
  const total = Number(item.total);
  if (
    !Number.isInteger(pageNum) ||
    pageNum < 1 ||
    !Number.isInteger(pageSize) ||
    pageSize < 1 ||
    !Number.isFinite(total) ||
    total < 0
  ) {
    throw new Error('会话历史分页字段不符合约束');
  }
  return { records: item.records.map(normalizeMessage), pageNum, pageSize, total };
}

/** 快捷任务入口尚未落地，后端固定返回空数组，前端不补本地兜底数据。 */
function normalizeOverview(value: unknown): AiAssistantOverview {
  const item = record(value, 'overview');
  if (!Array.isArray(item.conversations)) {
    throw new Error('AI 概览缺少会话列表');
  }
  return {
    refreshedAt: String(item.refreshedAt || ''),
    quickPrompts: [],
    conversations: item.conversations.map(normalizeConversationSummary),
  };
}

export async function getAiAssistantOverview(signal?: AbortSignal) {
  const response = await http.get<Result<unknown>>('/ai/assistant/overview', {
    signal,
    skipPageLoading: true,
    suppressErrorToast: true,
  });
  return normalizeOverview(response.data.data);
}

export async function createAiConversation() {
  const response = await http.post<Result<unknown>>('/ai/assistant/conversations', undefined, {
    skipPageLoading: true,
    suppressErrorToast: true,
  });
  return normalizeConversationDetail(response.data.data);
}

export async function getAiConversationDetail(conversationId: string, signal?: AbortSignal) {
  const response = await http.get<Result<unknown>>(
    `/ai/assistant/conversations/${conversationId}`,
    {
      signal,
      skipPageLoading: true,
      suppressErrorToast: true,
    },
  );
  return normalizeConversationDetail(response.data.data);
}

export async function renameAiConversation(
  conversationId: string,
  payload: AiConversationUpdateRequest,
) {
  await http.patch(`/ai/assistant/conversations/${conversationId}`, payload, {
    skipPageLoading: true,
    suppressErrorToast: true,
  });
}

export async function deleteAiConversation(conversationId: string) {
  await http.delete(`/ai/assistant/conversations/${conversationId}`, {
    skipPageLoading: true,
    suppressErrorToast: true,
  });
}

export async function getAiConversationMessagePage(
  conversationId: string,
  pageNum: number,
  pageSize: number,
  signal?: AbortSignal,
) {
  const response = await http.get<Result<unknown>>(
    `/ai/assistant/conversations/${conversationId}/messages`,
    {
      params: { pageNum, pageSize },
      signal,
      skipPageLoading: true,
      suppressErrorToast: true,
    },
  );
  return normalizeMessagePage(response.data.data);
}

/** 只定位最近一页，旧消息由用户向上加载，避免每次执行后重读全量历史。 */
export async function getAiConversationHistory(conversationId: string, signal?: AbortSignal) {
  const first = await getAiConversationMessagePage(conversationId, 1, 50, signal);
  const lastPage = Math.max(1, Math.ceil(first.total / first.pageSize));
  const latest =
    lastPage === 1
      ? first
      : await getAiConversationMessagePage(conversationId, lastPage, first.pageSize, signal);
  return { ...latest, records: latest.records.sort(compareMessages) };
}

// 页面组件暂存供以后接回；后端任务能力未实现前，不发送不存在的接口或生成假报告。
export async function getAiScheduledTaskPage(): Promise<AiScheduledTaskPage> {
  throw new Error('经营任务暂未开放');
}
export async function runAiScheduledTask(_taskId: string): Promise<AiScheduledTaskRunAccepted> {
  throw new Error('经营任务暂未开放');
}
export async function updateAiScheduledTaskStatus(
  _taskId: string,
  _status: AiScheduledTaskStatus,
): Promise<AiScheduledTask> {
  throw new Error('经营任务暂未开放');
}
export async function createAiScheduledTask(
  _payload: AiScheduledTaskUpdateRequest,
): Promise<AiScheduledTask> {
  throw new Error('经营任务暂未开放');
}
export async function updateAiScheduledTask(
  _taskId: string,
  _payload: AiScheduledTaskUpdateRequest,
): Promise<AiScheduledTask> {
  throw new Error('经营任务暂未开放');
}
