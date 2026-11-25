/** 销售草稿：与工具参数结构一致，只包含用户可决策的业务字段。 */
export interface SalesDraftItem {
  productId: string;
  quantity: number;
  /** 销售单价必填，由模型按参考售价或用户给定价格给出。 */
  unitPrice: number | null;
  remark: string | null;
}

export interface SalesDraftHeader {
  /** 修改已有销售单时必填。 */
  salesOrderId?: string | null;
  customerId: string;
  warehouseId: string;
  expectedDeliveryDate: string | null;
  remark: string | null;
  items: SalesDraftItem[];
}

/** 后端等待通道发出的一份待确认草稿。 */
export interface SalesDraftRequest {
  draftRequestId: string;
  toolCallId?: string;
  /** 创建新草稿或修改已有销售单。 */
  action?: 'CREATE' | 'UPDATE';
  draft: SalesDraftHeader;
}

/** 草稿中一行的展示信息；产品信息按编号回查，用于校验与详情回显。 */
export interface SalesDraftLine {
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  referenceSalePrice: number | null;
  /** 修改已有销售单时沿用原明细编号，新增行为空。 */
  salesOrderItemId?: string | null;
  quantity: number;
  unitPrice: number | null;
  remark: string;
}

/** 用户处置结果；提交后由工具交回模型。 */
export type SalesDraftReceipt =
  | { status: 'created'; orderNo: string; salesOrderId: string; orderStatus?: string }
  | { status: 'updated'; orderNo: string; salesOrderId: string; orderStatus?: string }
  | {
      status: 'edited';
      orderNo: string;
      salesOrderId: string;
      orderStatus?: string;
      finalParams: SalesDraftHeader;
      /** 卡片打开时的参数，作为“用户改了什么”的比较基线。 */
      originalParams: SalesDraftHeader;
      userNote: string;
    }
  | { status: 'failed'; orderNo?: string; salesOrderId?: string; error: string; finalParams?: SalesDraftHeader }
  | { status: 'rejected'; orderNo?: string; salesOrderId?: string; reasons: string[]; suggestion: string };

/** 驳回原因：覆盖客户、仓库、价格、数量、交期五类常见调整方向。 */
export const SALES_DRAFT_REJECT_REASONS = [
  '价格不合适',
  '数量不合适',
  '客户不对',
  '交期太早',
  '出库仓不对',
  '其他',
] as const;

/** 把后端返回值收敛成可校验的草稿；数值字段允许数字字符串。 */
export function parseSalesDraftRequest(data: Record<string, unknown>): SalesDraftRequest {
  const draft = data.draft;
  if (!draft || typeof draft !== 'object' || Array.isArray(draft)) {
    throw new Error('销售草稿缺少单据内容');
  }
  const value = draft as Record<string, unknown>;
  const items = Array.isArray(value.items) ? value.items : [];
  const normalizedItems = items.map((item, index) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      throw new Error(`销售草稿第 ${index + 1} 行格式错误`);
    }
    const line = item as Record<string, unknown>;
    if (typeof line.productId !== 'string' || !line.productId) {
      throw new Error(`销售草稿第 ${index + 1} 行缺少产品`);
    }
    return {
      productId: line.productId,
      quantity: positiveNumber(line.quantity, `销售草稿第 ${index + 1} 行数量`),
      unitPrice: optionalNumber(line.unitPrice),
      remark: typeof line.remark === 'string' && line.remark.trim() ? line.remark.trim() : null,
    };
  });
  const action = data.action === 'UPDATE' ? 'UPDATE' : 'CREATE';
  const customerId = typeof value.customerId === 'string' ? value.customerId : '';
  const warehouseId = typeof value.warehouseId === 'string' ? value.warehouseId : '';
  // 创建必须有完整参数；修改允许只带变化字段，其余以单据详情为底。
  if (action === 'CREATE') {
    if (!customerId) {
      throw new Error('销售草稿缺少客户');
    }
    if (!warehouseId) {
      throw new Error('销售草稿缺少出库仓库');
    }
    if (normalizedItems.length === 0) {
      throw new Error('销售草稿没有明细');
    }
  }
  return {
    draftRequestId: String(data.draftRequestId ?? ''),
    toolCallId: typeof data.toolCallId === 'string' ? data.toolCallId : undefined,
    action,
    draft: {
      salesOrderId: typeof value.salesOrderId === 'string' ? value.salesOrderId : null,
      customerId,
      warehouseId,
      expectedDeliveryDate: typeof value.expectedDeliveryDate === 'string' && value.expectedDeliveryDate ? value.expectedDeliveryDate : null,
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