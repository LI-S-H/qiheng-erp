import { normalizeHistoryMessage, compareMessages } from './contracts/message-contract';
import { normalizeReportCard, normalizeReportDetail } from './contracts/report-contract';
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
    awaitingUser: item.awaitingUser === true,
  };
}

function normalizeConversationDetail(value: unknown): AiConversationDetail {
  return normalizeConversationSummary(value);
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
  return { records: item.records.map(normalizeHistoryMessage).filter(message => message !== null), pageNum, pageSize, total };
}

/** 快捷任务入口尚未落地，后端固定返回空数组，前端不补本地兜底数据。 */
function normalizeOverview(value: unknown): AiAssistantOverview {
  const item = record(value, 'overview');
  if (!Array.isArray(item.conversations)) {
    throw new Error('AI 概览缺少会话列表');
  }
  return {
    refreshedAt: String(item.refreshedAt || ''),
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

/** 按活动时间和 ID 获取一批会话；游标只由服务端生成。 */
export async function getAiConversationPage(cursor?: { time: string; id: string }, signal?: AbortSignal) {
  const response = await http.get<Result<unknown>>('/ai/assistant/conversations', {
    params: cursor ? { beforeTime: cursor.time, beforeId: cursor.id } : undefined,
    signal, skipPageLoading: true, suppressErrorToast: true,
  });
  const page = record(response.data.data, 'conversationPage');
  if (!Array.isArray(page.records) || typeof page.hasMore !== 'boolean') throw new Error('会话分页结构不符合接口契约');
  const nextTime = optionalText(page.nextBeforeTime);
  const nextId = page.nextBeforeId == null ? null : normalizeStringId(page.nextBeforeId, 'nextBeforeId');
  if (page.hasMore && (!nextTime || !nextId)) throw new Error('会话分页缺少下一页游标');
  return { records: page.records.map(normalizeConversationSummary), hasMore: page.hasMore, nextTime, nextId };
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

/** 只定位最近一页，旧消息由用户向上加载，避免每次执行后重读全量历史；一页按轮次计。 */
export async function getAiConversationHistory(conversationId: string, signal?: AbortSignal) {
  const first = await getAiConversationMessagePage(conversationId, 1, 20, signal);
  const lastPage = Math.max(1, Math.ceil(first.total / first.pageSize));
  const latest =
    lastPage === 1
      ? first
      : await getAiConversationMessagePage(conversationId, lastPage, first.pageSize, signal);
  return { ...latest, records: latest.records.sort(compareMessages) };
}

/** 只读取可见历史轮次的卡片，正文由详情页单独请求。 */
export async function getAiConversationReports(conversationId: string, requestIds: string[], signal?: AbortSignal) {
  const chunks: string[][] = [];
  for (let start = 0; start < requestIds.length; start += 100) {
    chunks.push(requestIds.slice(start, start + 100));
  }
  const cards = await Promise.all(chunks.map(async (chunk) => {
    const params = new URLSearchParams();
    chunk.forEach(requestId => params.append('requestId', requestId));
    const response = await http.get<Result<unknown>>(
      `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/reports`,
      { params, signal, skipPageLoading: true, suppressErrorToast: true },
    );
    if (!Array.isArray(response.data.data)) {
      throw new Error('会话报告列表结构无效');
    }
    return response.data.data.map(normalizeReportCard);
  }));
  return cards.flat();
}

/** 报告权限仍由服务端按会话和报告归属校验。 */
export async function getAiReport(conversationId: string, reportId: string, signal?: AbortSignal) {
  const response = await http.get<Result<unknown>>(
    `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/reports/${encodeURIComponent(reportId)}`,
    { signal, skipPageLoading: true, suppressErrorToast: true },
  );
  return normalizeReportDetail(response.data.data);
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
