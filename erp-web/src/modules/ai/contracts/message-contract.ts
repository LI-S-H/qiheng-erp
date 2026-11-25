import { normalizeStringId } from '@/shared/utils/api-normalizers';
import type { AiConversationMessage, AiMessageContent } from '../types';
import { normalizeQuestions } from '../clarification/clarification-contract';
import { normalizeReportCard, normalizeReportStep } from './report-contract';

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

/** 时长字段可能是数字或数字字符串；无法解析时按 0 处理。 */
function elapsedMillis(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
    return value;
  }
  if (typeof value === 'string' && /^\d+$/.test(value)) {
    return Number(value);
  }
  return 0;
}

export function normalizeMessage(value: unknown): AiConversationMessage {
  const item = object(value);
  if (item.role !== 'user' && item.role !== 'assistant' && item.role !== 'tool') {
    throw new Error('消息角色不符合接口契约');
  }
  if (!['USER_INPUT', 'ASSISTANT_FINAL', 'ASSISTANT_STEP', 'TOOL_CALL', 'TOOL_RESULT', 'ANALYSIS_STEP', 'REPORT_STEP'].includes(String(item.kind))) {
    throw new Error('消息种类不符合接口契约');
  }
  const source = object(item.content);
  const data = object(source.data);
  let content: AiMessageContent;
  if (source.type === 'TOOL_CALL') {
    // 追问带问题参数，渲染成问答卡片；普通工具只展示工具名、文案与耗时。
    if (data.arguments !== undefined) {
      const args = typeof data.arguments === 'string' ? JSON.parse(data.arguments) : data.arguments;
      content = { type: 'CLARIFICATION', data: { text: '', callId: requiredText(data.callId, 'callId'), questions: normalizeQuestions(object(args).questions, 'questions') } };
    } else {
      content = {
        type: 'TOOL_STEP',
        data: {
          callId: requiredText(data.callId, 'callId'),
          name: typeof data.name === 'string' ? data.name : '',
          label: requiredText(data.label, 'label'),
          status: data.status === 'failed' ? 'failed' : 'done',
          // 后端把 long 统一序列化为字符串，避免前端精度丢失，这里两种形态都接受。
          durationMs: elapsedMillis(data.durationMs),
        },
      };
    }
  } else if (source.type === 'TOOL_RESULT') {
    let result = data.result;
    if (typeof result === 'string') result = JSON.parse(result);
    const resultData = object(result);
    const answers = resultData.answers;
    if (!Array.isArray(answers)) {
      throw new Error('追问结果缺少答案');
    }
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
  } else if (source.type === 'ANALYSIS') {
    content = normalizeAnalysis(source);
  } else if (source.type === 'PURCHASE_ORDER') {
    content = normalizeOrderRecord(data);
  } else if (source.type === 'ORDER_TOOL') {
    content = normalizeOrderTool(data);
  } else if (source.type === 'REPORT') {
    if (!['RUNNING', 'COMPLETED', 'FAILED'].includes(String(data.status)) || !Array.isArray(data.steps)) {
      throw new Error('报告过程字段无效');
    }
    content = { type: 'REPORT', data: {
      callId: requiredText(data.toolCallId, 'toolCallId'),
      ...(data.taskId === undefined ? {} : { taskId: requiredText(data.taskId, 'taskId') }),
      status: data.status as 'RUNNING' | 'COMPLETED' | 'FAILED',
      steps: data.steps.map(normalizeReportStep),
      ...(data.report ? { report: normalizeReportCard(data.report) } : {}),
    } };
  } else {
    content = normalizeVisibleContent(item.content);
  }
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
  if (item.kind !== 'TOOL_CALL' && item.kind !== 'TOOL_RESULT') {
    return normalizeMessage(value);
  }
  try {
    return normalizeMessage(value);
  } catch {
    return null;
  }
}

/** 采购单动作记录：只保留动作、状态、单号和结果说明。 */
function normalizeOrderRecord(data: Record<string, unknown>): AiMessageContent {
  const status = String(data.status);
  if (!['created', 'updated', 'edited', 'rejected', 'failed', 'submitted', 'cancelled'].includes(status)) {
    throw new Error('采购单动作状态无效');
  }
  const optionalText = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : undefined;
  const textList = (value: unknown) => Array.isArray(value)
    ? value.map(item => optionalText(item)).filter((item): item is string => Boolean(item)) : undefined;
  const action = optionalText(data.action);
  return {
    type: 'PURCHASE_ORDER',
    data: {
      action: action === 'CREATE' || action === 'UPDATE' || action === 'SUBMIT' || action === 'CANCEL' ? action : undefined,
      status: status as 'created' | 'updated' | 'edited' | 'rejected' | 'failed' | 'submitted' | 'cancelled',
      purchaseOrderId: optionalText(data.purchaseOrderId),
      orderNo: optionalText(data.orderNo),
      orderStatus: optionalText(data.orderStatus),
      changedFields: textList(data.changedFields),
      userNote: optionalText(data.userNote),
      reasons: textList(data.reasons),
      suggestion: optionalText(data.suggestion),
      error: optionalText(data.error),
      reason: optionalText(data.reason),
    },
  };
}

/** 草稿工具调用与结果：调用带参数，结果带业务回执。 */
function normalizeOrderTool(data: Record<string, unknown>): AiMessageContent {
  const optionalText = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : undefined;
  const action = optionalText(data.action);
  const status = optionalText(data.status);
  const orderType = optionalText(data.orderType);
  return {
    type: 'ORDER_TOOL',
    data: {
      callId: requiredText(data.callId, 'callId'),
      name: requiredText(data.name, 'name'),
      ...(optionalText(data.label) ? { label: optionalText(data.label) } : {}),
      ...(action === 'CREATE' || action === 'UPDATE' || action === 'SUBMIT' || action === 'CANCEL' ? { action } : {}),
      ...(orderType === 'SALES' ? { orderType } : {}),
      ...(status === 'waiting' ? { status } : {}),
      ...(optionalText(data.arguments) ? { arguments: optionalText(data.arguments) } : {}),
      ...(optionalText(data.result) ? { result: optionalText(data.result) } : {}),
      ...(optionalText(data.salesOrderId) ? { salesOrderId: optionalText(data.salesOrderId) } : {}),
      ...(optionalText(data.orderNo) ? { orderNo: optionalText(data.orderNo) } : {}),
      ...(optionalText(data.reason) ? { reason: optionalText(data.reason) } : {}),
    },
  };
}

/** 校验并归一化分析过程消息；步骤只保留思路文字与工具文案。 */
function normalizeAnalysis(value: unknown): AiMessageContent {
  const body = object(value);
  const data = object(body.data);
  const steps = (Array.isArray(data.steps) ? data.steps : []).map(step => {
    const item = object(step);
    const tools = (Array.isArray(item.tools) ? item.tools : []).map(tool => {
      const entry = object(tool);
      return { name: text(entry.name, 'name'), label: text(entry.label, 'label') };
    });
    return { text: text(item.text, 'text'), tools };
  });
  return {
    type: 'ANALYSIS',
    data: {
      callId: requiredText(data.toolCallId, 'toolCallId'),
      goal: text(data.goal, 'goal'),
      steps,
      completion: text(data.completion, 'completion'),
      summary: text(data.summary, 'summary'),
    },
  };
}

/** 校验并归一化可见消息的展示内容（{type,data}），历史流水与实时产物事件共用。 */
export function normalizeVisibleContent(value: unknown): AiMessageContent {
  const body = object(value);
  const data = object(body.data);
  let content: AiMessageContent;
  if (body.type === 'TEXT') {
    content = { type: 'TEXT', data: { text: text(data.text, 'text'), ...(typeof data.callId === 'string' && data.callId ? { callId: data.callId } : {}) } };
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
