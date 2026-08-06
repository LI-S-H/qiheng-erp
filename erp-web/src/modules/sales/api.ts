import { getResult, http, postResult } from '@/api/http';
import type { PageResult } from '@/shared/types/api';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { getMockProductSnapshot, listProducts } from '@/modules/product/products/api';
import { listWarehouses } from '@/modules/warehouse/warehouses/api';
import type { WarehouseListItem } from '@/modules/warehouse/warehouses/types';
import type {
  CustomerBatchIdsPayload,
  CustomerBatchStatusPayload,
  CustomerFormPayload,
  CustomerListItem,
  CustomerOption,
  CustomerQuery,
  SalesOrderFormPayload,
  SalesOrderDetail,
  SalesOrderItem,
  SalesOrderListItem,
  SalesOrderPage,
  SalesOrderQuery,
  SalesOrderStatus,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const customerSeed = [
  ['C001', '上海林间便利连锁', '秦夏', '021-7728-2001', '上海市浦东新区张江镇', 180000, 1, true],
  ['C002', '杭州蓝湖办公采购', '许然', '0571-7728-2002', '杭州市西湖区文三路', 90000, 1, true],
  ['C003', '南京星火校园超市', '陈可', '025-7728-2003', '南京市栖霞区仙林大道', 120000, 1, true],
  ['C004', '广州云帆商贸', '林沐', '020-7728-2004', '广州市天河区体育西路', 60000, 1, false],
  ['C005', '苏州森活社区团购', '周晨', '0512-7728-2005', '苏州市工业园区星湖街', 75000, 0, false],
  ['C006', '上海星河便利店', '陈宁', '021-7728-2101', '上海市浦东新区张江镇', 60000, 1, true],
  ['C007', '成都青柠商贸', '李青', '028-7728-2102', '成都市武侯区天府大道', 80000, 1, true],
  ['C008', '杭州电商客户', '周帆', '0571-7728-2103', '杭州市余杭区电商园', 100000, 1, true],
  ['C009', '广州天河门店', '何俊', '020-7728-2104', '广州市天河区体育西路', 70000, 1, true],
] as const;

const historicalCustomerIds: Record<string, string> = {
  C006: '2020000000000000101',
  C007: '2020000000000000102',
  C008: '2020000000000000103',
  C009: '2020000000000000104',
};

let mockCustomers: Array<CustomerListItem & { referenced: boolean }> = customerSeed.map((item, index) => ({
  customerId: historicalCustomerIds[item[0]] || `2020000000000000${String(index + 1).padStart(3, '0')}`,
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
  updatedById: '1900000000000000003',
  updatedByName: '销售主管',
  referenced: item[7],
}));

let mockOrders: SalesOrderDetail[] = [
  buildOrderSeed('SO202607001', 'C001', 'WH001', 'APPROVED', '2026-07-27', [['P000001', 12, 49.9], ['P000007', 8, 99]], '销售主管', true),
  buildOrderSeed('SO202607002', 'C003', 'WH002', 'PARTIAL_OUTBOUND', '2026-07-24', [['P000021', 20, 19.9]], '销售主管', true),
  buildOrderSeed('SO202607003', 'C004', 'WH008', 'DRAFT', '2026-07-30', [['P000026', 6, 119]], '系统管理员', false),
  buildOrderSeed('SO202607004', 'C002', 'WH001', 'SUBMITTED', '2026-07-30', [['P000002', 10, 69]], '销售主管', true),
  buildOrderSeed('SO202607005', 'C003', 'WH002', 'SUBMITTED', '2026-07-30', [['P000026', 8, 119]], '销售主管', true),
  buildOrderSeed('SO202607006', 'C001', 'WH001', 'OUTBOUND_DONE', '2026-07-20', [['P000001', 6, 49.9]], '销售主管', true),
  buildOrderSeed('SO202607007', 'C004', 'WH008', 'CANCELLED', '2026-07-26', [['P000007', 4, 99]], '系统管理员', true),
  buildOrderSeed('SO202606001', 'C006', 'WH001', 'OUTBOUND_DONE', '2026-06-14', [['P000001', 8, 49.9, '对应 OB202606140002']], '销售主管', true),
  buildOrderSeed('SO202606002', 'C007', 'WH004', 'OUTBOUND_DONE', '2026-06-13', [['P000034', 12, 16.9, '对应 OB202606130007']], '销售主管', true),
  buildOrderSeed('SO202606003', 'C008', 'WH007', 'OUTBOUND_DONE', '2026-06-11', [['P000044', 6, 69, '停用产品仅保留历史追溯']], '销售主管', true),
  buildOrderSeed('SO202606004', 'C009', 'WH002', 'APPROVED', '2026-06-10', [['P000026', 10, 119, '对应 OB202606100015，待确认']], '销售主管', true),
];

let nextCustomerSequence = customerSeed.length + 1;
let nextSalesOrderSequence = 8;

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
  const productId = (1920000000000000000n + BigInt(productCode.slice(1))).toString();
  const product = getMockProductSnapshot(productId);
  if (!product) throw new Error(`产品 ${productCode} 不存在`);
  return product;
}

function mockProductSnapshotById(productId: string) {
  const product = getMockProductSnapshot(productId);
  if (!product) throw new Error(`产品 ${productId} 不存在`);
  return product;
}

function mockWarehouseSnapshot(warehouseId: string): Pick<WarehouseListItem, 'warehouseId' | 'warehouseName'> {
  const warehouses: Record<string, Pick<WarehouseListItem, 'warehouseId' | 'warehouseName'>> = {
    '1930000000000000001': { warehouseId: '1930000000000000001', warehouseName: '华东中心仓' },
    '1930000000000000002': { warehouseId: '1930000000000000002', warehouseName: '华南中心仓' },
    '1930000000000000004': { warehouseId: '1930000000000000004', warehouseName: '西南中心仓' },
    '1930000000000000007': { warehouseId: '1930000000000000007', warehouseName: '杭州电商仓' },
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
  lines: Array<[string, number, number, string?]>,
  createdByName: string,
  submitted: boolean,
): SalesOrderDetail {
  const customer = mockCustomers.find(item => item.customerCode === customerCode)!;
  const warehouses: Record<string, { warehouseId: string; warehouseName: string }> = {
    WH001: { warehouseId: '1930000000000000001', warehouseName: '华东中心仓' },
    WH002: { warehouseId: '1930000000000000002', warehouseName: '华南中心仓' },
    WH004: { warehouseId: '1930000000000000004', warehouseName: '西南中心仓' },
    WH007: { warehouseId: '1930000000000000007', warehouseName: '杭州电商仓' },
    WH008: { warehouseId: '1930000000000000008', warehouseName: '南京备货仓' },
  };
  const warehouse = warehouses[warehouseCode] || warehouses.WH001;
  const orderIds: Record<string, string> = {
    SO202606001: '2022000000000000101',
    SO202606002: '2022000000000000102',
    SO202606003: '2022000000000000103',
    SO202606004: '2022000000000000104',
  };
  const salesOrderId = orderIds[salesNo] || `2022000000000000${salesNo.slice(-3)}`;
  const itemIds: Record<string, string[]> = {
    SO202606001: ['2022100000000000101'],
    SO202606002: ['2022100000000000102'],
    SO202606003: ['2022100000000000103'],
    SO202606004: ['2022100000000000104'],
    SO202607001: ['2022100000000000001', '2022100000000000002'],
    SO202607002: ['2022100000000000003'],
    SO202607003: ['2022100000000000004'],
    SO202607004: ['2022100000000000005'],
    SO202607005: ['2022100000000000006'],
    SO202607006: ['2022100000000000007'],
    SO202607007: ['2022100000000000008'],
  };
  const items = lines.map((line, index) => {
    const product = mockProductSnapshot(line[0]);
    return normalizeOrderItem({
      salesOrderItemId: itemIds[salesNo][index],
      salesOrderId,
      salesNo,
      productId: product.productId,
      productCode: product.productCode,
      productName: product.productName,
      unitName: product.unitName,
      quantityPrecision: product.quantityPrecision,
      quantity: line[1],
      lockedQty: status === 'DRAFT' || status === 'CANCELLED' || status === 'OUTBOUND_DONE'
        ? 0
        : status === 'PARTIAL_OUTBOUND'
          ? line[1] - Math.floor(line[1] / 2)
          : line[1],
      outboundQty: status === 'PARTIAL_OUTBOUND' ? Math.floor(line[1] / 2) : status === 'OUTBOUND_DONE' ? line[1] : 0,
      unitPrice: line[2],
      totalAmount: line[1] * line[2],
      remark: line[3] || '',
    });
  });
  const historicalTimes: Record<string, { createTime: string; updateTime: string; submittedAt: string; approvedAt: string; lockedAt: string | null; remark: string }> = {
    SO202606001: { createTime: '2026-06-13 09:20:00', updateTime: '2026-06-14 10:05:00', submittedAt: '2026-06-13 09:20:00', approvedAt: '2026-06-13 10:00:00', lockedAt: null, remark: '对应 OB202606140002，已确认出库' },
    SO202606002: { createTime: '2026-06-12 13:50:00', updateTime: '2026-06-13 14:50:00', submittedAt: '2026-06-12 13:50:00', approvedAt: '2026-06-12 14:20:00', lockedAt: null, remark: '对应 OB202606130007，已确认出库' },
    SO202606003: { createTime: '2026-06-10 17:10:00', updateTime: '2026-06-11 18:05:00', submittedAt: '2026-06-10 17:10:00', approvedAt: '2026-06-10 17:40:00', lockedAt: null, remark: '对应 OB202606110012，停用产品仅保留历史追溯' },
    SO202606004: { createTime: '2026-06-09 16:00:00', updateTime: '2026-06-10 09:40:00', submittedAt: '2026-06-09 16:00:00', approvedAt: '2026-06-09 16:30:00', lockedAt: '2026-06-10 09:40:00', remark: '对应 OB202606100015，待确认出库' },
  };
  const auditTime = historicalTimes[salesNo];
  const timestamp = auditTime?.createTime || '2026-07-12 10:40:00';
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
    lockedAt: auditTime ? auditTime.lockedAt : (status === 'DRAFT' || status === 'CANCELLED' || status === 'OUTBOUND_DONE' ? null : '2026-07-13 09:15:00'),
    createdById: createdByName === '系统管理员' ? '1900000000000000001' : '1900000000000000003',
    createdByName,
    submittedAt: submitted ? (auditTime?.submittedAt || timestamp) : null,
    submittedById: submitted ? (createdByName === '系统管理员' ? '1900000000000000001' : '1900000000000000003') : null,
    submittedByName: submitted ? createdByName : null,
    approvedById: status === 'APPROVED' || status === 'PARTIAL_OUTBOUND' || status === 'OUTBOUND_DONE' ? '1900000000000000003' : null,
    approvedByName: status === 'APPROVED' || status === 'PARTIAL_OUTBOUND' || status === 'OUTBOUND_DONE' ? '销售主管' : '',
    approvedAt: status === 'APPROVED' || status === 'PARTIAL_OUTBOUND' || status === 'OUTBOUND_DONE' ? (auditTime?.approvedAt || '2026-07-13 09:30:00') : null,
    createTime: timestamp,
    updateTime: auditTime?.updateTime || timestamp,
    version: 0,
    remark: auditTime?.remark || '',
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
    updatedById: normalizeNullableStringId(item.updatedById, 'updatedById'),
    updatedByName: item.updatedByName || null,
  };
}

function normalizeOrderItem(item: SalesOrderItem): SalesOrderItem {
  return {
    ...item,
    salesOrderItemId: normalizeStringId(item.salesOrderItemId, 'salesOrderItemId'),
    salesOrderId: normalizeStringId(item.salesOrderId, 'salesOrderId'),
    productId: normalizeStringId(item.productId, 'productId'),
    quantityPrecision: normalizeFiniteNumber(item.quantityPrecision, 'quantityPrecision'),
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
    submittedById: normalizeNullableStringId(item.submittedById, 'submittedById'),
    submittedByName: item.submittedByName || null,
    approvedById: normalizeNullableStringId(item.approvedById, 'approvedById'),
    version: normalizeFiniteNumber(item.version, 'version'),
  };
}

function normalizeOrderDetail(item: SalesOrderDetail): SalesOrderDetail {
  return { ...normalizeOrder(item), items: item.items.map(normalizeOrderItem) };
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

function filterOrders(params: SalesOrderQuery): SalesOrderPage {
  let filtered = [...mockOrders];
  const salesNo = params.salesNo?.trim().toLocaleLowerCase();
  if (salesNo) filtered = filtered.filter(item => item.salesNo.toLocaleLowerCase().includes(salesNo));
  if (params.customerId && params.customerId !== 'all') filtered = filtered.filter(item => item.customerId === params.customerId);
  if (params.warehouseId && params.warehouseId !== 'all') filtered = filtered.filter(item => item.warehouseId === params.warehouseId);
  if (params.status && params.status !== 'all') filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => b.createTime.localeCompare(a.createTime));
  const records = pageSlice(filtered, params.pageNum, params.pageSize).map(({ items: _, ...item }) => item);
  return { records, total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize };
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
      updatedById: '1900000000000000001',
      updatedByName: '系统管理员',
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
      return { ...item, ...payload, version: item.version + 1, updateTime: nowText(), updatedById: '1900000000000000001', updatedByName: '系统管理员' };
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
      return { ...item, status, version: item.version + 1, updateTime: nowText(), updatedById: '1900000000000000001', updatedByName: '系统管理员' };
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
    mockCustomers = mockCustomers.map(item => payload.customerIds.includes(item.customerId) ? { ...item, status: payload.status, version: item.version + 1, updateTime: nowText(), updatedById: '1900000000000000001', updatedByName: '系统管理员' } : item);
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
    return Promise.resolve(normalizePage(page, normalizeOrder));
  }
  const { salesNo, customerId, warehouseId, status, ...rest } = params;
  return getResult<SalesOrderPage>('/sales/orders', {
    ...rest,
    ...(salesNo?.trim() ? { salesNo: salesNo.trim() } : {}),
    ...(customerId && customerId !== 'all' ? { customerId } : {}),
    ...(warehouseId && warehouseId !== 'all' ? { warehouseId } : {}),
    ...(status && status !== 'all' ? { status } : {}),
  }).then(page => normalizePage(page, normalizeOrder));
}

export function getSalesOrderDetail(salesOrderId: string) {
  if (useMockApi) {
    const order = mockOrders.find(item => item.salesOrderId === salesOrderId);
    return order ? Promise.resolve(normalizeOrderDetail(order)) : Promise.reject(new Error('销售订单不存在'));
  }
  return getResult<SalesOrderDetail>(`/sales/orders/${salesOrderId}`).then(normalizeOrderDetail);
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
      quantityPrecision: product.quantityPrecision,
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
    const salesNo = `SO202607${String(nextSalesOrderSequence++).padStart(3, '0')}`;
    const timestamp = nowText();
    const items = buildOrderItems(salesOrderId, salesNo, payload);
    const created = normalizeOrderDetail({
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
      submittedById: null,
      submittedByName: null,
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
  return postResult<SalesOrderDetail, SalesOrderFormPayload>('/sales/orders', payload).then(normalizeOrderDetail);
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
    const updated = normalizeOrderDetail({
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
  return normalizeOrderDetail(response.data.data as SalesOrderDetail);
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
        submittedById: action === 'submit' ? '1900000000000000003' : item.submittedById,
        submittedByName: action === 'submit' ? '销售主管' : item.submittedByName,
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

/** 仅读取启用产品总数，用于销售单明细的可添加行上限判断。 */
export async function getEnabledSalesProductTotal() {
  const page = await listProducts({ pageNum: 1, pageSize: 1, status: 1 });
  return page.total;
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
