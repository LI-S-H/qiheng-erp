import { getResult, http, postResult } from '@/api/http';
import type { PageResult } from '@/shared/types/api';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { listProducts } from '@/modules/product/products/api';
import { listWarehouses } from '@/modules/warehouse/warehouses/api';
import type { ProductListItem } from '@/modules/product/products/types';
import type { WarehouseListItem } from '@/modules/warehouse/warehouses/types';
import type {
  CustomerBatchIdsPayload,
  CustomerBatchStatusPayload,
  CustomerFormPayload,
  CustomerListItem,
  CustomerOption,
  CustomerQuery,
  SalesOrderFormPayload,
  SalesOrderItem,
  SalesOrderListItem,
  SalesOrderPage,
  SalesOrderQuery,
  SalesOrderStatus,
  SalesOrderSummary,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const customerSeed = [
  ['C001', '上海林间便利连锁', '秦夏', '021-7728-2001', '上海市浦东新区张江镇', 180000, 1, true],
  ['C002', '杭州蓝湖办公采购', '许然', '0571-7728-2002', '杭州市西湖区文三路', 90000, 1, true],
  ['C003', '南京星火校园超市', '陈可', '025-7728-2003', '南京市栖霞区仙林大道', 120000, 1, true],
  ['C004', '广州云帆商贸', '林沐', '020-7728-2004', '广州市天河区体育西路', 60000, 1, false],
  ['C005', '苏州森活社区团购', '周晨', '0512-7728-2005', '苏州市工业园区星湖街', 75000, 0, false],
] as const;

let mockCustomers: Array<CustomerListItem & { referenced: boolean }> = customerSeed.map((item, index) => ({
  customerId: `1950000000000000${String(index + 1).padStart(3, '0')}`,
  customerCode: item[0],
  customerName: item[1],
  contactName: item[2],
  contactPhone: item[3],
  address: item[4],
  creditLimit: item[5],
  status: item[6],
  version: 0,
  remark: index < 3 ? '重点销售客户，订单创建时需关注信用额度' : '',
  createTime: `2026-06-${String(4 + index).padStart(2, '0')} 09:20:00`,
  updateTime: `2026-06-${String(14 + (index % 4)).padStart(2, '0')} 15:20:00`,
  referenced: item[7],
}));

let mockOrders: SalesOrderListItem[] = [
  buildOrderSeed('SO202606001', 'C001', 'WH001', 'APPROVED', '2026-06-27', [['P0001', 12, 49.9], ['P0003', 8, 99]], '销售主管', true),
  buildOrderSeed('SO202606002', 'C003', 'WH002', 'PARTIAL_OUTBOUND', '2026-06-24', [['P0005', 20, 19.9]], '销售主管', true),
  buildOrderSeed('SO202606003', 'C004', 'WH008', 'DRAFT', '2026-06-30', [['P0007', 6, 119]], '系统管理员', false),
  buildOrderSeed('SO202606004', 'C002', 'WH001', 'SUBMITTED', '2026-06-30', [['P0002', 10, 69]], '销售专员', true),
  buildOrderSeed('SO202606005', 'C003', 'WH002', 'SUBMITTED', '2026-06-30', [['P0007', 8, 119]], '销售专员', true),
  buildOrderSeed('SO202606006', 'C001', 'WH001', 'OUTBOUND_DONE', '2026-06-20', [['P0001', 6, 49.9]], '销售主管', true),
  buildOrderSeed('SO202606007', 'C004', 'WH008', 'CANCELLED', '2026-06-26', [['P0003', 4, 99]], '系统管理员', true),
];

let nextCustomerSequence = customerSeed.length + 1;
let nextSalesOrderSequence = mockOrders.length + 1;

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function assertOptimisticVersion(current: number, expected: number | undefined) {
  if (expected !== undefined && current !== expected) throw new Error('数据已被其他人修改，请刷新后重试');
}

function generateCode(prefix: string, sequence: number, width = 3) {
  return `${prefix}${String(sequence).padStart(width, '0')}`;
}

function mockProductSnapshot(productCode: string) {
  const productMap: Record<string, Pick<ProductListItem, 'productId' | 'productCode' | 'productName' | 'unitName' | 'referenceSalePrice' | 'quantityPrecision'>> = {
    P0001: { productId: '1920000000000000001', productCode: 'P0001', productName: '经典原味苏打水', unitName: '箱', referenceSalePrice: 49.9, quantityPrecision: 0 },
    P0002: { productId: '1920000000000000002', productCode: 'P0002', productName: '速溶黑咖啡', unitName: '盒', referenceSalePrice: 69, quantityPrecision: 0 },
    P0003: { productId: '1920000000000000003', productCode: 'P0003', productName: '每日坚果混合装', unitName: '盒', referenceSalePrice: 99, quantityPrecision: 0 },
    P0005: { productId: '1920000000000000005', productCode: 'P0005', productName: '中性签字笔', unitName: '盒', referenceSalePrice: 19.9, quantityPrecision: 0 },
    P0007: { productId: '1920000000000000007', productCode: 'P0007', productName: 'A4复印纸', unitName: '箱', referenceSalePrice: 119, quantityPrecision: 0 },
    P0015: { productId: '1920000000000000015', productCode: 'P0015', productName: '散装东北大米', unitName: 'kg', referenceSalePrice: 7.9, quantityPrecision: 2 },
  };
  return productMap[productCode] || productMap.P0001;
}

function mockProductSnapshotById(productId: string) {
  const byStatic = ['P0001', 'P0002', 'P0003', 'P0005', 'P0007', 'P0015']
    .map(mockProductSnapshot)
    .find(item => item.productId === productId);
  return byStatic || mockProductSnapshot('P0001');
}

function mockWarehouseSnapshot(warehouseId: string): Pick<WarehouseListItem, 'warehouseId' | 'warehouseName'> {
  const warehouses: Record<string, Pick<WarehouseListItem, 'warehouseId' | 'warehouseName'>> = {
    '1930000000000000001': { warehouseId: '1930000000000000001', warehouseName: '华东中心仓' },
    '1930000000000000002': { warehouseId: '1930000000000000002', warehouseName: '华南中心仓' },
    '1930000000000000008': { warehouseId: '1930000000000000008', warehouseName: '南京备货仓' },
  };
  return warehouses[warehouseId] || { warehouseId, warehouseName: '出库仓库' };
}

function buildOrderSeed(
  salesNo: string,
  customerCode: string,
  warehouseCode: string,
  status: SalesOrderStatus,
  expectedDeliveryDate: string,
  lines: Array<[string, number, number]>,
  createdByName: string,
  submitted: boolean,
): SalesOrderListItem {
  const customer = mockCustomers.find(item => item.customerCode === customerCode)!;
  const warehouses: Record<string, { warehouseId: string; warehouseName: string }> = {
    WH001: { warehouseId: '1930000000000000001', warehouseName: '华东中心仓' },
    WH002: { warehouseId: '1930000000000000002', warehouseName: '华南中心仓' },
    WH008: { warehouseId: '1930000000000000008', warehouseName: '南京备货仓' },
  };
  const warehouse = warehouses[warehouseCode] || warehouses.WH001;
  const salesOrderId = `1952000000000000${salesNo.slice(-3)}`;
  const items = lines.map((line, index) => {
    const product = mockProductSnapshot(line[0]);
    return normalizeOrderItem({
      salesOrderItemId: `${salesOrderId}${index + 1}`,
      salesOrderId,
      salesNo,
      productId: product.productId,
      productCode: product.productCode,
      productName: product.productName,
      unitName: product.unitName,
      quantity: line[1],
      lockedQty: status === 'DRAFT' || status === 'CANCELLED' ? 0 : line[1],
      outboundQty: status === 'PARTIAL_OUTBOUND' ? Math.floor(line[1] / 2) : status === 'OUTBOUND_DONE' ? line[1] : 0,
      unitPrice: line[2],
      totalAmount: line[1] * line[2],
      remark: '',
    });
  });
  const timestamp = '2026-06-12 10:40:00';
  return {
    salesOrderId,
    salesNo,
    customerId: customer.customerId,
    customerCode: customer.customerCode,
    customerName: customer.customerName,
    warehouseId: warehouse.warehouseId,
    warehouseName: warehouse.warehouseName,
    status,
    totalAmount: items.reduce((sum, item) => sum + item.totalAmount, 0),
    expectedDeliveryDate,
    lockedAt: status === 'DRAFT' || status === 'CANCELLED' ? null : '2026-06-13 09:15:00',
    createdById: '1900000000000000001',
    createdByName,
    submittedAt: submitted ? timestamp : null,
    approvedById: status === 'APPROVED' || status === 'PARTIAL_OUTBOUND' || status === 'OUTBOUND_DONE' ? '1900000000000000001' : null,
    approvedByName: status === 'APPROVED' || status === 'PARTIAL_OUTBOUND' || status === 'OUTBOUND_DONE' ? '销售主管' : '',
    approvedAt: status === 'APPROVED' || status === 'PARTIAL_OUTBOUND' || status === 'OUTBOUND_DONE' ? '2026-06-13 09:30:00' : null,
    createTime: timestamp,
    updateTime: timestamp,
    version: 0,
    remark: '',
    items,
  };
}

function normalizeCustomer(item: CustomerListItem): CustomerListItem {
  return {
    ...item,
    customerId: normalizeStringId(item.customerId, 'customerId'),
    customerCode: String(item.customerCode),
    customerName: String(item.customerName),
    contactName: String(item.contactName),
    contactPhone: String(item.contactPhone),
    address: String(item.address),
    creditLimit: normalizeFiniteNumber(item.creditLimit, 'creditLimit'),
    status: normalizeBinaryStatus(item.status),
    version: normalizeFiniteNumber(item.version, 'version'),
    remark: String(item.remark),
  };
}

function normalizeOrderItem(item: SalesOrderItem): SalesOrderItem {
  return {
    ...item,
    salesOrderItemId: normalizeStringId(item.salesOrderItemId, 'salesOrderItemId'),
    salesOrderId: normalizeStringId(item.salesOrderId, 'salesOrderId'),
    productId: normalizeStringId(item.productId, 'productId'),
    quantity: normalizeFiniteNumber(item.quantity, 'quantity'),
    lockedQty: normalizeFiniteNumber(item.lockedQty, 'lockedQty'),
    outboundQty: normalizeFiniteNumber(item.outboundQty, 'outboundQty'),
    unitPrice: normalizeFiniteNumber(item.unitPrice, 'unitPrice'),
    totalAmount: normalizeFiniteNumber(item.totalAmount, 'totalAmount'),
  };
}

function normalizeOrder(item: SalesOrderListItem): SalesOrderListItem {
  return {
    ...item,
    salesOrderId: normalizeStringId(item.salesOrderId, 'salesOrderId'),
    customerId: normalizeStringId(item.customerId, 'customerId'),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    totalAmount: normalizeFiniteNumber(item.totalAmount, 'totalAmount'),
    lockedAt: item.lockedAt || null,
    expectedDeliveryDate: item.expectedDeliveryDate || null,
    createdById: normalizeNullableStringId(item.createdById, 'createdById'),
    approvedById: normalizeNullableStringId(item.approvedById, 'approvedById'),
    version: normalizeFiniteNumber(item.version, 'version'),
    items: item.items.map(normalizeOrderItem),
  };
}

function normalizePage<T>(page: PageResult<T>, mapper: (item: T) => T): PageResult<T> {
  return {
    records: page.records.map(mapper),
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
  };
}

function keywordField(keyword: string, codeField: string, nameField: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { [codeField]: value } : { [nameField]: value };
}

function pageSlice<T>(records: T[], pageNum: number, pageSize: number) {
  return records.slice((pageNum - 1) * pageSize, (pageNum - 1) * pageSize + pageSize);
}

function filterCustomers(params: CustomerQuery): PageResult<CustomerListItem> {
  let filtered = [...mockCustomers];
  const customerCode = params.customerCode?.trim().toLocaleLowerCase();
  const customerName = params.customerName?.trim().toLocaleLowerCase();
  const contactName = params.contactName?.trim().toLocaleLowerCase();
  if (customerCode) filtered = filtered.filter(item => item.customerCode.toLocaleLowerCase().includes(customerCode));
  if (customerName) filtered = filtered.filter(item => item.customerName.toLocaleLowerCase().includes(customerName));
  if (contactName) filtered = filtered.filter(item => item.contactName.toLocaleLowerCase().includes(contactName));
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => a.customerCode.localeCompare(b.customerCode));
  return { records: pageSlice(filtered, params.pageNum, params.pageSize).map(({ referenced: _, ...item }) => item), total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize };
}

function buildOrderSummary(records: SalesOrderListItem[]): SalesOrderSummary {
  return {
    draftCount: records.filter(item => item.status === 'DRAFT').length,
    submittedCount: records.filter(item => item.status === 'SUBMITTED').length,
    approvedCount: records.filter(item => item.status === 'APPROVED').length,
    outboundPendingCount: records.filter(item => item.status === 'APPROVED' || item.status === 'PARTIAL_OUTBOUND').length,
  };
}

function filterOrders(params: SalesOrderQuery): SalesOrderPage {
  let filtered = [...mockOrders];
  const salesNo = params.salesNo?.trim().toLocaleLowerCase();
  if (salesNo) filtered = filtered.filter(item => item.salesNo.toLocaleLowerCase().includes(salesNo));
  if (params.customerId && params.customerId !== 'all') filtered = filtered.filter(item => item.customerId === params.customerId);
  if (params.warehouseId && params.warehouseId !== 'all') filtered = filtered.filter(item => item.warehouseId === params.warehouseId);
  if (params.status && params.status !== 'all') filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => b.createTime.localeCompare(a.createTime));
  const records = pageSlice(filtered, params.pageNum, params.pageSize);
  return { records, total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize, summary: buildOrderSummary(records) };
}

export function listCustomers(params: CustomerQuery) {
  if (useMockApi) return Promise.resolve(normalizePage(filterCustomers(params), normalizeCustomer));
  const { customerCode, customerName, contactName, status, ...rest } = params;
  return getResult<PageResult<CustomerListItem>>('/sales/customers', {
    ...rest,
    ...(customerCode?.trim() ? { customerCode: customerCode.trim() } : {}),
    ...(customerName?.trim() ? { customerName: customerName.trim() } : {}),
    ...(contactName?.trim() ? { contactName: contactName.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  }).then(page => normalizePage(page, normalizeCustomer));
}

export function listCustomerOptions(): Promise<CustomerOption[]> {
  if (useMockApi) {
    return Promise.resolve(mockCustomers.map(item => ({
      customerId: item.customerId,
      customerCode: item.customerCode,
      customerName: item.customerName,
      status: item.status,
    })));
  }
  return getResult<CustomerOption[]>('/sales/customers/options');
}

export async function searchCustomerOptions(keyword = '', pageSize = 10): Promise<CustomerOption[]> {
  const page = await listCustomers({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'customerCode', 'customerName'),
  });
  return page.records.map(item => ({
    customerId: item.customerId,
    customerCode: item.customerCode,
    customerName: item.customerName,
    status: item.status,
  }));
}

export function createCustomer(payload: CustomerFormPayload) {
  if (useMockApi) {
    const timestamp = nowText();
    const created: CustomerListItem & { referenced: boolean } = {
      customerId: String(Date.now()),
      customerCode: generateCode('C', nextCustomerSequence++),
      ...payload,
      version: 0,
      createTime: timestamp,
      updateTime: timestamp,
      referenced: false,
    };
    mockCustomers = [...mockCustomers, created];
    return Promise.resolve(normalizeCustomer(created));
  }
  return postResult<CustomerListItem, CustomerFormPayload>('/sales/customers', payload).then(normalizeCustomer);
}

export async function updateCustomer(customerId: string, payload: CustomerFormPayload) {
  if (useMockApi) {
    mockCustomers = mockCustomers.map(item => {
      if (item.customerId !== customerId) return item;
      assertOptimisticVersion(item.version, payload.version);
      return { ...item, ...payload, version: item.version + 1, updateTime: nowText() };
    });
    const customer = mockCustomers.find(item => item.customerId === customerId);
    return customer ? normalizeCustomer(customer) : null;
  }
  const response = await http.put(`/sales/customers/${customerId}`, payload);
  return normalizeCustomer(response.data.data as CustomerListItem);
}

export async function updateCustomerStatus(customerId: string, status: 0 | 1, version: number) {
  if (useMockApi) {
    mockCustomers = mockCustomers.map(item => {
      if (item.customerId !== customerId) return item;
      assertOptimisticVersion(item.version, version);
      return { ...item, status, version: item.version + 1, updateTime: nowText() };
    });
    return null;
  }
  const response = await http.patch(`/sales/customers/${customerId}/status`, { status, version });
  return response.data.data as null;
}

export function deleteCustomer(customerId: string, version: number) {
  if (useMockApi) {
    const target = mockCustomers.find(item => item.customerId === customerId);
    if (target) assertOptimisticVersion(target.version, version);
    if (target?.referenced) return Promise.reject(new Error('客户已被销售订单引用，无法删除'));
    mockCustomers = mockCustomers.filter(item => item.customerId !== customerId);
    return Promise.resolve(null);
  }
  return http.delete(`/sales/customers/${customerId}`, { data: { version } }).then(response => response.data.data as null);
}

export function batchUpdateCustomerStatus(payload: CustomerBatchStatusPayload) {
  if (useMockApi) {
    payload.customerIds.forEach(customerId => {
      const item = mockCustomers.find(candidate => candidate.customerId === customerId);
      if (item) assertOptimisticVersion(item.version, payload.versionByCustomerId[customerId]);
    });
    mockCustomers = mockCustomers.map(item => payload.customerIds.includes(item.customerId) ? { ...item, status: payload.status, version: item.version + 1, updateTime: nowText() } : item);
    return Promise.resolve(null);
  }
  return http.patch('/sales/customers/batch/status', payload).then(response => response.data.data as null);
}

export function batchDeleteCustomers(payload: CustomerBatchIdsPayload) {
  if (useMockApi) {
    payload.customerIds.forEach(customerId => {
      const item = mockCustomers.find(candidate => candidate.customerId === customerId);
      if (item) assertOptimisticVersion(item.version, payload.versionByCustomerId[customerId]);
    });
    if (mockCustomers.some(item => payload.customerIds.includes(item.customerId) && item.referenced)) {
      return Promise.reject(new Error('所选客户中存在已被销售订单引用的数据'));
    }
    mockCustomers = mockCustomers.filter(item => !payload.customerIds.includes(item.customerId));
    return Promise.resolve(null);
  }
  return postResult<null, CustomerBatchIdsPayload>('/sales/customers/batch/delete', payload);
}

export function listSalesOrders(params: SalesOrderQuery) {
  if (useMockApi) {
    const page = filterOrders(params);
    const normalized = normalizePage(page, normalizeOrder);
    return Promise.resolve({ ...normalized, summary: buildOrderSummary(normalized.records) });
  }
  const { salesNo, customerId, warehouseId, status, ...rest } = params;
  return getResult<SalesOrderPage>('/sales/orders', {
    ...rest,
    ...(salesNo?.trim() ? { salesNo: salesNo.trim() } : {}),
    ...(customerId && customerId !== 'all' ? { customerId } : {}),
    ...(warehouseId && warehouseId !== 'all' ? { warehouseId } : {}),
    ...(status && status !== 'all' ? { status } : {}),
  }).then(page => {
    const normalized = normalizePage(page, normalizeOrder);
    return { ...normalized, summary: buildOrderSummary(normalized.records) };
  });
}

function buildOrderItems(orderId: string, salesNo: string, payload: SalesOrderFormPayload, existingItems: SalesOrderItem[] = []) {
  return payload.items.map((line, index) => {
    const product = mockProductSnapshotById(line.productId);
    return normalizeOrderItem({
      salesOrderItemId: line.salesOrderItemId || existingItems[index]?.salesOrderItemId || `${orderId}${index + 1}`,
      salesOrderId: orderId,
      salesNo,
      productId: product.productId,
      productCode: product.productCode,
      productName: product.productName,
      unitName: product.unitName,
      quantity: line.quantity,
      lockedQty: 0,
      outboundQty: 0,
      unitPrice: line.unitPrice,
      totalAmount: line.quantity * line.unitPrice,
      remark: line.remark.trim(),
    });
  });
}

export function createSalesOrder(payload: SalesOrderFormPayload) {
  if (useMockApi) {
    const customer = mockCustomers.find(item => item.customerId === payload.customerId);
    if (!customer || customer.status === 0) return Promise.reject(new Error('请选择启用状态的客户'));
    const warehouse = mockWarehouseSnapshot(payload.warehouseId);
    const salesOrderId = String(Date.now());
    const salesNo = `SO202606${String(nextSalesOrderSequence++).padStart(3, '0')}`;
    const timestamp = nowText();
    const items = buildOrderItems(salesOrderId, salesNo, payload);
    const created = normalizeOrder({
      salesOrderId,
      salesNo,
      customerId: customer.customerId,
      customerCode: customer.customerCode,
      customerName: customer.customerName,
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      status: 'DRAFT',
      totalAmount: items.reduce((sum, item) => sum + item.totalAmount, 0),
      expectedDeliveryDate: payload.expectedDeliveryDate || null,
      lockedAt: null,
      createdById: '1900000000000000001',
      createdByName: '系统管理员',
      submittedAt: null,
      approvedById: null,
      approvedByName: '',
      approvedAt: null,
      createTime: timestamp,
      updateTime: timestamp,
      version: 0,
      remark: payload.remark.trim(),
      items,
    });
    mockOrders = [created, ...mockOrders];
    return Promise.resolve(created);
  }
  return postResult<SalesOrderListItem, SalesOrderFormPayload>('/sales/orders', payload).then(normalizeOrder);
}

export async function updateSalesOrder(salesOrderId: string, payload: SalesOrderFormPayload) {
  if (useMockApi) {
    const existing = mockOrders.find(item => item.salesOrderId === salesOrderId);
    if (!existing) return Promise.reject(new Error('销售订单不存在'));
    assertOptimisticVersion(existing.version, payload.version);
    if (existing.status !== 'DRAFT' && existing.status !== 'SUBMITTED') return Promise.reject(new Error('仅草稿或已提交销售单可以编辑'));
    const customer = mockCustomers.find(item => item.customerId === payload.customerId);
    if (!customer || customer.status === 0) return Promise.reject(new Error('请选择启用状态的客户'));
    const warehouse = mockWarehouseSnapshot(payload.warehouseId);
    const items = buildOrderItems(salesOrderId, existing.salesNo, payload, existing.items);
    const updated = normalizeOrder({
      ...existing,
      customerId: customer.customerId,
      customerCode: customer.customerCode,
      customerName: customer.customerName,
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      totalAmount: items.reduce((sum, item) => sum + item.totalAmount, 0),
      expectedDeliveryDate: payload.expectedDeliveryDate || null,
      version: existing.version + 1,
      updateTime: nowText(),
      remark: payload.remark.trim(),
      items,
    });
    mockOrders = mockOrders.map(item => (item.salesOrderId === salesOrderId ? updated : item));
    return Promise.resolve(updated);
  }
  const response = await http.put(`/sales/orders/${salesOrderId}`, payload);
  return normalizeOrder(response.data.data as SalesOrderListItem);
}

export function updateSalesOrderStatus(salesOrderId: string, action: 'submit' | 'approve' | 'cancel', version: number) {
  if (useMockApi) {
    const timestamp = nowText();
    mockOrders = mockOrders.map(item => {
      if (item.salesOrderId !== salesOrderId) return item;
      assertOptimisticVersion(item.version, version);
      if (action === 'submit' && item.status !== 'DRAFT') throw new Error('仅草稿销售单可以提交');
      if (action === 'approve' && item.status !== 'SUBMITTED') throw new Error('仅已提交销售单可以审核');
      if (action === 'cancel' && item.status !== 'DRAFT' && item.status !== 'SUBMITTED') throw new Error('仅草稿或已提交销售单可以取消');
      if ((action === 'submit' || action === 'approve') && !item.expectedDeliveryDate) throw new Error('提交或审核销售订单前必须维护预计发货日期');
      const lockedItems = action === 'cancel' ? item.items.map(line => ({ ...line, lockedQty: 0 })) : item.items.map(line => ({ ...line, lockedQty: line.quantity }));
      return {
        ...item,
        status: action === 'submit' ? 'SUBMITTED' : action === 'approve' ? 'APPROVED' : 'CANCELLED',
        submittedAt: action === 'submit' ? timestamp : item.submittedAt,
        lockedAt: action === 'cancel' ? null : item.lockedAt || timestamp,
        approvedById: action === 'approve' ? '1900000000000000001' : item.approvedById,
        approvedByName: action === 'approve' ? '销售主管' : item.approvedByName,
        approvedAt: action === 'approve' ? timestamp : item.approvedAt,
        version: item.version + 1,
        updateTime: timestamp,
        items: lockedItems,
      };
    });
    return Promise.resolve(null);
  }
  return postResult<null, { version: number }>(`/sales/orders/${salesOrderId}/${action}`, { version });
}

export async function listEnabledSalesProductOptions(keyword = '', pageSize = 10) {
  const page = await listProducts({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'productCode', 'productName'),
  });
  return page.records.map(item => ({ value: item.productId, label: `${item.productCode} ${item.productName}`, product: item }));
}

export async function listEnabledSalesWarehouseOptions(keyword = '', pageSize = 10) {
  const page = await listWarehouses({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'warehouseCode', 'warehouseName'),
  });
  return page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}`, warehouse: item }));
}
