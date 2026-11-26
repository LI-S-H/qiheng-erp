import type { ReturnHandlingType, ReturnReasonCode, ReturnType } from '@/modules/returns/types';

/** 退货草稿：与工具参数结构一致，只包含用户可决策的业务字段。 */
export interface ReturnDraftItem {
  sourceOrderItemId: string;
  requestedQty: number;
  remark: string | null;
}

export interface ReturnDraftHeader {
  /** 修改已有退货单时必填。 */
  returnOrderId?: string | null;
  returnType: ReturnType;
  sourceOrderId: string;
  warehouseId: string;
  expectedExecutionDate: string | null;
  handlingType: ReturnHandlingType;
  reasonCode: ReturnReasonCode;
  returnReason: string;
  remark: string | null;
  items: ReturnDraftItem[];
}

/** 后端等待通道发出的一份待确认退货草稿。 */
export interface ReturnDraftRequest {
  draftRequestId: string;
  toolCallId?: string;
  action?: 'CREATE' | 'UPDATE';
  draft: ReturnDraftHeader;
}

/** 等待用户确认的退货单提交或取消动作。 */
export interface ReturnActionRequest {
  actionRequestId: string;
  toolCallId?: string;
  returnType?: 'SALES_RETURN' | 'PURCHASE_RETURN';
  action: 'SUBMIT' | 'CANCEL';
  returnOrderId: string;
  orderNo: string;
  reason: string;
}

/** 用户处置结果；提交后由工具交回模型。 */
export type ReturnDraftReceipt =
  | {
      status: 'created';
      orderNo: string;
      returnOrderId: string;
      returnType: ReturnType;
      orderStatus?: string;
    }
  | {
      status: 'updated';
      orderNo: string;
      returnOrderId: string;
      returnType: ReturnType;
      orderStatus?: string;
    }
  | {
      status: 'edited';
      orderNo: string;
      returnOrderId: string;
      returnType: ReturnType;
      orderStatus?: string;
      finalParams: ReturnDraftHeader;
      /** 卡片打开时的参数，作为“用户改了什么”的比较基线。 */
      originalParams: ReturnDraftHeader;
      userNote: string;
    }
  | {
      status: 'failed';
      orderNo?: string;
      returnOrderId?: string;
      returnType: ReturnType;
      error: string;
      finalParams?: ReturnDraftHeader;
    }
  | {
      status: 'rejected';
      orderNo?: string;
      returnOrderId?: string;
      returnType: ReturnType;
      reasons: string[];
      suggestion: string;
    };

export type ReturnActionReceipt =
  | {
      status: 'submitted';
      returnOrderId: string;
      returnType: ReturnType;
      orderNo: string;
      orderStatus?: string;
      executionDate?: string;
    }
  | {
      status: 'cancelled';
      returnOrderId: string;
      returnType: ReturnType;
      orderNo: string;
      orderStatus?: string;
      reason?: string;
    }
  | {
      status: 'rejected';
      returnOrderId: string;
      returnType: ReturnType;
      orderNo: string;
      reason?: string;
    }
  | {
      status: 'failed';
      returnOrderId: string;
      returnType: ReturnType;
      orderNo?: string;
      error: string;
      orderParams?: Record<string, unknown>;
    };

/** 用户驳回草稿时的常见原因。 */
export const RETURN_DRAFT_REJECT_REASONS = [
  '数量不合适',
  '退货原因不对',
  '预计执行日期不对',
  '处理方式不对',
  '其他',
] as const;

const RETURN_TYPES: ReturnType[] = ['SALES_RETURN', 'PURCHASE_RETURN'];
const HANDLING_TYPES: ReturnHandlingType[] = ['REFUND', 'EXCHANGE', 'OTHER'];
const REASON_CODES: ReturnReasonCode[] = [
  'QUALITY_ISSUE', 'DAMAGED', 'WRONG_ITEM', 'QUANTITY_ERROR',
  'SPEC_MISMATCH', 'NO_LONGER_NEEDED', 'OTHER',
];

/** 新事件用 callId + arguments；旧事件用 draftRequestId + draft；统一成同一结构。 */
function normalizeDraftSource(data: Record<string, unknown>): Record<string, unknown> {
  if (typeof data.callId !== 'string' || typeof data.arguments !== 'string') {
    return data;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(data.arguments);
  } catch {
    return { ...data, draftRequestId: data.callId, draft: null };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ...data, draftRequestId: data.callId, draft: null };
  }
  const root = parsed as Record<string, unknown>;
  const draft = root.request && typeof root.request === 'object' && !Array.isArray(root.request)
    ? root.request as Record<string, unknown>
    : root;
  return {
    ...data,
    draftRequestId: data.callId,
    draft: {
      ...draft,
      returnType: draft.returnType ?? data.returnType,
      returnOrderId: draft.returnOrderId ?? data.returnOrderId,
    },
  };
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[], label: string): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) {
    throw new Error(`${label}枚举值无效`);
  }
  return value as T;
}

function idText(value: unknown): string {
  if (typeof value === 'string') {
    return value.trim();
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value);
  }
  return '';
}

function positiveNumber(value: unknown, label: string): number {
  const number = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(number) || number <= 0) {
    throw new Error(`${label}必须大于 0`);
  }
  return number;
}

/** 把后端返回值收敛成可校验的退货草稿。 */
export function parseReturnDraftRequest(data: Record<string, unknown>): ReturnDraftRequest {
  const source = normalizeDraftSource(data);
  if (!source.draft || typeof source.draft !== 'object' || Array.isArray(source.draft)) {
    throw new Error('退货草稿缺少单据内容');
  }
  const value = source.draft as Record<string, unknown>;
  const action = source.action === 'UPDATE' ? 'UPDATE' : 'CREATE';
  const returnType = enumValue(value.returnType, RETURN_TYPES, '退货方向');
  const sourceOrderId = idText(value.sourceOrderId);
  const warehouseId = idText(value.warehouseId);
  if (!sourceOrderId) {
    throw new Error('退货草稿缺少来源单');
  }
  if (!warehouseId) {
    throw new Error('退货草稿缺少退货仓库');
  }
  const items = Array.isArray(value.items) ? value.items : [];
  const normalizedItems = items.map((item, index) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      throw new Error(`退货草稿第 ${index + 1} 行格式错误`);
    }
    const line = item as Record<string, unknown>;
    const sourceItemId = idText(line.sourceOrderItemId);
    if (!sourceItemId) {
      throw new Error(`退货草稿第 ${index + 1} 行缺少来源明细`);
    }
    return {
      sourceOrderItemId: sourceItemId,
      requestedQty: positiveNumber(line.requestedQty, `退货草稿第 ${index + 1} 行数量`),
      remark: typeof line.remark === 'string' && line.remark.trim() ? line.remark.trim() : null,
    };
  });
  if (normalizedItems.length === 0) {
    throw new Error('退货草稿没有明细');
  }
  return {
    draftRequestId: String(source.draftRequestId ?? ''),
    toolCallId: typeof source.toolCallId === 'string' ? source.toolCallId : undefined,
    action,
    draft: {
      returnOrderId: idText(value.returnOrderId) || null,
      returnType,
      sourceOrderId,
      warehouseId,
      expectedExecutionDate: typeof value.expectedExecutionDate === 'string' && value.expectedExecutionDate
        ? value.expectedExecutionDate : null,
      handlingType: value.handlingType === undefined || value.handlingType === null || value.handlingType === ''
        ? 'REFUND' : enumValue(value.handlingType, HANDLING_TYPES, '处理方式'),
      reasonCode: enumValue(value.reasonCode, REASON_CODES, '退货原因'),
      returnReason: typeof value.returnReason === 'string' ? value.returnReason.trim() : '',
      remark: typeof value.remark === 'string' && value.remark.trim() ? value.remark.trim() : null,
      items: normalizedItems,
    },
  };
}

/** 把后端动作事件收敛成可校验结构。 */
export function parseReturnActionRequest(data: Record<string, unknown>): ReturnActionRequest | null {
  if (typeof data.callId !== 'string' || (data.action !== 'SUBMIT' && data.action !== 'CANCEL')) {
    return null;
  }
  const returnOrderId = idText(data.returnOrderId);
  if (!returnOrderId) {
    return null;
  }
  return {
    actionRequestId: data.callId,
    toolCallId: data.callId,
    action: data.action,
    returnType: data.returnType === 'SALES_RETURN' || data.returnType === 'PURCHASE_RETURN' ? data.returnType : undefined,
    returnOrderId,
    orderNo: typeof data.orderNo === 'string' ? data.orderNo : '',
    reason: typeof data.reason === 'string' ? data.reason : '',
  };
}