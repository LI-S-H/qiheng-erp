import { http } from '@/api/http';

/** 后端等待通道发出的一次采购单动作确认。 */
export interface PurchaseActionRequest {
  actionRequestId: string;
  toolCallId?: string;
  action: 'SUBMIT' | 'CANCEL';
  purchaseOrderId: string;
  orderNo: string;
  reason: string;
}

/** 用户对提交或取消的处置结果。 */
export type PurchaseActionReceipt =
  | { status: 'submitted'; purchaseOrderId: string; orderNo: string; orderStatus: string; arrivalDate?: string }
  | { status: 'cancelled'; purchaseOrderId: string; orderNo: string; orderStatus: string; reason?: string }
  | { status: 'rejected'; purchaseOrderId: string; orderNo: string; reason?: string }
  | {
      status: 'failed';
      purchaseOrderId: string;
      orderNo: string;
      error: string;
      orderParams?: Record<string, unknown>;
    };

/** 把后端事件收敛成可校验的动作请求；兼容 callId + arguments 新结构和旧结构。 */
export function parsePurchaseActionRequest(data: Record<string, unknown>): PurchaseActionRequest {
  const source = normalizeActionSource(data);
  const action = source.action === 'SUBMIT' || source.action === 'CANCEL' ? source.action : null;
  if (!action || typeof source.actionRequestId !== 'string' || !source.actionRequestId) {
    throw new Error('采购单动作格式错误');
  }
  if (typeof source.purchaseOrderId !== 'string' || !source.purchaseOrderId) {
    throw new Error('采购单动作缺少单据编号');
  }
  return {
    actionRequestId: source.actionRequestId,
    toolCallId: typeof source.toolCallId === 'string' ? source.toolCallId : undefined,
    action,
    purchaseOrderId: source.purchaseOrderId,
    orderNo: typeof source.orderNo === 'string' ? source.orderNo : '',
    reason: typeof source.reason === 'string' ? source.reason : '',
  };
}

/** 新事件用 callId + arguments，旧事件直接带 actionRequestId；统一成旧结构。 */
function normalizeActionSource(data: Record<string, unknown>): Record<string, unknown> {
  if (typeof data.callId !== 'string') {
    return data;
  }
  let parsed: unknown = null;
  if (typeof data.arguments === 'string') {
    try {
      parsed = JSON.parse(data.arguments);
    } catch {
      parsed = null;
    }
  }
  const root = parsed && typeof parsed === 'object' && !Array.isArray(parsed)
    ? (parsed as Record<string, unknown>)
    : {};
  const args = root.request && typeof root.request === 'object' && !Array.isArray(root.request)
    ? root.request as Record<string, unknown>
    : root;
  return {
    ...data,
    actionRequestId: data.callId,
    purchaseOrderId: data.purchaseOrderId ?? args.purchaseOrderId,
    orderNo: data.orderNo ?? args.orderNo,
    reason: data.reason ?? args.reason,
  };
}

/** 把用户处置结果交回等待中的模型。 */
export async function resolvePurchaseAction(
  conversationId: string,
  request: PurchaseActionRequest,
  receipt: PurchaseActionReceipt,
) {
  await http.post(
    `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/actions/${encodeURIComponent(request.actionRequestId)}/resolve`,
    receipt,
    { skipPageLoading: true },
  );
}