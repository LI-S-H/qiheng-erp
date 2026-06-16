import { getResult, http, postResult } from '@/api/http';
import type { PageResult } from '@/shared/types/api';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeStringId } from '@/shared/utils/api-normalizers';
import type {
  WarehouseBatchIdsPayload,
  WarehouseBatchStatusPayload,
  WarehouseCreatePayload,
  WarehouseListItem,
  WarehouseQuery,
  WarehouseUpdatePayload,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const warehouseSeed = [
  ['WH001', '华东中心仓', '周宁', '021-5558-1001', '上海市嘉定区汇源路 88 号', 1, true],
  ['WH002', '华南中心仓', '林敏', '020-5558-1002', '广州市黄埔区开创大道 168 号', 1, true],
  ['WH003', '华北中心仓', '许峰', '010-5558-1003', '北京市顺义区物流园北路 16 号', 1, true],
  ['WH004', '西南中心仓', '高洁', '028-5558-1004', '成都市双流区航空港大道 52 号', 1, true],
  ['WH005', '武汉中转仓', '陈航', '027-5558-1005', '武汉市东西湖区新城十一路 30 号', 1, true],
  ['WH006', '西安中转仓', '赵然', '029-5558-1006', '西安市灞桥区港务大道 109 号', 1, true],
  ['WH007', '杭州电商仓', '沈佳', '0571-5558-1007', '杭州市余杭区仁和街道云创路 9 号', 1, true],
  ['WH008', '南京备货仓', '王澄', '025-5558-1008', '南京市江宁区秣陵工业园 21 号', 1, true],
  ['WH009', '青岛周转仓', '方圆', '0532-5558-1009', '青岛市城阳区双元路 66 号', 0, true],
  ['WH010', '长沙临时仓', '唐月', '0731-5558-1010', '长沙市雨花区环保东路 18 号', 0, false],
  ['WH011', '郑州临时仓', '', '', '郑州市经开区航海东路 1268 号', 1, false],
  ['WH012', '合肥样品仓', '宋哲', '0551-5558-1012', '合肥市蜀山区创新大道 2800 号', 1, false],
] as const;

let mockWarehouses: Array<WarehouseListItem & { referenced: boolean }> = warehouseSeed.map((item, index) => ({
  warehouseId: `1930000000000000${String(index + 1).padStart(3, '0')}`,
  warehouseCode: item[0],
  warehouseName: item[1],
  contactName: item[2],
  contactPhone: item[3],
  address: item[4],
  status: item[5],
  remark: index < 4 ? '区域主仓，承担日常收发与调拨' : '',
  createTime: `2026-06-${String(1 + (index % 8)).padStart(2, '0')} 09:30:00`,
  updateTime: `2026-06-${String(10 + (index % 4)).padStart(2, '0')} 15:20:00`,
  referenced: item[6],
}));

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
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
    remark: String(item.remark),
    createTime: String(item.createTime),
    updateTime: String(item.updateTime),
  };
}

function normalizeWarehousePage(page: PageResult<WarehouseListItem>): PageResult<WarehouseListItem> {
  return {
    records: page.records.map(normalizeWarehouse),
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
  };
}

function filterWarehouses(params: WarehouseQuery): PageResult<WarehouseListItem> {
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

export function listWarehouses(params: WarehouseQuery) {
  if (useMockApi) return Promise.resolve(normalizeWarehousePage(filterWarehouses(params)));
  const { warehouseCode, warehouseName, contactName, contactPhone, status, ...rest } = params;
  return getResult<PageResult<WarehouseListItem>>('/warehouse/warehouses', {
    ...rest,
    ...(warehouseCode?.trim() ? { warehouseCode: warehouseCode.trim() } : {}),
    ...(warehouseName?.trim() ? { warehouseName: warehouseName.trim() } : {}),
    ...(contactName?.trim() ? { contactName: contactName.trim() } : {}),
    ...(contactPhone?.trim() ? { contactPhone: contactPhone.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  }).then(normalizeWarehousePage);
}

export function createWarehouse(payload: WarehouseCreatePayload) {
  if (useMockApi) {
    const timestamp = nowText();
    const created: WarehouseListItem & { referenced: boolean } = {
      warehouseId: String(Date.now()),
      ...payload,
      warehouseCode: generateMockWarehouseCode(),
      createTime: timestamp,
      updateTime: timestamp,
      referenced: false,
    };
    mockWarehouses = [...mockWarehouses, created];
    return Promise.resolve(normalizeWarehouse(created));
  }
  return postResult<WarehouseListItem, WarehouseCreatePayload>('/warehouse/warehouses', payload).then(normalizeWarehouse);
}

export async function updateWarehouse(warehouseId: string, payload: WarehouseUpdatePayload) {
  if (useMockApi) {
    mockWarehouses = mockWarehouses.map(item => item.warehouseId === warehouseId
      ? { ...item, ...payload, updateTime: nowText() }
      : item);
    const warehouse = mockWarehouses.find(item => item.warehouseId === warehouseId);
    return warehouse ? normalizeWarehouse(warehouse) : null;
  }
  const response = await http.put(`/warehouse/warehouses/${warehouseId}`, payload);
  return normalizeWarehouse(response.data.data as WarehouseListItem);
}

export async function updateWarehouseStatus(warehouseId: string, status: WarehouseListItem['status']) {
  if (useMockApi) {
    mockWarehouses = mockWarehouses.map(item => item.warehouseId === warehouseId
      ? { ...item, status, updateTime: nowText() }
      : item);
    return null;
  }
  const response = await http.patch(`/warehouse/warehouses/${warehouseId}/status`, { status });
  return response.data.data as null;
}

export async function deleteWarehouse(warehouseId: string) {
  if (useMockApi) {
    const target = mockWarehouses.find(item => item.warehouseId === warehouseId);
    if (target?.referenced) throw new Error('仓库存在库存余额或出入库记录，无法删除');
    mockWarehouses = mockWarehouses.filter(item => item.warehouseId !== warehouseId);
    return null;
  }
  const response = await http.delete(`/warehouse/warehouses/${warehouseId}`);
  return response.data.data as null;
}

export async function batchUpdateWarehouseStatus(payload: WarehouseBatchStatusPayload) {
  if (useMockApi) {
    const updateTime = nowText();
    mockWarehouses = mockWarehouses.map(item => payload.warehouseIds.includes(item.warehouseId)
      ? { ...item, status: payload.status, updateTime }
      : item);
    return null;
  }
  const response = await http.patch('/warehouse/warehouses/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteWarehouses(payload: WarehouseBatchIdsPayload) {
  if (useMockApi) {
    if (mockWarehouses.some(item => payload.warehouseIds.includes(item.warehouseId) && item.referenced)) {
      return Promise.reject(new Error('所选仓库中存在已有库存余额或出入库记录的数据'));
    }
    mockWarehouses = mockWarehouses.filter(item => !payload.warehouseIds.includes(item.warehouseId));
    return Promise.resolve(null);
  }
  return postResult<null, WarehouseBatchIdsPayload>('/warehouse/warehouses/batch/delete', payload);
}
