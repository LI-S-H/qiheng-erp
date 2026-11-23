import { http } from '@/api/http';

/** 后端等待通道发出的一次销售单动作确认。 */
export interface SalesActionRequest {
  actionRequestId: string;
  toolCallId?: string;
  action: 'SUBMIT' | 'CANCEL';
  salesOrderId: string;
  orderNo: string;
  reason: string;
}

/** 用户对提交或取消的处置结果。 */
export type SalesActionReceipt =
  | { status: 'submitted'; salesOrderId: string; orderNo: string; orderStatus: string; deliveryDate?: string }
  | { status: 'cancelled'; salesOrderId: string; orderNo: string; orderStatus: string; reason?: string }
  | { status: 'rejected'; salesOrderId: string; orderNo: string; reason?: string }
  | {
      status: 'failed';
      salesOrderId: string;
      orderNo: string;
      error: string;
      orderParams?: Record<string, unknown>;
    };

/** 把后端事件收敛成可校验的动作请求；格式异常时按缺少动作处理。 */
export function parseSalesActionRequest(data: Record<string, unknown>): SalesActionRequest {
  const action = data.action === 'SUBMIT' || data.action === 'CANCEL' ? data.action : null;
  if (!action || typeof data.actionRequestId !== 'string' || !data.actionRequestId) {
    throw new Error('销售单动作格式错误');
  }
  if (typeof data.salesOrderId !== 'string' || !data.salesOrderId) {
    throw new Error('销售单动作缺少单据编号');
  }
  return {
    actionRequestId: data.actionRequestId,
    toolCallId: typeof data.toolCallId === 'string' ? data.toolCallId : undefined,
    action,
    salesOrderId: data.salesOrderId,
    orderNo: typeof data.orderNo === 'string' ? data.orderNo : '',
    reason: typeof data.reason === 'string' ? data.reason : '',
  };
}

/** 把用户处置结果交回等待中的模型。 */
export async function resolveSalesAction(
  conversationId: string,
  request: SalesActionRequest,
  receipt: SalesActionReceipt,
) {
  await http.post(
    `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/sales-actions/${encodeURIComponent(request.actionRequestId)}/resolve`,
    receipt,
    { skipPageLoading: true },
  );
}