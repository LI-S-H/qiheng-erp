import { normalizeStringId } from '@/shared/utils/api-normalizers';
import type { AiConversationMessage, AiMessageContent } from './types';
import { normalizePlan, normalizeQuestions } from './plan-contract';
import { normalizeNeeds } from './needs-contract';

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('消息内容结构不符合接口契约');
  }
  return value as Record<string, unknown>;
}
function text(value: unknown, field: string): string {
  if (typeof value !== 'string') {
    throw new Error(`消息字段 ${field} 必须是字符串`);
  }
  return value;
}
function requiredText(value: unknown, field: string): string {
  const result = text(value, field).trim();
  if (!result) {
    throw new Error(`消息字段 ${field} 不能为空`);
  }
  return result;
}

export function normalizeMessage(value: unknown): AiConversationMessage {
  const item = object(value);
  if (item.role !== 'user' && item.role !== 'assistant') {
    throw new Error('消息角色不符合接口契约');
  }
  if (item.kind !== 'USER_INPUT' && item.kind !== 'ASSISTANT_FINAL') {
    throw new Error('消息种类不符合接口契约');
  }
  const content = normalizeVisibleContent(item.content);
  const seq = requiredText(item.seq, 'seq');
  if (!/^\d+$/.test(seq)) {
    throw new Error('消息序号必须是非负整数字符串');
  }
  return {
    messageId: normalizeStringId(item.messageId, 'messageId'),
    requestId: text(item.requestId, 'requestId'),
    seq,
    role: item.role,
    kind: item.kind,
    content,
    createdAt: text(item.createdAt, 'createdAt'),
  };
}

/** 校验并归一化可见消息的展示内容（{type,data}），历史流水与实时产物事件共用。 */
export function normalizeVisibleContent(value: unknown): AiMessageContent {
  const body = object(value);
  const data = object(body.data);
  let content: AiMessageContent;
  if (body.type === 'TEXT') {
    content = { type: 'TEXT', data: { text: text(data.text, 'text') } };
    if (data.replyToMessageId !== undefined && data.replyToMessageId !== null) {
      content.data.replyToMessageId = normalizeStringId(data.replyToMessageId, 'replyToMessageId');
    }
  } else if (body.type === 'ERROR') {
    content = { type: 'ERROR', data: { text: text(data.text, 'text') } };
    // non_null 会省略可选字段；保留原始分类和任务关联，不从错误消息推断当前会话状态。
    if (data.status !== undefined && data.status !== null) content.data.status = requiredText(data.status, 'status');
    if (data.threadId !== undefined && data.threadId !== null) content.data.threadId = requiredText(data.threadId, 'threadId');
  } else if (body.type === 'PLAN' || body.type === 'CLARIFICATION' || body.type === 'NEEDS') {
    const shared = {
      text: text(data.text, 'text'),
      threadId:
        typeof data.threadId === 'string' && data.threadId.trim() ? data.threadId : undefined,
    };
    content =
      body.type === 'PLAN'
        ? { type: 'PLAN', data: { ...shared, plan: normalizePlan(data.plan) } }
        : body.type === 'NEEDS'
          ? { type: 'NEEDS', data: { ...shared, needs: normalizeNeeds(data.needs) } }
          : {
            type: 'CLARIFICATION',
            data: {
              ...shared,
              questions: normalizeQuestions(data.questions, 'questions'),
              pauseId:
                typeof data.pauseId === 'string' && data.pauseId.trim() ? data.pauseId : undefined,
            },
          };
  } else {
    throw new Error('未知的用户可见消息类型');
  }
  return content;
}

/** 避免 BIGINT 转 Number 丢失精度，分页与 SSE 合并始终按 seq 排序。 */
export function compareMessages(a: AiConversationMessage, b: AiConversationMessage): number {
  const left = BigInt(a.seq);
  const right = BigInt(b.seq);
  return left < right ? -1 : left > right ? 1 : a.messageId.localeCompare(b.messageId);
}
