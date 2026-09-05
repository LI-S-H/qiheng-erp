import type { AxiosRequestConfig } from 'axios';
import { getResult, http, postResult } from '@/api/http';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeStringId } from '@/shared/utils/api-normalizers';
import type {
  WarehouseBatchIdsPayload,
  WarehouseBatchStatusPayload,
  WarehouseCreatePayload,
  WarehouseListItem,
  WarehousePage,
  WarehouseQuery,
  WarehouseUpdatePayload,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const warehouseSeed: Array<WarehouseListItem & { referenced: boolean }> = [
  { warehouseId: '1930000000000000001', warehouseCode: 'WH001', warehouseName: '华东中心仓', contactName: '周宁', contactPhone: '021-5558-1001', address: '上海市嘉定区汇源路88号', status: 1, version: 0, remark: '华东区域日常收发与调拨仓库', createTime: '2026-06-01 09:30:00', updateTime: '2026-07-01 10:05:00', referenced: true },
  { warehouseId: '1930000000000000002', warehouseCode: 'WH002', warehouseName: '华南中心仓', contactName: '林敏', contactPhone: '020-5558-1002', address: '广州市黄埔区开创大道168号', status: 1, version: 0, remark: '华南区域日常收发与调拨仓库', createTime: '2026-06-02 09:30:00', updateTime: '2026-06-11 15:20:00', referenced: true },
  { warehouseId: '1930000000000000003', warehouseCode: 'WH003', warehouseName: '华北中心仓', contactName: '许峰', contactPhone: '010-5558-1003', address: '北京市顺义区物流园北路16号', status: 1, version: 0, remark: '华北区域日常收发与调拨仓库', createTime: '2026-06-03 09:30:00', updateTime: '2026-07-01 09:12:00', referenced: true },
  { warehouseId: '1930000000000000004', warehouseCode: 'WH004', warehouseName: '西南中心仓', contactName: '高洁', contactPhone: '028-5558-1004', address: '成都市双流区航空港大道52号', status: 1, version: 0, remark: '西南区域日常收发与调拨仓库', createTime: '2026-06-04 09:30:00', updateTime: '2026-06-13 15:20:00', referenced: true },
  { warehouseId: '1930000000000000005', warehouseCode: 'WH005', warehouseName: '武汉中转仓', contactName: '陈航', contactPhone: '027-5558-1005', address: '武汉市东西湖区新城十一路30号', status: 1, version: 0, remark: '', createTime: '2026-06-05 09:30:00', updateTime: '2026-06-10 15:20:00', referenced: true },
  { warehouseId: '1930000000000000006', warehouseCode: 'WH006', warehouseName: '西安中转仓', contactName: '赵然', contactPhone: '029-5558-1006', address: '西安市灞桥区港务大道109号', status: 1, version: 0, remark: '', createTime: '2026-06-06 09:30:00', updateTime: '2026-06-11 15:20:00', referenced: true },
  { warehouseId: '1930000000000000007', warehouseCode: 'WH007', warehouseName: '杭州电商仓', contactName: '沈佳', contactPhone: '0571-5558-1007', address: '杭州市余杭区仁和街道云创路9号', status: 1, version: 0, remark: '', createTime: '2026-06-07 09:30:00', updateTime: '2026-06-12 15:20:00', referenced: true },
  { warehouseId: '1930000000000000008', warehouseCode: 'WH008', warehouseName: '南京备货仓', contactName: '王澄', contactPhone: '025-5558-1008', address: '南京市江宁区秣陵工业园21号', status: 1, version: 0, remark: '', createTime: '2026-06-08 09:30:00', updateTime: '2026-06-13 15:20:00', referenced: true },
  { warehouseId: '1930000000000000009', warehouseCode: 'WH009', warehouseName: '青岛周转仓', contactName: '方圆', contactPhone: '0532-5558-1009', address: '青岛市城阳区双元路66号', status: 0, version: 0, remark: '', createTime: '2026-06-01 09:30:00', updateTime: '2026-06-10 15:20:00', referenced: true },
  { warehouseId: '1930000000000000010', warehouseCode: 'WH010', warehouseName: '长沙临时仓', contactName: '唐月', contactPhone: '0731-5558-1010', address: '长沙市雨花区环保东路18号', status: 0, version: 0, remark: '', createTime: '2026-06-02 09:30:00', updateTime: '2026-06-11 15:20:00', referenced: false },
  { warehouseId: '1930000000000000011', warehouseCode: 'WH011', warehouseName: '郑州临时仓', contactName: '', contactPhone: '', address: '郑州市经开区航海东路1268号', status: 1, version: 0, remark: '', createTime: '2026-06-03 09:30:00', updateTime: '2026-06-12 15:20:00', referenced: true },
  { warehouseId: '1930000000000000012', warehouseCode: 'WH012', warehouseName: '合肥样品仓', contactName: '宋哲', contactPhone: '0551-5558-1012', address: '合肥市蜀山区创新大道2800号', status: 1, version: 0, remark: '', createTime: '2026-06-04 09:30:00', updateTime: '2026-06-13 15:20:00', referenced: true },
];

let mockWarehouses = warehouseSeed.map(item => ({ ...item }));

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function assertOptimisticVersion(current: number, expected: number | undefined) {
  if (expected !== undefined && current !== expected) throw new Error('数据已被其他人修改，请刷新后重试');
}

function normalizeWarehouse(item: WarehouseListItem): WarehouseListItem {
  return {
    ...item,
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    warehouseCode: String(item.warehouseCode),
    warehouseName: String(item.warehouseName),
    contactName: String(item.contactName),
    contactPhone: String(item.contactPhone),
    address: String(item.address),
    status: normalizeBinaryStatus(item.status),
    version: normalizeFiniteNumber(item.version, 'version'),
    remark: String(item.remark),
    createTime: String(item.createTime),
    updateTime: String(item.updateTime),
  };
}

export function getMockWarehouseSnapshot() {
  return mockWarehouses.map(({ referenced: _, ...item }) => normalizeWarehouse({ ...item }));
}

function normalizeWarehousePage(page: WarehousePage): WarehousePage {
  const records = page.records.map(normalizeWarehouse);
  const total = page.total === null || page.total === undefined ? null : Number(page.total);
  return {
    records,
    total: total === null ? null : Number.isFinite(total) ? total : null,
    ...(typeof page.hasNext === 'boolean' ? { hasNext: page.hasNext } : {}),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
  };
}

function filterWarehouses(params: WarehouseQuery): WarehousePage {
  let filtered = [...mockWarehouses];
  const warehouseCode = params.warehouseCode?.trim().toLocaleLowerCase();
  const warehouseName = params.warehouseName?.trim().toLocaleLowerCase();
  const contactName = params.contactName?.trim().toLocaleLowerCase();
  const contactPhone = params.contactPhone?.trim().toLocaleLowerCase();
  if (warehouseCode) filtered = filtered.filter(item => item.warehouseCode.toLocaleLowerCase().includes(warehouseCode));
  if (warehouseName) filtered = filtered.filter(item => item.warehouseName.toLocaleLowerCase().includes(warehouseName));
  if (contactName) filtered = filtered.filter(item => item.contactName.toLocaleLowerCase().includes(contactName));
  if (contactPhone) filtered = filtered.filter(item => item.contactPhone.toLocaleLowerCase().includes(contactPhone));
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) {
    filtered = filtered.filter(item => item.status === params.status);
  }
  filtered.sort((a, b) => a.warehouseCode.localeCompare(b.warehouseCode));
  const total = filtered.length;
  const start = (params.pageNum - 1) * params.pageSize;
  return {
    records: filtered.slice(start, start + params.pageSize).map(({ referenced: _, ...item }) => item),
    total,
    hasNext: start + params.pageSize < total,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
  };
}

let nextMockWarehouseSequence = warehouseSeed.length + 1;

function generateMockWarehouseCode() {
  let warehouseCode = '';
  do {
    warehouseCode = `WH${String(nextMockWarehouseSequence).padStart(3, '0')}`;
    nextMockWarehouseSequence += 1;
  } while (mockWarehouses.some(item => item.warehouseCode === warehouseCode));
  return warehouseCode;
}

export function listWarehouses(params: WarehouseQuery, requestConfig?: AxiosRequestConfig) {
  if (useMockApi) return Promise.resolve(normalizeWarehousePage(filterWarehouses(params)));
  const { warehouseCode, warehouseName, contactName, contactPhone, status, ...rest } = params;
  return getResult<WarehousePage>('/warehouse/warehouses', {
    ...rest,
    ...(warehouseCode?.trim() ? { warehouseCode: warehouseCode.trim() } : {}),
    ...(warehouseName?.trim() ? { warehouseName: warehouseName.trim() } : {}),
    ...(contactName?.trim() ? { contactName: contactName.trim() } : {}),
    ...(contactPhone?.trim() ? { contactPhone: contactPhone.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  }, requestConfig).then(normalizeWarehousePage);
}

export function createWarehouse(payload: WarehouseCreatePayload) {
  const { remark, ...requiredPayload } = payload;
  const normalizedRemark = remark?.trim();
  const requestPayload: WarehouseCreatePayload = {
    ...requiredPayload,
    ...(normalizedRemark ? { remark: normalizedRemark } : {}),
  };
  if (useMockApi) {
    const timestamp = nowText();
    const created: WarehouseListItem & { referenced: boolean } = {
      warehouseId: String(Date.now()),
      ...requestPayload,
      warehouseCode: generateMockWarehouseCode(),
      version: 0,
      remark: requestPayload.remark ?? '',
      createTime: timestamp,
      updateTime: timestamp,
      referenced: false,
    };
    mockWarehouses = [...mockWarehouses, created];
    return Promise.resolve(normalizeWarehouse(created));
  }
  return postResult<WarehouseListItem, WarehouseCreatePayload>('/warehouse/warehouses', requestPayload).then(normalizeWarehouse);
}

export async function updateWarehouse(warehouseId: string, payload: WarehouseUpdatePayload) {
  if (useMockApi) {
    mockWarehouses = mockWarehouses.map(item => {
      if (item.warehouseId !== warehouseId) return item;
      assertOptimisticVersion(item.version, payload.version);
      return { ...item, ...payload, version: item.version + 1, updateTime: nowText() };
    });
    const warehouse = mockWarehouses.find(item => item.warehouseId === warehouseId);
    return warehouse ? normalizeWarehouse(warehouse) : null;
  }
  const response = await http.put(`/warehouse/warehouses/${warehouseId}`, payload);
  return normalizeWarehouse(response.data.data as WarehouseListItem);
}

export async function updateWarehouseStatus(warehouseId: string, status: WarehouseListItem['status'], version: number) {
  if (useMockApi) {
    mockWarehouses = mockWarehouses.map(item => {
      if (item.warehouseId !== warehouseId) return item;
      assertOptimisticVersion(item.version, version);
      return { ...item, status, version: item.version + 1, updateTime: nowText() };
    });
    return null;
  }
  const response = await http.patch(`/warehouse/warehouses/${warehouseId}/status`, { status, version });
  return response.data.data as null;
}

export async function deleteWarehouse(warehouseId: string, version: number) {
  if (useMockApi) {
    const target = mockWarehouses.find(item => item.warehouseId === warehouseId);
    if (target) assertOptimisticVersion(target.version, version);
    if (target?.referenced) throw new Error('仓库存在库存余额、入库单、出库单或库存流水，无法删除');
    mockWarehouses = mockWarehouses.filter(item => item.warehouseId !== warehouseId);
    return null;
  }
  const response = await http.delete(`/warehouse/warehouses/${warehouseId}`, { data: { version } });
  return response.data.data as null;
}

export async function batchUpdateWarehouseStatus(payload: WarehouseBatchStatusPayload) {
  if (useMockApi) {
    const updateTime = nowText();
    payload.warehouseIds.forEach(warehouseId => {
      const item = mockWarehouses.find(candidate => candidate.warehouseId === warehouseId);
      if (item) assertOptimisticVersion(item.version, payload.versionByWarehouseId[warehouseId]);
    });
    mockWarehouses = mockWarehouses.map(item => payload.warehouseIds.includes(item.warehouseId)
      ? { ...item, status: payload.status, version: item.version + 1, updateTime }
      : item);
    return null;
  }
  const response = await http.patch('/warehouse/warehouses/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteWarehouses(payload: WarehouseBatchIdsPayload) {
  if (useMockApi) {
    payload.warehouseIds.forEach(warehouseId => {
      const item = mockWarehouses.find(candidate => candidate.warehouseId === warehouseId);
      if (item) assertOptimisticVersion(item.version, payload.versionByWarehouseId[warehouseId]);
    });
    if (mockWarehouses.some(item => payload.warehouseIds.includes(item.warehouseId) && item.referenced)) {
      return Promise.reject(new Error('所选仓库中存在已有库存余额、入库单、出库单或库存流水的数据'));
    }
    mockWarehouses = mockWarehouses.filter(item => !payload.warehouseIds.includes(item.warehouseId));
    return Promise.resolve(null);
  }
  return postResult<null, WarehouseBatchIdsPayload>('/warehouse/warehouses/batch/delete', payload);
}
