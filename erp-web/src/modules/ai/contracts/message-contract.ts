import { normalizeStringId } from '@/shared/utils/api-normalizers';
import type { AiConversationMessage, AiMessageContent } from '../types';
import { normalizeQuestions } from '../clarification/clarification-contract';

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
  if (item.role !== 'user' && item.role !== 'assistant' && item.role !== 'tool') {
    throw new Error('消息角色不符合接口契约');
  }
  if (!['USER_INPUT', 'ASSISTANT_FINAL', 'ASSISTANT_STEP', 'TOOL_CALL', 'TOOL_RESULT'].includes(String(item.kind))) {
    throw new Error('消息种类不符合接口契约');
  }
  const source = object(item.content);
  const data = object(source.data);
  let content: AiMessageContent;
  if (source.type === 'TOOL_CALL') {
    const args = typeof data.arguments === 'string' ? JSON.parse(data.arguments) : data.arguments;
    content = { type: 'CLARIFICATION', data: { text: '', callId: requiredText(data.callId, 'callId'), questions: normalizeQuestions(object(args).questions, 'questions') } };
  } else if (source.type === 'TOOL_RESULT') {
    let result = data.result;
    if (typeof result === 'string') result = JSON.parse(result);
    const resultData = object(result);
    const answers = resultData.answers;
    if (!Array.isArray(answers)) throw new Error('追问结果缺少答案');
    const rendered = Array.isArray(answers) ? answers.map(value => {
      const answer = object(value);
      return [...(Array.isArray(answer.selectedOptions) ? answer.selectedOptions : []), answer.customText].filter(Boolean).join('、');
    }).filter(Boolean).join('；') : '';
    const note = typeof resultData.note === 'string' ? resultData.note.trim() : '';
    const entries = answers.map(value => {
      const answer = object(value);
      return [requiredText(answer.questionId, 'questionId'), [...(Array.isArray(answer.selectedOptions) ? answer.selectedOptions : []), answer.customText].filter(Boolean).join('、')];
    });
    const questionAnswers = Object.fromEntries(entries);
    content = { type: 'TEXT', data: { text: [rendered, note].filter(Boolean).join('；') || '已补充信息', callId: requiredText(data.callId, 'callId'), questionAnswers, note } };
  } else content = normalizeVisibleContent(item.content);
  const seq = requiredText(item.seq, 'seq');
  if (!/^\d+$/.test(seq)) {
    throw new Error('消息序号必须是非负整数字符串');
  }
  return {
    messageId: normalizeStringId(item.messageId, 'messageId'),
    requestId: text(item.requestId, 'requestId'),
    seq,
    role: item.role === 'tool' ? 'assistant' : item.role,
    kind: item.kind as AiConversationMessage['kind'],
    content,
    createdAt: text(item.createdAt, 'createdAt'),
  };
}

/** 忽略未能形成有效追问或答案的工具记录，其他历史仍正常展示。 */
export function normalizeHistoryMessage(value: unknown): AiConversationMessage | null {
  const item = object(value);
  if (item.kind !== 'TOOL_CALL' && item.kind !== 'TOOL_RESULT') return normalizeMessage(value);
  try {
    return normalizeMessage(value);
  } catch {
    return null;
  }
}

/** 校验并归一化可见消息的展示内容（{type,data}），历史流水与实时产物事件共用。 */
export function normalizeVisibleContent(value: unknown): AiMessageContent {
  const body = object(value);
  const data = object(body.data);
  let content: AiMessageContent;
  if (body.type === 'TEXT') {
    content = { type: 'TEXT', data: { text: text(data.text, 'text') } };
  } else {
    throw new Error('未知的用户可见消息类型');
  }
  return content;
}

/** 根据工具调用 ID 关联追问和回答，消息分页或顺序变化不影响对应关系。 */
export function clarificationAnswers(messages: AiConversationMessage[]): Map<string, string> {
  const answers = new Map<string, string>();
  for (const message of messages) {
    if (message.kind === 'TOOL_RESULT' && message.content.type === 'TEXT' && message.content.data.callId) {
      answers.set(message.content.data.callId, message.content.data.text);
    }
  }
  return answers;
}

/** 避免 BIGINT 转 Number 丢失精度，分页与 SSE 合并始终按 seq 排序。 */
export function compareMessages(a: AiConversationMessage, b: AiConversationMessage): number {
  const left = BigInt(a.seq);
  const right = BigInt(b.seq);
  return left < right ? -1 : left > right ? 1 : a.messageId.localeCompare(b.messageId);
}
