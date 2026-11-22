import { http } from '@/api/http';
import { createPurchaseOrder, getSupplierDetail, listSupplierProducts } from '@/modules/purchase/api';
import { getWarehouseDetail } from '@/modules/warehouse/warehouses/api';
import type { PurchaseDraftHeader, PurchaseDraftLine, PurchaseDraftRequest, PurchaseDraftReceipt } from './purchase-draft-types';

/** 草稿确认页需要的展示信息：名称按编号回查，不在前端拼推测值。 */
export interface PurchaseDraftContext {
  supplierLabel: string;
  warehouseLabel: string;
  lines: PurchaseDraftLine[];
}

/**
 * 按草稿中的编号补齐供应商名称、仓库名称和供货关系。
 *
 * @param draft 草稿内容
 * @returns 展示信息
 */
export async function loadPurchaseDraftContext(draft: PurchaseDraftHeader): Promise<PurchaseDraftContext> {
  const [supplier, warehouse, lines] = await Promise.all([
    getSupplierDetail(draft.supplierId).catch(() => null),
    // 草稿回显是浮层内的一次读取，不能触发全页加载遮罩。
    getWarehouseDetail(draft.warehouseId, { skipPageLoading: true }).catch(() => null),
    Promise.all(draft.items.map(item => resolveLine(draft.supplierId, item))),
  ]);
  return {
    supplierLabel: supplier ? `${supplier.supplierCode} ${supplier.supplierName}` : `供应商 ${draft.supplierId}`,
    warehouseLabel: warehouse ? warehouse.warehouseName : `仓库 ${draft.warehouseId}`,
    lines,
  };
}

/** 按供应商和产品查启用供货关系；查不到时该行不能提交。 */
async function resolveLine(supplierId: string, item: PurchaseDraftHeader['items'][number]): Promise<PurchaseDraftLine> {
  const page = await listSupplierProducts({
    pageNum: 1,
    pageSize: 1,
    supplierId,
    productId: item.productId,
    status: 1,
  }, { skipPageLoading: true }).catch(() => null);
  const record = page?.records?.[0];
  return {
    productId: item.productId,
    productCode: record?.productCode || '',
    productName: record?.productName || `产品 ${item.productId}`,
    unitName: record?.unitName || '',
    quantityPrecision: record?.quantityPrecision ?? 0,
    supplierProductId: record?.supplierProductId || null,
    latestPurchasePrice: record?.latestPurchasePrice ?? null,
    minOrderQty: record?.minOrderQty ?? 0,
    quantity: item.quantity,
    // 模型未给出单价时按供货关系最新采购价回填，避免用户凭空填写。
    unitPrice: item.unitPrice ?? record?.latestPurchasePrice ?? null,
    remark: item.remark || '',
  };
}

/** 按供应商、产品查启用供货关系，供编辑时新增明细行使用。 */
export async function searchDraftSupplierProducts(supplierId: string, keyword: string) {
  const page = await listSupplierProducts({
    pageNum: 1,
    pageSize: 10,
    supplierId,
    status: 1,
    ...(/^[A-Za-z0-9_-]+$/.test(keyword) ? { productCode: keyword } : keyword.trim() ? { productName: keyword } : {}),
  }, { skipPageLoading: true });
  return page.records.map(item => ({
    value: item.productId,
    label: `${item.productCode} ${item.productName}`,
    line: {
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      unitName: item.unitName,
      quantityPrecision: item.quantityPrecision,
      supplierProductId: item.supplierProductId,
      latestPurchasePrice: item.latestPurchasePrice,
      minOrderQty: item.minOrderQty,
      quantity: item.minOrderQty > 0 ? item.minOrderQty : 1,
      unitPrice: item.latestPurchasePrice,
      remark: '',
    } satisfies PurchaseDraftLine,
  }));
}

/** 编辑时按编号重新读取一条供货关系。 */
export async function findDraftLine(supplierId: string, productId: string): Promise<PurchaseDraftLine | null> {
  const page = await listSupplierProducts({ pageNum: 1, pageSize: 1, supplierId, productId, status: 1 },
    { skipPageLoading: true }).catch(() => null);
  const record = page?.records?.[0];
  if (!record) return null;
  return {
    productId: record.productId,
    productCode: record.productCode,
    productName: record.productName,
    unitName: record.unitName,
    quantityPrecision: record.quantityPrecision,
    supplierProductId: record.supplierProductId,
    latestPurchasePrice: record.latestPurchasePrice,
    minOrderQty: record.minOrderQty,
    quantity: record.minOrderQty > 0 ? record.minOrderQty : 1,
    unitPrice: record.latestPurchasePrice,
    remark: '',
  };
}

/** 用户确认后写入采购单草稿，复用业务模块的完整校验。 */
export async function createPurchaseOrderFromDraft(draft: PurchaseDraftHeader, lines: PurchaseDraftLine[]) {
  if (!draft.warehouseId) throw new Error('请选择入库仓库');
  const order = await createPurchaseOrder({
    supplierId: draft.supplierId,
    warehouseId: draft.warehouseId,
    expectedArrivalDate: draft.expectedArrivalDate || null,
    remark: draft.remark || '',
    items: lines.map(line => ({
      supplierProductId: line.supplierProductId!,
      productId: line.productId,
      quantityPrecision: line.quantityPrecision,
      quantity: line.quantity,
      unitPrice: line.unitPrice!,
      remark: line.remark,
    })),
  });
  return { purchaseNo: order.purchaseNo, purchaseOrderId: order.purchaseOrderId };
}

/** 把用户处置结果交回等待中的模型。 */
export async function resolvePurchaseDraft(conversationId: string, request: PurchaseDraftRequest, receipt: PurchaseDraftReceipt) {
  await http.post(
    `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/drafts/${encodeURIComponent(request.draftRequestId)}/resolve`,
    receipt,
    { skipPageLoading: true },
  );
}