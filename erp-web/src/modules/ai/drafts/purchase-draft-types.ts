/** 采购草稿：与工具参数结构一致，只包含用户可决策的业务字段。 */
export interface PurchaseDraftItem {
  productId: string;
  quantity: number;
  unitPrice: number | null;
  remark: string | null;
}

export interface PurchaseDraftHeader {
  /** 修改已有采购单时必填。 */
  purchaseOrderId?: string | null;
  supplierId: string;
  warehouseId: string;
  expectedArrivalDate: string | null;
  remark: string | null;
  items: PurchaseDraftItem[];
}

/** 后端等待通道发出的一份待确认草稿。 */
export interface PurchaseDraftRequest {
  draftRequestId: string;
  toolCallId?: string;
  /** 创建新草稿或修改已有采购单。 */
  action?: 'CREATE' | 'UPDATE';
  draft: PurchaseDraftHeader;
}

/** 草稿中一行的展示信息；来自该供应商的启用供货关系，用于校验与详情回显。 */
export interface PurchaseDraftLine {
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  supplierProductId: string | null;
  latestPurchasePrice: number | null;
  minOrderQty: number;
  quantity: number;
  unitPrice: number | null;
  remark: string;
}

/** 用户处置结果；提交后由工具交回模型。 */
export type PurchaseDraftReceipt =
  | { status: 'created'; orderNo: string; purchaseOrderId: string; orderStatus?: string }
  | { status: 'updated'; orderNo: string; purchaseOrderId: string; orderStatus?: string }
  | {
      status: 'edited';
      orderNo: string;
      purchaseOrderId: string;
      orderStatus?: string;
      finalParams: PurchaseDraftHeader;
      /** 卡片打开时的参数，作为“用户改了什么”的比较基线。 */
      originalParams: PurchaseDraftHeader;
      userNote: string;
    }
  | { status: 'failed'; orderNo?: string; purchaseOrderId?: string; error: string; finalParams?: PurchaseDraftHeader }
  | { status: 'rejected'; orderNo?: string; purchaseOrderId?: string; reasons: string[]; suggestion: string };

/** 草稿行可按产品编码或名称查询启用供货关系。 */
export const PURCHASE_DRAFT_REJECT_REASONS = [
  '价格过高',
  '数量不合适',
  '供应商不合适',
  '交期太晚',
  '收货仓不对',
  '其他',
] as const;

/** 新事件用 callId + arguments，旧事件用 draftRequestId + draft；统一成旧结构再解析。 */
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
    ? root.request
    : root;
  return { ...data, draftRequestId: data.callId, draft };
}

/** 把后端返回值收敛成可校验的草稿；兼容 callId + arguments 新结构和旧 draft 结构。 */
export function parsePurchaseDraftRequest(data: Record<string, unknown>): PurchaseDraftRequest {
  const source = normalizeDraftSource(data);
  const draft = source.draft;
  if (!draft || typeof draft !== 'object' || Array.isArray(draft)) {
    throw new Error('采购草稿缺少单据内容');
  }
  const value = draft as Record<string, unknown>;
  const items = Array.isArray(value.items) ? value.items : [];
  const normalizedItems = items.map((item, index) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      throw new Error(`采购草稿第 ${index + 1} 行格式错误`);
    }
    const line = item as Record<string, unknown>;
    if (typeof line.productId !== 'string' || !line.productId) {
      throw new Error(`采购草稿第 ${index + 1} 行缺少产品`);
    }
    return {
      productId: line.productId,
      quantity: positiveNumber(line.quantity, `采购草稿第 ${index + 1} 行数量`),
      unitPrice: optionalNumber(line.unitPrice),
      remark: typeof line.remark === 'string' && line.remark.trim() ? line.remark.trim() : null,
    };
  });
  const action = source.action === 'UPDATE' ? 'UPDATE' : 'CREATE';
  const supplierId = typeof value.supplierId === 'string' ? value.supplierId : '';
  const warehouseId = typeof value.warehouseId === 'string' ? value.warehouseId : '';
  // 创建必须有完整参数；修改允许只带变化字段，其余以单据详情为底。
  if (action === 'CREATE') {
    if (!supplierId) {
      throw new Error('采购草稿缺少供应商');
    }
    if (!warehouseId) {
      throw new Error('采购草稿缺少入库仓库');
    }
    if (normalizedItems.length === 0) {
      throw new Error('采购草稿没有明细');
    }
  }
  return {
    draftRequestId: String(source.draftRequestId ?? ''),
    toolCallId: typeof source.toolCallId === 'string' ? source.toolCallId : undefined,
    action,
    draft: {
      purchaseOrderId: typeof value.purchaseOrderId === 'string' ? value.purchaseOrderId : null,
      supplierId,
      warehouseId,
      expectedArrivalDate: typeof value.expectedArrivalDate === 'string' && value.expectedArrivalDate ? value.expectedArrivalDate : null,
      remark: typeof value.remark === 'string' && value.remark.trim() ? value.remark.trim() : null,
      items: normalizedItems,
    },
  };
}

function positiveNumber(value: unknown, label: string): number {
  const number = optionalNumber(value);
  if (number === null || number <= 0) {
    throw new Error(`${label}必须大于 0`);
  }
  return number;
}

function optionalNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const number = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(number) ? number : null;
}