import { http } from '@/api/http';
import { getPurchaseOrderDetail } from '@/modules/purchase/api';
import { getSalesOrderDetail } from '@/modules/sales/api';
import {
  createPurchaseReturn,
  getPurchaseReturnDetail,
  listPurchaseReturnSourceItems,
  listPurchaseReturns,
  submitPurchaseReturn,
  cancelPurchaseReturn,
  updatePurchaseReturn,
} from '@/modules/purchase/returns/api';
import {
  createSalesReturn,
  getSalesReturnDetail,
  listSalesReturnSourceItems,
  listSalesReturns,
  submitSalesReturn,
  cancelSalesReturn,
  updateSalesReturn,
} from '@/modules/sales/returns/api';
import type {
  ReturnOrderDetail,
  ReturnOrderFormPayload,
  ReturnableSourceOrderItem,
  ReturnType,
} from '@/modules/returns/types';
import { ORDER_CONFIRM_REQUEST_CONFIG } from './order-confirm-request';
import type {
  ReturnActionReceipt,
  ReturnActionRequest,
  ReturnDraftHeader,
  ReturnDraftReceipt,
  ReturnDraftRequest,
} from './return-draft-types';

/** 退货确认页需要的展示信息：名称按来源单或退货单详情回查。 */
export interface ReturnDraftContext {
  sourceOrderNo: string;
  partyLabel: string;
  warehouseLabel: string;
  sourceStatus: string;
  returnNo: string;
  /** 已解析出的退货单数字 ID；创建场景为空。 */
  returnOrderId: string;
  version: number | null;
  items: ReturnableSourceOrderItem[];
}

/** 判断是否为后端要求的数字主键。 */
function isNumericId(value: string): boolean {
  return /^\d+$/.test(value);
}

/**
 * 把退货单 ID 收敛成数字主键。
 * 模型偶尔会把业务单号当成 returnOrderId 传入，这里按单号回查一次，避免确认卡片直接报错。
 */
export async function resolveReturnOrderId(value: string, orderNo?: string): Promise<string> {
  const candidate = (value || '').trim();
  if (isNumericId(candidate)) {
    return candidate;
  }
  const wanted = (orderNo || candidate).trim();
  if (!wanted) {
    throw new Error('退货单信息不完整，请重新发起');
  }
  const types: ReturnType[] = wanted.startsWith('PR')
    ? ['PURCHASE_RETURN']
    : wanted.startsWith('SR')
      ? ['SALES_RETURN']
      : ['PURCHASE_RETURN', 'SALES_RETURN'];
  for (const type of types) {
    const page = await (type === 'PURCHASE_RETURN' ? listPurchaseReturns : listSalesReturns)({
      returnNo: wanted,
      pageNum: 1,
      pageSize: 5,
    }).catch(() => null);
    const hit = page?.records.find(row => row.returnNo === wanted);
    if (hit) {
      return hit.returnOrderId;
    }
  }
  throw new Error(`未找到退货单 ${wanted}，请确认单号后重新发起`);
}

/** 读取退货草稿的展示信息；修改场景同时读取当前退货单详情。 */
export async function loadReturnDraftContext(draft: ReturnDraftHeader): Promise<ReturnDraftContext> {
  if (draft.returnOrderId) {
    const returnOrderId = await resolveReturnOrderId(draft.returnOrderId);
    const detail = await getReturnDetail(draft.returnType, returnOrderId);
    const items = await listSourceItems(draft.returnType, detail.sourceOrderId, returnOrderId);
    return {
      sourceOrderNo: detail.sourceOrderNo,
      partyLabel: `${detail.partyCode} ${detail.partyName}`.trim(),
      warehouseLabel: detail.warehouseName,
      sourceStatus: detail.status,
      returnNo: detail.returnNo,
      returnOrderId,
      version: detail.version,
      items,
    };
  }
  const header = await loadSourceHeader(draft.returnType, draft.sourceOrderId);
  const items = await listSourceItems(draft.returnType, draft.sourceOrderId);
  return { ...header, returnNo: '', returnOrderId: '', version: null, items };
}

/** 创建场景读取来源单表头，用于展示单号、往来单位和仓库。 */
async function loadSourceHeader(returnType: ReturnType, sourceOrderId: string) {
  if (returnType === 'SALES_RETURN') {
    const order = await getSalesOrderDetail(sourceOrderId);
    return {
      sourceOrderNo: order.salesNo,
      partyLabel: `${order.customerCode} ${order.customerName}`.trim(),
      warehouseLabel: order.warehouseName,
      sourceStatus: order.status,
    };
  }
  const order = await getPurchaseOrderDetail(sourceOrderId);
  return {
    sourceOrderNo: order.purchaseNo,
    partyLabel: `${order.supplierCode} ${order.supplierName}`.trim(),
    warehouseLabel: order.warehouseName,
    sourceStatus: order.status,
  };
}

/** 查询来源单可退明细；修改退货单时排除自身占用。 */
export async function listSourceItems(returnType: ReturnType, sourceOrderId: string, excludeReturnOrderId?: string) {
  return returnType === 'SALES_RETURN'
    ? listSalesReturnSourceItems(sourceOrderId, excludeReturnOrderId)
    : listPurchaseReturnSourceItems(sourceOrderId, excludeReturnOrderId);
}

/** 查询退货单详情。 */
export async function getReturnDetail(returnType: ReturnType, returnOrderId: string): Promise<ReturnOrderDetail> {
  return returnType === 'SALES_RETURN'
    ? getSalesReturnDetail(returnOrderId)
    : getPurchaseReturnDetail(returnOrderId);
}

/** 按退货单 ID 查询详情；方向由服务端返回，动作卡片使用。 */
export async function getReturnDetailById(returnOrderId: string, orderNo?: string): Promise<ReturnOrderDetail> {
  const resolvedId = await resolveReturnOrderId(returnOrderId, orderNo);
  try {
    return await getPurchaseReturnDetail(resolvedId);
  } catch {
    return getSalesReturnDetail(resolvedId);
  }
}

/** 用户确认后创建退货草稿，复用退货业务接口的完整校验。 */
export async function createReturnFromDraft(draft: ReturnDraftHeader, items: ReturnDraftHeader['items']) {
  const order = await createReturn(draft.returnType, toFormPayload(draft, items));
  return {
    returnOrderId: order.returnOrderId,
    returnNo: order.returnNo,
    returnType: order.returnType,
    status: order.status,
  };
}

/** 用户确认后更新已有退货单，复用退货业务接口的完整校验。 */
export async function updateReturnFromDraft(
  returnOrderId: string,
  version: number,
  draft: ReturnDraftHeader,
  items: ReturnDraftHeader['items'],
) {
  const order = await updateReturn(draft.returnType, returnOrderId, version, toFormPayload(draft, items));
  return {
    returnOrderId: order.returnOrderId,
    returnNo: order.returnNo,
    returnType: order.returnType,
    status: order.status,
  };
}

/** 提交退货单审核。 */
export async function submitReturn(returnType: ReturnType, returnOrderId: string, version: number) {
  if (returnType === 'SALES_RETURN') {
    await submitSalesReturn(returnOrderId, version);
    return;
  }
  await submitPurchaseReturn(returnOrderId, version);
}

/** 取消退货单。 */
export async function cancelReturn(returnType: ReturnType, returnOrderId: string, version: number, reason: string) {
  if (returnType === 'SALES_RETURN') {
    await cancelSalesReturn(returnOrderId, { version, reason });
    return;
  }
  await cancelPurchaseReturn(returnOrderId, { version, reason });
}

async function createReturn(returnType: ReturnType, payload: ReturnOrderFormPayload) {
  return returnType === 'SALES_RETURN'
    ? createSalesReturn(payload)
    : createPurchaseReturn(payload);
}

async function updateReturn(
  returnType: ReturnType,
  returnOrderId: string,
  version: number,
  payload: ReturnOrderFormPayload,
) {
  return returnType === 'SALES_RETURN'
    ? updateSalesReturn(returnOrderId, { ...payload, version })
    : updatePurchaseReturn(returnOrderId, { ...payload, version });
}

function toFormPayload(draft: ReturnDraftHeader, items: ReturnDraftHeader['items']): ReturnOrderFormPayload {
  return {
    sourceOrderId: draft.sourceOrderId,
    warehouseId: draft.warehouseId,
    expectedExecutionDate: draft.expectedExecutionDate || null,
    handlingType: draft.handlingType,
    reasonCode: draft.reasonCode,
    returnReason: draft.returnReason,
    remark: draft.remark || '',
    items: items.map(item => ({
      sourceOrderItemId: item.sourceOrderItemId,
      requestedQty: item.requestedQty,
      remark: item.remark || '',
    })),
  };
}

/** 把用户处置结果交回等待中的模型。 */
export async function resolveReturnDraft(conversationId: string, request: ReturnDraftRequest, receipt: ReturnDraftReceipt) {
  await http.post(
    `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/return-drafts/${encodeURIComponent(request.draftRequestId)}/resolve`,
    receipt,
    ORDER_CONFIRM_REQUEST_CONFIG,
  );
}

/** 把提交或取消结果交回等待中的模型。 */
export async function resolveReturnAction(conversationId: string, request: ReturnActionRequest, receipt: ReturnActionReceipt) {
  await http.post(
    `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/return-actions/${encodeURIComponent(request.actionRequestId)}/resolve`,
    receipt,
    ORDER_CONFIRM_REQUEST_CONFIG,
  );
}