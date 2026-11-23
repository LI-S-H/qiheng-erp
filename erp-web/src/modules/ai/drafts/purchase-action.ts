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

/** 把后端事件收敛成可校验的动作请求；格式异常时按缺少动作处理。 */
export function parsePurchaseActionRequest(data: Record<string, unknown>): PurchaseActionRequest {
  const action = data.action === 'SUBMIT' || data.action === 'CANCEL' ? data.action : null;
  if (!action || typeof data.actionRequestId !== 'string' || !data.actionRequestId) {
    throw new Error('采购单动作格式错误');
  }
  if (typeof data.purchaseOrderId !== 'string' || !data.purchaseOrderId) {
    throw new Error('采购单动作缺少单据编号');
  }
  return {
    actionRequestId: data.actionRequestId,
    toolCallId: typeof data.toolCallId === 'string' ? data.toolCallId : undefined,
    action,
    purchaseOrderId: data.purchaseOrderId,
    orderNo: typeof data.orderNo === 'string' ? data.orderNo : '',
    reason: typeof data.reason === 'string' ? data.reason : '',
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