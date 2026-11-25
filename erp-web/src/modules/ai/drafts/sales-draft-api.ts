import { http } from '@/api/http';
import { createSalesOrder, getCustomerDetail, getSalesOrderDetail, listEnabledSalesProductOptions, updateSalesOrder } from '@/modules/sales/api';
import { getProductDetail } from '@/modules/product/products/api';
import { getWarehouseDetail } from '@/modules/warehouse/warehouses/api';
import type { SalesDraftHeader, SalesDraftLine, SalesDraftRequest, SalesDraftReceipt } from './sales-draft-types';

/** 草稿确认页需要的展示信息：名称一律按编号回查，不由模型提供。 */
export interface SalesDraftContext {
  customerLabel: string;
  warehouseLabel: string;
  lines: SalesDraftLine[];
}

/**
 * 按草稿中的编号补齐客户名称、仓库名称和产品信息。
 *
 * @param draft 草稿内容
 * @returns 展示信息
 */
export async function loadSalesDraftContext(draft: SalesDraftHeader): Promise<SalesDraftContext> {
  const [customer, warehouse, lines] = await Promise.all([
    getCustomerDetail(draft.customerId).catch(() => null),
    // 草稿回显是浮层内的一次读取，不能触发全页加载遮罩。
    getWarehouseDetail(draft.warehouseId, { skipPageLoading: true }).catch(() => null),
    Promise.all(draft.items.map(item => resolveLine(item))),
  ]);
  return {
    customerLabel: customer ? `${customer.customerCode} ${customer.customerName}` : `客户 ${draft.customerId}`,
    warehouseLabel: warehouse?.warehouseName || `仓库 ${draft.warehouseId}`,
    lines,
  };
}

/** 按产品编号回查产品档案；查不到时只保留编号，由提交接口最终校验。 */
async function resolveLine(item: SalesDraftHeader['items'][number]): Promise<SalesDraftLine> {
  const product = await getProductDetail(item.productId).catch(() => null);
  return {
    productId: item.productId,
    productCode: product?.productCode || '',
    productName: product?.productName || '',
    unitName: product?.unitName || '',
    quantityPrecision: product?.quantityPrecision ?? 0,
    referenceSalePrice: product?.referenceSalePrice ?? null,
    quantity: item.quantity,
    // 模型未给出单价时按参考销售价回填，避免用户凭空填写。
    unitPrice: item.unitPrice ?? product?.referenceSalePrice ?? null,
    remark: item.remark || '',
  };
}

/** 销售明细产品下拉：只列出启用状态的产品。 */
export async function searchDraftProducts(keyword: string) {
  const options = await listEnabledSalesProductOptions(keyword, 10);
  return options.map(item => ({
    value: item.value,
    label: item.label,
    line: {
      productId: item.product.productId,
      productCode: item.product.productCode,
      productName: item.product.productName,
      unitName: item.product.unitName,
      quantityPrecision: item.product.quantityPrecision,
      referenceSalePrice: item.product.referenceSalePrice,
      quantity: 1,
      unitPrice: item.product.referenceSalePrice,
      remark: '',
    } satisfies SalesDraftLine,
  }));
}

/** 编辑时按编号重新读取一条产品信息。 */
export async function findDraftLine(productId: string): Promise<SalesDraftLine | null> {
  const product = await getProductDetail(productId).catch(() => null);
  if (!product) return null;
  return {
    productId: product.productId,
    productCode: product.productCode,
    productName: product.productName,
    unitName: product.unitName,
    quantityPrecision: product.quantityPrecision,
    referenceSalePrice: product.referenceSalePrice,
    quantity: 1,
    unitPrice: product.referenceSalePrice,
    remark: '',
  };
}

/** 用户确认后写入销售单草稿，复用业务模块的完整校验。 */
export async function createSalesOrderFromDraft(draft: SalesDraftHeader, lines: SalesDraftLine[]) {
  if (!draft.warehouseId) throw new Error('请选择出库仓库');
  const order = await createSalesOrder({
    customerId: draft.customerId,
    warehouseId: draft.warehouseId,
    expectedDeliveryDate: draft.expectedDeliveryDate || null,
    remark: draft.remark || '',
    items: lines.map(line => ({
      productId: line.productId,
      quantity: line.quantity,
      unitPrice: line.unitPrice ?? 0,
      remark: line.remark,
    })),
  }, { skipPageLoading: true });
  return { salesNo: order.salesNo, salesOrderId: order.salesOrderId };
}

/** 用户确认后更新已有销售单，复用业务模块的完整校验。 */
export async function updateSalesOrderFromDraft(
  orderId: string,
  version: number,
  draft: SalesDraftHeader,
  lines: SalesDraftLine[],
) {
  if (!draft.warehouseId) throw new Error('请选择出库仓库');
  const order = await updateSalesOrder(orderId, {
    version,
    customerId: draft.customerId,
    warehouseId: draft.warehouseId,
    expectedDeliveryDate: draft.expectedDeliveryDate || null,
    remark: draft.remark || '',
    items: lines.map(line => ({
      salesOrderItemId: line.salesOrderItemId || null,
      productId: line.productId,
      quantity: line.quantity,
      unitPrice: line.unitPrice ?? 0,
      remark: line.remark,
    })),
  }, { skipPageLoading: true });
  return { salesNo: order.salesNo, salesOrderId: order.salesOrderId, status: order.status };
}

/** 按编号读取销售单详情，供修改确认页与动作确认页共用。 */
export async function loadSalesOrderDetail(orderId: string) {
  return getSalesOrderDetail(orderId);
}

/** 把用户处置结果交回等待中的模型。 */
export async function resolveSalesDraft(conversationId: string, request: SalesDraftRequest, receipt: SalesDraftReceipt) {
  await http.post(
    `/ai/assistant/conversations/${encodeURIComponent(conversationId)}/sales-drafts/${encodeURIComponent(request.draftRequestId)}/resolve`,
    receipt,
    { skipPageLoading: true },
  );
}
