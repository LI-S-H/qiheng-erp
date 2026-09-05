import { getResult, http, postResult } from '@/api/http';
import type { Result } from '@/shared/types/api';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { normalizeMoneyNumber } from '@/shared/utils/money';
import { getSalesOrderDetail, listEnabledSalesWarehouseOptions, listSalesOrders, searchCustomerOptions } from '../api';
import type { SalesOrderDetail } from '../types';
import { normalizeReturnReasonCode } from '@/modules/returns/types';
import type {
  ReturnHandlingType,
  ReturnOrderApprovePayload,
  ReturnOrderCreateRequest,
  ReturnOrderDetail,
  ReturnOrderFormPayload,
  ReturnOrderItem,
  ReturnOrderListItem,
  ReturnOrderPage,
  ReturnOrderQuery,
  ReturnOrderReasonActionPayload,
  ReturnOrderStatus,
  ReturnOrderUpdateRequest,
  ReturnReasonCode,
  ReturnableSourceOrder,
  ReturnableSourceOrderItem,
} from '@/modules/returns/types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';
const RETURN_API = '/returns';
const returnStatuses: ReturnOrderStatus[] = ['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED', 'CANCELLED'];
const handlingTypes: ReturnHandlingType[] = ['REFUND', 'EXCHANGE', 'OTHER'];
const reasonCodes: ReturnReasonCode[] = ['QUALITY_ISSUE', 'DAMAGED', 'WRONG_ITEM', 'QUANTITY_ERROR', 'SPEC_MISMATCH', 'NO_LONGER_NEEDED', 'OTHER'];

let mockReturns: ReturnOrderDetail[] | null = null;
let nextReturnSequence = 7;
let nextReturnItemSequence = 7;

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function nextMockReturnItemId() {
  return `2041000000000000${String(nextReturnItemSequence++).padStart(3, '0')}`;
}

function assertEnum<T extends string>(value: unknown, allowed: readonly T[], fieldName: string): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) throw new Error(`接口字段 ${fieldName} 枚举值无效`);
  return value as T;
}

function normalizeQuantityPrecision(value: unknown, fieldName = 'quantityPrecision') {
  const precision = normalizeFiniteNumber(value, fieldName);
  if (!Number.isInteger(precision) || precision < 0 || precision > 2) {
    throw new Error(`接口字段 ${fieldName} 必须是 0～2 的整数`);
  }
  return precision;
}

function normalizeQuantity(value: unknown, precision: number, fieldName: string) {
  const quantity = normalizeFiniteNumber(value, fieldName);
  const factor = 10 ** precision;
  if (Math.abs(quantity * factor - Math.round(quantity * factor)) > 1e-8) {
    throw new Error(`接口字段 ${fieldName} 小数位超过 quantityPrecision`);
  }
  return quantity;
}

function normalizeReturnItem(item: ReturnOrderItem): ReturnOrderItem {
  const quantityPrecision = normalizeQuantityPrecision(item.quantityPrecision);
  return {
    ...item,
    returnOrderItemId: normalizeStringId(item.returnOrderItemId, 'returnOrderItemId'),
    returnOrderId: normalizeStringId(item.returnOrderId, 'returnOrderId'),
    sourceOrderItemId: normalizeStringId(item.sourceOrderItemId, 'sourceOrderItemId'),
    productId: normalizeStringId(item.productId, 'productId'),
    productCode: String(item.productCode),
    productName: String(item.productName),
    unitName: String(item.unitName),
    quantityPrecision,
    sourceFulfilledQty: normalizeQuantity(item.sourceFulfilledQty, quantityPrecision, 'sourceFulfilledQty'),
    requestedQty: normalizeQuantity(item.requestedQty, quantityPrecision, 'requestedQty'),
    approvedQty: normalizeQuantity(item.approvedQty, quantityPrecision, 'approvedQty'),
    processedQty: normalizeQuantity(item.processedQty, quantityPrecision, 'processedQty'),
    unitPrice: normalizeMoneyNumber(item.unitPrice, 'unitPrice', false, useMockApi)!,
    totalAmount: normalizeMoneyNumber(item.totalAmount, 'totalAmount', false, useMockApi)!,
    remark: String(item.remark || ''),
  };
}

function normalizeReturn(row: ReturnOrderListItem): ReturnOrderListItem {
  const returnType = assertEnum(row.returnType, ['SALES_RETURN'] as const, 'returnType');
  if (returnType !== 'SALES_RETURN') throw new Error('销售退货接口不得返回其他退货类型');
  return {
    ...row,
    returnOrderId: normalizeStringId(row.returnOrderId, 'returnOrderId'),
    returnNo: String(row.returnNo),
    returnType,
    sourceOrderId: normalizeStringId(row.sourceOrderId, 'sourceOrderId'),
    sourceOrderNo: String(row.sourceOrderNo),
    partyId: normalizeStringId(row.partyId, 'partyId'),
    partyCode: String(row.partyCode),
    partyName: String(row.partyName),
    warehouseId: normalizeStringId(row.warehouseId, 'warehouseId'),
    warehouseName: String(row.warehouseName),
    expectedExecutionDate: row.expectedExecutionDate ? String(row.expectedExecutionDate) : null,
    handlingType: assertEnum(row.handlingType, handlingTypes, 'handlingType'),
    reasonCode: normalizeReturnReasonCode(row.reasonCode),
    returnReason: String(row.returnReason || ''),
    totalAmount: normalizeMoneyNumber(row.totalAmount, 'totalAmount', false, useMockApi)!,
    status: assertEnum(row.status, returnStatuses, 'status'),
    statusReason: String(row.statusReason || ''),
    createdById: normalizeNullableStringId(row.createdById, 'createdById'),
    createdByName: String(row.createdByName || ''),
    submittedAt: row.submittedAt ? String(row.submittedAt) : null,
    approvedById: normalizeNullableStringId(row.approvedById, 'approvedById'),
    approvedByName: String(row.approvedByName || ''),
    approvedAt: row.approvedAt ? String(row.approvedAt) : null,
    createTime: String(row.createTime),
    updateTime: String(row.updateTime),
    remark: String(row.remark || ''),
    version: normalizeFiniteNumber(row.version, 'version'),
  };
}

function normalizeReturnDetail(row: ReturnOrderDetail): ReturnOrderDetail {
  return { ...normalizeReturn(row), items: row.items.map(normalizeReturnItem) };
}

function normalizeSourceOrder(row: ReturnableSourceOrder): ReturnableSourceOrder {
  return {
    ...row,
    sourceOrderId: normalizeStringId(row.sourceOrderId, 'sourceOrderId'),
    sourceOrderNo: String(row.sourceOrderNo),
    partyId: normalizeStringId(row.partyId, 'partyId'),
    partyCode: String(row.partyCode),
    partyName: String(row.partyName),
    warehouseId: normalizeStringId(row.warehouseId, 'warehouseId'),
    warehouseName: String(row.warehouseName),
    fulfilledItemCount: normalizeFiniteNumber(row.fulfilledItemCount, 'fulfilledItemCount'),
    totalAvailableReturnQty: normalizeFiniteNumber(row.totalAvailableReturnQty, 'totalAvailableReturnQty'),
  };
}

function normalizeSourceItem(item: ReturnableSourceOrderItem): ReturnableSourceOrderItem {
  const quantityPrecision = normalizeQuantityPrecision(item.quantityPrecision);
  return {
    ...item,
    sourceOrderItemId: normalizeStringId(item.sourceOrderItemId, 'sourceOrderItemId'),
    productId: normalizeStringId(item.productId, 'productId'),
    productCode: String(item.productCode),
    productName: String(item.productName),
    unitName: String(item.unitName),
    quantityPrecision,
    sourceFulfilledQty: normalizeQuantity(item.sourceFulfilledQty, quantityPrecision, 'sourceFulfilledQty'),
    stockAvailableQty: normalizeQuantity(item.stockAvailableQty, quantityPrecision, 'stockAvailableQty'),
    occupiedQty: normalizeQuantity(item.occupiedQty, quantityPrecision, 'occupiedQty'),
    availableReturnQty: normalizeQuantity(item.availableReturnQty, quantityPrecision, 'availableReturnQty'),
    unitPrice: normalizeMoneyNumber(item.unitPrice, 'unitPrice', false, useMockApi)!,
  };
}

function occupiedQuantity(sourceOrderItemId: string, excludeReturnOrderId?: string) {
  return (mockReturns || []).reduce((sum, order) => {
    if (order.returnOrderId === excludeReturnOrderId) return sum;
    const item = order.items.find(candidate => candidate.sourceOrderItemId === sourceOrderItemId);
    if (!item) return sum;
    if (order.status === 'SUBMITTED') return sum + item.requestedQty;
    if (order.status === 'APPROVED' || order.status === 'PARTIAL_EXECUTED') return sum + item.approvedQty;
    if (order.status === 'COMPLETED') return sum + item.processedQty;
    return sum;
  }, 0);
}

function amountQuantity(status: ReturnOrderStatus, item: Pick<ReturnOrderItem, 'requestedQty' | 'approvedQty' | 'processedQty'>) {
  if (status === 'APPROVED' || status === 'PARTIAL_EXECUTED') return item.approvedQty;
  if (status === 'COMPLETED') return item.processedQty;
  return item.requestedQty;
}

function refreshMockAmounts(order: ReturnOrderDetail) {
  order.items.forEach(item => {
    item.totalAmount = Number((amountQuantity(order.status, item) * item.unitPrice).toFixed(2));
  });
  order.totalAmount = Number(order.items.reduce((sum, item) => sum + item.totalAmount, 0).toFixed(2));
}

async function getEligibleSalesDetails() {
  const pages = await Promise.all([
    listSalesOrders({ status: 'PARTIAL_OUTBOUND', pageNum: 1, pageSize: 20 }),
    listSalesOrders({ status: 'OUTBOUND_DONE', pageNum: 1, pageSize: 20 }),
  ]);
  const unique = new Map(pages.flatMap(page => page.records).map(order => [order.salesOrderId, order]));
  return Promise.all([...unique.keys()].map(id => getSalesOrderDetail(id)));
}

function buildSeed(
  source: SalesOrderDetail,
  index: number,
  status: ReturnOrderStatus,
  sourceItemIndex: number,
  requestedQty: number,
  approvedQty: number,
  processedQty: number,
): ReturnOrderDetail {
  const item = source.items[sourceItemIndex % source.items.length];
  const returnOrderId = `2040000000000000${String(index + 1).padStart(3, '0')}`;
  const returnNo = `SR202607${String(index + 1).padStart(3, '0')}`;
  const createTime = `2026-07-${String(10 + index).padStart(2, '0')} 09:30:00`;
  const returnItem: ReturnOrderItem = {
    returnOrderItemId: `2041000000000000${String(index + 1).padStart(3, '0')}`,
    returnOrderId,
    sourceOrderItemId: item.salesOrderItemId,
    productId: item.productId,
    productCode: item.productCode,
    productName: item.productName,
    unitName: item.unitName,
    quantityPrecision: normalizeQuantityPrecision(item.quantityPrecision),
    sourceFulfilledQty: item.outboundQty,
    requestedQty,
    approvedQty,
    processedQty,
    unitPrice: item.unitPrice,
    totalAmount: 0,
    createTime,
    updateTime: createTime,
    remark: index === 0 ? '客户退货诉求已确认，待提交审核' : '',
  };
  const order: ReturnOrderDetail = {
    returnOrderId,
    returnNo,
    returnType: 'SALES_RETURN',
    sourceOrderId: source.salesOrderId,
    sourceOrderNo: source.salesNo,
    partyId: source.customerId,
    partyCode: source.customerCode,
    partyName: source.customerName,
    warehouseId: source.warehouseId,
    warehouseName: source.warehouseName,
    expectedExecutionDate: '2026-07-28',
    handlingType: index % 3 === 1 ? 'EXCHANGE' : 'REFUND',
    reasonCode: index % 2 === 0 ? 'QUALITY_ISSUE' : 'SPEC_MISMATCH',
    returnReason: index % 2 === 0 ? '客户反馈商品存在质量异常' : '规格与销售约定不一致',
    totalAmount: 0,
    status,
    statusReason: status === 'CANCELLED' ? '客户已撤回退货申请' : '',
    createdById: '1900000000000000002',
    createdByName: '销售主管',
    submittedAt: status === 'DRAFT' ? null : createTime,
    approvedById: ['APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED'].includes(status) ? '1900000000000000002' : null,
    approvedByName: ['APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED'].includes(status) ? '销售主管' : '',
    approvedAt: ['APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED'].includes(status) ? createTime : null,
    createTime,
    updateTime: createTime,
    remark: '销售退货流程演示数据',
    version: 0,
    items: [returnItem],
  };
  return order;
}

async function ensureMockReturns() {
  if (mockReturns) return mockReturns;
  const sources = await getEligibleSalesDetails();
  if (sources.length === 0) throw new Error('缺少可用于销售退货 Mock 的已出库销售单');
  const source = (index: number) => sources[index % sources.length];
  mockReturns = [
    buildSeed(source(0), 0, 'DRAFT', 0, 5, 0, 0),
    buildSeed(source(0), 1, 'SUBMITTED', 1, 4, 0, 0),
    buildSeed(source(1), 2, 'APPROVED', 0, 3, 2, 0),
    buildSeed(source(2), 3, 'PARTIAL_EXECUTED', 0, 4, 4, 2),
    buildSeed(source(0), 4, 'COMPLETED', 0, 2, 2, 2),
    buildSeed(source(1), 5, 'CANCELLED', 0, 2, 0, 0),
  ];
  mockReturns.forEach(refreshMockAmounts);
  return mockReturns;
}

async function sourceDetail(sourceOrderId: string) {
  return getSalesOrderDetail(sourceOrderId);
}

async function mockSourceItems(sourceOrderId: string): Promise<ReturnableSourceOrderItem[]> {
  await ensureMockReturns();
  const source = await sourceDetail(sourceOrderId);
  return source.items
    .filter(item => item.outboundQty > 0)
    .map(item => {
      const occupiedQty = occupiedQuantity(item.salesOrderItemId);
      return normalizeSourceItem({
        sourceOrderItemId: item.salesOrderItemId,
        productId: item.productId,
        productCode: item.productCode,
        productName: item.productName,
        unitName: item.unitName,
        quantityPrecision: normalizeQuantityPrecision(item.quantityPrecision),
        sourceFulfilledQty: item.outboundQty,
        // 销售退货为入库操作，真实后端跳过库存查询并返回 0；是否可退仍由 availableReturnQty 决定。
        stockAvailableQty: 0,
        occupiedQty,
        availableReturnQty: Math.max(0, item.outboundQty - occupiedQty),
        unitPrice: item.unitPrice,
      });
    })
    .filter(item => item.availableReturnQty > 0);
}

async function buildMockItems(returnOrderId: string, source: SalesOrderDetail, payload: ReturnOrderFormPayload, existingItems: ReturnOrderItem[] = []) {
  await ensureMockReturns();
  const sourceItems = await mockSourceItems(source.salesOrderId);
  return payload.items.map((draft): ReturnOrderItem => {
    const sourceItem = sourceItems.find(item => item.sourceOrderItemId === draft.sourceOrderItemId);
    if (!sourceItem) throw new Error('原销售明细已无剩余可退数量，请刷新后重试');
    const requestedQty = normalizeQuantity(draft.requestedQty, sourceItem.quantityPrecision, 'requestedQty');
    if (requestedQty <= 0 || requestedQty > sourceItem.availableReturnQty) throw new Error(`${sourceItem.productName} 的申请数量超出剩余可退数量`);
    return {
      returnOrderItemId: existingItems.find(item => item.sourceOrderItemId === draft.sourceOrderItemId)?.returnOrderItemId
        || nextMockReturnItemId(),
      returnOrderId,
      sourceOrderItemId: sourceItem.sourceOrderItemId,
      productId: sourceItem.productId,
      productCode: sourceItem.productCode,
      productName: sourceItem.productName,
      unitName: sourceItem.unitName,
      quantityPrecision: sourceItem.quantityPrecision,
      sourceFulfilledQty: sourceItem.sourceFulfilledQty,
      requestedQty,
      approvedQty: 0,
      processedQty: 0,
      unitPrice: sourceItem.unitPrice,
      totalAmount: Number((requestedQty * sourceItem.unitPrice).toFixed(2)),
      createTime: nowText(),
      updateTime: nowText(),
      remark: draft.remark.trim(),
    };
  });
}

function cleanListParams(query: ReturnOrderQuery) {
  return {
    returnType: 'SALES_RETURN',
    returnNo: query.returnNo?.trim() || undefined,
    sourceOrderNo: query.sourceOrderNo?.trim() || undefined,
    partyId: query.partyId && query.partyId !== 'all' ? query.partyId : undefined,
    warehouseId: query.warehouseId && query.warehouseId !== 'all' ? query.warehouseId : undefined,
    status: query.status && query.status !== 'all' ? query.status : undefined,
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  };
}

export async function listSalesReturns(query: ReturnOrderQuery): Promise<ReturnOrderPage> {
  const params = cleanListParams(query);
  if (!useMockApi) {
    const page = await getResult<ReturnOrderPage>(RETURN_API, params);
    return { ...page, records: page.records.map(normalizeReturn) };
  }
  const rows = await ensureMockReturns();
  rows.forEach(refreshMockAmounts);
  const filtered = rows.filter(row => {
    if (params.returnNo && !row.returnNo.includes(params.returnNo)) return false;
    if (params.sourceOrderNo && !row.sourceOrderNo.includes(params.sourceOrderNo)) return false;
    if (params.partyId && row.partyId !== params.partyId) return false;
    if (params.warehouseId && row.warehouseId !== params.warehouseId) return false;
    return !params.status || row.status === params.status;
  });
  const start = (query.pageNum - 1) * query.pageSize;
  return {
    records: filtered.slice(start, start + query.pageSize).map(row => normalizeReturn({ ...row })),
    total: filtered.length,
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  };
}

export async function getSalesReturnDetail(returnOrderId: string): Promise<ReturnOrderDetail> {
  normalizeStringId(returnOrderId, 'returnOrderId');
  if (!useMockApi) return normalizeReturnDetail(await getResult<ReturnOrderDetail>(`${RETURN_API}/${returnOrderId}`, undefined, { skipPageLoading: true }));
  const rows = await ensureMockReturns();
  const row = rows.find(item => item.returnOrderId === returnOrderId);
  if (!row) throw new Error('销售退货单不存在');
  refreshMockAmounts(row);
  return normalizeReturnDetail(structuredClone(row));
}

export async function createSalesReturn(payload: ReturnOrderFormPayload): Promise<ReturnOrderDetail> {
  const request: ReturnOrderCreateRequest = { ...payload, returnType: 'SALES_RETURN' };
  if (!useMockApi) return normalizeReturnDetail(await postResult<ReturnOrderDetail, ReturnOrderCreateRequest>(RETURN_API, request));
  const rows = await ensureMockReturns();
  const source = await sourceDetail(payload.sourceOrderId);
  const returnOrderId = `2040000000000000${String(nextReturnSequence).padStart(3, '0')}`;
  const returnNo = `SR${new Date().toISOString().slice(0, 10).split('-').join('')}${String(nextReturnSequence).padStart(3, '0')}`;
  nextReturnSequence += 1;
  const timestamp = nowText();
  const items = await buildMockItems(returnOrderId, source, payload);
  const created: ReturnOrderDetail = {
    returnOrderId,
    returnNo,
    returnType: 'SALES_RETURN',
    sourceOrderId: source.salesOrderId,
    sourceOrderNo: source.salesNo,
    partyId: source.customerId,
    partyCode: source.customerCode,
    partyName: source.customerName,
    warehouseId: payload.warehouseId,
    warehouseName: payload.warehouseId === source.warehouseId ? source.warehouseName : '退货入库仓库',
    expectedExecutionDate: payload.expectedExecutionDate,
    handlingType: payload.handlingType,
    reasonCode: payload.reasonCode,
    returnReason: payload.returnReason.trim(),
    totalAmount: 0,
    status: 'DRAFT',
    statusReason: '',
    createdById: '1900000000000000001',
    createdByName: '系统管理员',
    submittedAt: null,
    approvedById: null,
    approvedByName: '',
    approvedAt: null,
    createTime: timestamp,
    updateTime: timestamp,
    remark: payload.remark.trim(),
    version: 0,
    items,
  };
  refreshMockAmounts(created);
  rows.unshift(created);
  return normalizeReturnDetail(structuredClone(created));
}

export async function updateSalesReturn(returnOrderId: string, payload: ReturnOrderUpdateRequest): Promise<ReturnOrderDetail> {
  if (!useMockApi) {
    const response = await http.put<Result<ReturnOrderDetail>>(`${RETURN_API}/${returnOrderId}`, payload);
    return normalizeReturnDetail(response.data.data);
  }
  const rows = await ensureMockReturns();
  const row = rows.find(item => item.returnOrderId === returnOrderId);
  if (!row || row.status !== 'DRAFT') throw new Error('只有销售退货草稿可以编辑');
  if (row.version !== payload.version) throw new Error('数据已被其他人修改，请刷新后重试');
  const source = await sourceDetail(payload.sourceOrderId);
  row.sourceOrderId = source.salesOrderId;
  row.sourceOrderNo = source.salesNo;
  row.partyId = source.customerId;
  row.partyCode = source.customerCode;
  row.partyName = source.customerName;
  row.warehouseId = payload.warehouseId;
  row.warehouseName = payload.warehouseId === source.warehouseId ? source.warehouseName : '退货入库仓库';
  row.expectedExecutionDate = payload.expectedExecutionDate;
  row.handlingType = payload.handlingType;
  row.reasonCode = payload.reasonCode;
  row.returnReason = payload.returnReason.trim();
  row.remark = payload.remark.trim();
  row.items = await buildMockItems(returnOrderId, source, payload, row.items);
  row.version += 1;
  row.updateTime = nowText();
  refreshMockAmounts(row);
  return normalizeReturnDetail(structuredClone(row));
}

export async function deleteSalesReturn(returnOrderId: string, version: number) {
  if (!useMockApi) {
    await http.delete(`${RETURN_API}/${returnOrderId}`, { data: { version } });
    return;
  }
  const rows = await ensureMockReturns();
  const index = rows.findIndex(item => item.returnOrderId === returnOrderId);
  if (index < 0 || rows[index].status !== 'DRAFT') throw new Error('只有销售退货草稿可以删除');
  if (rows[index].version !== version) throw new Error('数据已被其他人修改，请刷新后重试');
  rows.splice(index, 1);
}

async function mockStatusRow(returnOrderId: string, version: number, allowed: ReturnOrderStatus[]) {
  const rows = await ensureMockReturns();
  const row = rows.find(item => item.returnOrderId === returnOrderId);
  if (!row || !allowed.includes(row.status)) throw new Error('当前状态不允许执行此操作');
  if (row.version !== version) throw new Error('数据已被其他人修改，请刷新后重试');
  return row;
}

export async function submitSalesReturn(returnOrderId: string, version: number) {
  if (!useMockApi) {
    await postResult<void>(`${RETURN_API}/${returnOrderId}/submit`, { version });
    return;
  }
  const row = await mockStatusRow(returnOrderId, version, ['DRAFT']);
  if (!row.expectedExecutionDate) throw new Error('提交前必须填写预计退货日期');
  for (const item of row.items) {
    const available = item.sourceFulfilledQty - occupiedQuantity(item.sourceOrderItemId, row.returnOrderId);
    if (item.requestedQty > available) throw new Error(`${item.productName} 的剩余可退数量不足`);
  }
  row.status = 'SUBMITTED';
  row.submittedAt = nowText();
  row.version += 1;
  row.updateTime = nowText();
  refreshMockAmounts(row);
}

export async function approveSalesReturn(returnOrderId: string, payload: ReturnOrderApprovePayload) {
  if (!useMockApi) {
    await postResult<void>(`${RETURN_API}/${returnOrderId}/approve`, payload);
    return;
  }
  const row = await mockStatusRow(returnOrderId, payload.version, ['SUBMITTED']);
  let positiveCount = 0;
  row.items.forEach(item => {
    const approval = payload.items.find(candidate => candidate.returnOrderItemId === item.returnOrderItemId);
    if (!approval || approval.approvedQty < 0 || approval.approvedQty > item.requestedQty) throw new Error(`${item.productName} 的审核数量无效`);
    const available = item.sourceFulfilledQty - occupiedQuantity(item.sourceOrderItemId, row.returnOrderId);
    if (approval.approvedQty > available) throw new Error(`${item.productName} 的剩余可退数量不足`);
    item.approvedQty = approval.approvedQty;
    if (approval.approvedQty > 0) positiveCount += 1;
  });
  if (positiveCount === 0) throw new Error('至少一条明细的审核数量必须大于 0；全部不通过请使用取消');
  row.status = 'APPROVED';
  row.approvedById = '1900000000000000002';
  row.approvedByName = '销售主管';
  row.approvedAt = nowText();
  row.version += 1;
  row.updateTime = nowText();
  refreshMockAmounts(row);
}

export async function cancelSalesReturn(returnOrderId: string, payload: ReturnOrderReasonActionPayload) {
  if (!useMockApi) {
    await postResult<void>(`${RETURN_API}/${returnOrderId}/cancel`, payload);
    return;
  }
  const row = await mockStatusRow(returnOrderId, payload.version, ['DRAFT', 'SUBMITTED', 'APPROVED']);
  if (row.items.some(item => item.processedQty > 0)) throw new Error('已产生退货入库事实，不能取消');
  row.status = 'CANCELLED';
  row.statusReason = payload.reason.trim();
  row.version += 1;
  row.updateTime = nowText();
  refreshMockAmounts(row);
}

export async function searchSalesReturnSourceOrders(keyword = ''): Promise<ReturnableSourceOrder[]> {
  const sourceOrderNo = keyword.trim();
  if (!useMockApi) {
    const rows = await getResult<ReturnableSourceOrder[]>(`${RETURN_API}/source-orders`, {
      returnType: 'SALES_RETURN',
      sourceOrderNo: sourceOrderNo || undefined,
      pageSize: 10,
    }, { skipPageLoading: true });
    return rows.map(normalizeSourceOrder);
  }
  const details = await getEligibleSalesDetails();
  const rows = await Promise.all(details
    .filter(order => !sourceOrderNo || order.salesNo.includes(sourceOrderNo))
    .map(async order => {
      const items = await mockSourceItems(order.salesOrderId);
      return normalizeSourceOrder({
        sourceOrderId: order.salesOrderId,
        sourceOrderNo: order.salesNo,
        partyId: order.customerId,
        partyCode: order.customerCode,
        partyName: order.customerName,
        warehouseId: order.warehouseId,
        warehouseName: order.warehouseName,
        fulfilledItemCount: items.length,
        totalAvailableReturnQty: items.reduce((sum, item) => sum + item.availableReturnQty, 0),
      });
    }));
  return rows.filter(row => row.fulfilledItemCount > 0).slice(0, 10);
}

export async function listSalesReturnSourceItems(sourceOrderId: string): Promise<ReturnableSourceOrderItem[]> {
  if (useMockApi) return mockSourceItems(sourceOrderId);
  const items = await getResult<ReturnableSourceOrderItem[]>(`${RETURN_API}/source-orders/${sourceOrderId}/items`, { returnType: 'SALES_RETURN' }, { skipPageLoading: true });
  return items.map(normalizeSourceItem);
}

export async function searchSalesReturnCustomerOptions(keyword = '') {
  const rows = await searchCustomerOptions(keyword, 10);
  return rows.map(row => ({ value: row.customerId, label: `${row.customerCode} ${row.customerName}`, disabled: row.status === 0 }));
}

export async function searchSalesReturnWarehouseOptions(keyword = '') {
  return listEnabledSalesWarehouseOptions(keyword, 10);
}
