import { getResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeStringId } from '@/shared/utils/api-normalizers';
import type {
  WarehouseStockListItem,
  WarehouseStockPage,
  WarehouseStockQuery,
  WarehouseStockSummary,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const stockSeed: WarehouseStockListItem[] = [
  { stockId: '1940000000000000001', warehouseId: '1930000000000000001', warehouseCode: 'WH001', warehouseName: '华东中心仓', productId: '1920000000000000001', productCode: 'P0001', productName: '经典原味苏打水', unitName: '箱', stockQty: 86, lockedQty: 18, availableQty: 68, safetyStockQty: 12, updateTime: '2026-06-14 09:20:00' },
  { stockId: '1940000000000000002', warehouseId: '1930000000000000001', warehouseCode: 'WH001', warehouseName: '华东中心仓', productId: '1920000000000000002', productCode: 'P0002', productName: '速溶黑咖啡', unitName: '盒', stockQty: 7, lockedQty: 2, availableQty: 5, safetyStockQty: 8, updateTime: '2026-06-14 09:18:00' },
  { stockId: '1940000000000000003', warehouseId: '1930000000000000001', warehouseCode: 'WH001', warehouseName: '华东中心仓', productId: '1920000000000000005', productCode: 'P0005', productName: '中性签字笔', unitName: '盒', stockQty: 42.5, lockedQty: 12, availableQty: 30.5, safetyStockQty: 30, updateTime: '2026-06-14 09:15:00' },
  { stockId: '1940000000000000004', warehouseId: '1930000000000000002', warehouseCode: 'WH002', warehouseName: '华南中心仓', productId: '1920000000000000001', productCode: 'P0001', productName: '经典原味苏打水', unitName: '箱', stockQty: 54, lockedQty: 0, availableQty: 54, safetyStockQty: 12, updateTime: '2026-06-14 08:50:00' },
  { stockId: '1940000000000000005', warehouseId: '1930000000000000002', warehouseCode: 'WH002', warehouseName: '华南中心仓', productId: '1920000000000000007', productCode: 'P0007', productName: 'A4复印纸', unitName: '箱', stockQty: 13, lockedQty: 3, availableQty: 10, safetyStockQty: 15, updateTime: '2026-06-14 08:45:00' },
  { stockId: '1940000000000000006', warehouseId: '1930000000000000002', warehouseCode: 'WH002', warehouseName: '华南中心仓', productId: '1920000000000000008', productCode: 'P0008', productName: '热敏标签纸', unitName: '卷', stockQty: 0, lockedQty: 0, availableQty: 0, safetyStockQty: 40, updateTime: '2026-06-14 08:40:00' },
  { stockId: '1940000000000000007', warehouseId: '1930000000000000003', warehouseCode: 'WH003', warehouseName: '华北中心仓', productId: '1920000000000000003', productCode: 'P0003', productName: '每日坚果混合装', unitName: '盒', stockQty: 31, lockedQty: 6, availableQty: 25, safetyStockQty: 6, updateTime: '2026-06-13 17:30:00' },
  { stockId: '1940000000000000008', warehouseId: '1930000000000000003', warehouseCode: 'WH003', warehouseName: '华北中心仓', productId: '1920000000000000009', productCode: 'P0009', productName: '浓缩洗衣液', unitName: '瓶', stockQty: 9, lockedQty: 0, availableQty: 9, safetyStockQty: 10, updateTime: '2026-06-13 17:25:00' },
  { stockId: '1940000000000000009', warehouseId: '1930000000000000004', warehouseCode: 'WH004', warehouseName: '西南中心仓', productId: '1920000000000000010', productCode: 'P0010', productName: '厨房清洁湿巾', unitName: '包', stockQty: 48, lockedQty: 16, availableQty: 32, safetyStockQty: 18, updateTime: '2026-06-13 16:48:00' },
  { stockId: '1940000000000000010', warehouseId: '1930000000000000004', warehouseCode: 'WH004', warehouseName: '西南中心仓', productId: '1920000000000000011', productCode: 'P0011', productName: '加厚垃圾袋', unitName: '卷', stockQty: 25, lockedQty: 5, availableQty: 20, safetyStockQty: 25, updateTime: '2026-06-13 16:45:00' },
  { stockId: '1940000000000000011', warehouseId: '1930000000000000005', warehouseCode: 'WH005', warehouseName: '武汉中转仓', productId: '1920000000000000004', productCode: 'P0004', productName: '海盐苏打饼干', unitName: '箱', stockQty: 19, lockedQty: 0, availableQty: 19, safetyStockQty: 5, updateTime: '2026-06-13 15:20:00' },
  { stockId: '1940000000000000012', warehouseId: '1930000000000000005', warehouseCode: 'WH005', warehouseName: '武汉中转仓', productId: '1920000000000000012', productCode: 'P0012', productName: '无痕粘钩', unitName: '卡', stockQty: 11, lockedQty: 1, availableQty: 10, safetyStockQty: 15, updateTime: '2026-06-13 15:18:00' },
  { stockId: '1940000000000000013', warehouseId: '1930000000000000006', warehouseCode: 'WH006', warehouseName: '西安中转仓', productId: '1920000000000000006', productCode: 'P0006', productName: '彩色便利贴', unitName: '本', stockQty: 63, lockedQty: 0, availableQty: 63, safetyStockQty: 20, updateTime: '2026-06-13 14:35:00' },
  { stockId: '1940000000000000014', warehouseId: '1930000000000000007', warehouseCode: 'WH007', warehouseName: '杭州电商仓', productId: '1920000000000000014', productCode: 'P0014', productName: '无线办公鼠标', unitName: '个', stockQty: 8, lockedQty: 8, availableQty: 0, safetyStockQty: 8, updateTime: '2026-06-13 13:10:00' },
  { stockId: '1940000000000000015', warehouseId: '1930000000000000008', warehouseCode: 'WH008', warehouseName: '南京备货仓', productId: '1920000000000000013', productCode: 'P0013', productName: 'USB-C扩展坞', unitName: '个', stockQty: 17, lockedQty: 4, availableQty: 13, safetyStockQty: 4, updateTime: '2026-06-13 11:55:00' },
];

let mockStocks = stockSeed.map(item => ({ ...item }));

interface MockStockChange {
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  safetyStockQty: number;
  changeQty: number;
  lockedChangeQty?: number;
}

export function getMockWarehouseStock(warehouseId: string, productId: string) {
  const stock = mockStocks.find(item => item.warehouseId === warehouseId && item.productId === productId);
  return stock ? { ...stock } : null;
}

export function applyMockWarehouseStockChange(change: MockStockChange) {
  const timestamp = new Date().toISOString().slice(0, 19).replace('T', ' ');
  const current = mockStocks.find(item => item.warehouseId === change.warehouseId && item.productId === change.productId);
  const beforeQty = current?.stockQty || 0;
  const afterQty = beforeQty + change.changeQty;
  const lockedQty = current?.lockedQty || 0;
  const afterLockedQty = lockedQty + (change.lockedChangeQty || 0);
  if (afterQty < 0) throw new Error(`产品 ${change.productCode} 库存不足，无法确认出库`);
  if (afterLockedQty < 0) throw new Error(`产品 ${change.productCode} 的销售锁定库存不足`);
  if (afterQty < afterLockedQty) throw new Error(`产品 ${change.productCode} 调整后库存不能低于已锁定库存`);
  if (current) {
    mockStocks = mockStocks.map(item => item.stockId === current.stockId
      ? { ...item, stockQty: afterQty, lockedQty: afterLockedQty, availableQty: afterQty - afterLockedQty, updateTime: timestamp }
      : item);
  } else {
    if (change.changeQty < 0) throw new Error(`产品 ${change.productCode} 在当前仓库没有可出库库存`);
    mockStocks = [...mockStocks, {
      stockId: String(Date.now()),
      warehouseId: change.warehouseId,
      warehouseCode: change.warehouseCode,
      warehouseName: change.warehouseName,
      productId: change.productId,
      productCode: change.productCode,
      productName: change.productName,
      unitName: change.unitName,
      stockQty: afterQty,
      lockedQty: 0,
      availableQty: afterQty,
      safetyStockQty: change.safetyStockQty,
      updateTime: timestamp,
    }];
  }
  return { beforeQty, afterQty };
}

function normalizeStock(item: WarehouseStockListItem): WarehouseStockListItem {
  const stockQty = normalizeFiniteNumber(item.stockQty, 'stockQty');
  const lockedQty = normalizeFiniteNumber(item.lockedQty, 'lockedQty');
  const availableQty = normalizeFiniteNumber(item.availableQty, 'availableQty');
  const safetyStockQty = normalizeFiniteNumber(item.safetyStockQty, 'safetyStockQty');
  if (stockQty < 0 || lockedQty < 0 || availableQty < 0 || safetyStockQty < 0 || lockedQty > stockQty) {
    throw new Error('库存数量字段不符合非负数和锁定量约束');
  }
  if (Math.abs(availableQty - (stockQty - lockedQty)) > 0.0001) {
    throw new Error('接口字段 availableQty 必须等于 stockQty - lockedQty');
  }
  return {
    ...item,
    stockId: normalizeStringId(item.stockId, 'stockId'),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    productId: normalizeStringId(item.productId, 'productId'),
    warehouseCode: String(item.warehouseCode),
    warehouseName: String(item.warehouseName),
    productCode: String(item.productCode),
    productName: String(item.productName),
    unitName: String(item.unitName),
    stockQty,
    lockedQty,
    availableQty,
    safetyStockQty,
    updateTime: String(item.updateTime),
  };
}

function normalizeSummary(summary: WarehouseStockSummary): WarehouseStockSummary {
  return {
    stockRecordCount: normalizeFiniteNumber(summary.stockRecordCount, 'stockRecordCount'),
    warehouseCount: normalizeFiniteNumber(summary.warehouseCount, 'warehouseCount'),
    productCount: normalizeFiniteNumber(summary.productCount, 'productCount'),
    lowStockCount: normalizeFiniteNumber(summary.lowStockCount, 'lowStockCount'),
  };
}

function normalizeStockPage(page: WarehouseStockPage): WarehouseStockPage {
  return {
    records: page.records.map(normalizeStock),
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
    summary: normalizeSummary(page.summary),
  };
}

function matchesInventoryHealth(item: WarehouseStockListItem, health: WarehouseStockQuery['inventoryHealth']) {
  if (!health || health === 'all') return true;
  if (health === 'NORMAL') return item.availableQty > item.safetyStockQty;
  if (health === 'LOW_STOCK') return item.availableQty > 0 && item.availableQty <= item.safetyStockQty;
  if (health === 'NO_AVAILABLE') return item.stockQty > 0 && item.availableQty === 0;
  return item.stockQty === 0;
}

function matchesReservationState(item: WarehouseStockListItem, state: WarehouseStockQuery['reservationState']) {
  if (!state || state === 'all') return true;
  if (state === 'UNLOCKED') return item.lockedQty === 0;
  if (state === 'PARTIALLY_LOCKED') return item.lockedQty > 0 && item.lockedQty < item.stockQty;
  return item.stockQty > 0 && item.lockedQty === item.stockQty;
}

function buildSummary(records: WarehouseStockListItem[]): WarehouseStockSummary {
  return {
    stockRecordCount: records.length,
    warehouseCount: new Set(records.map(item => item.warehouseId)).size,
    productCount: new Set(records.map(item => item.productId)).size,
    lowStockCount: records.filter(item => item.availableQty > 0 && item.availableQty <= item.safetyStockQty).length,
  };
}

function filterStocks(params: WarehouseStockQuery): WarehouseStockPage {
  const productCode = params.productCode?.trim().toLocaleLowerCase();
  const productName = params.productName?.trim().toLocaleLowerCase();
  let filtered = mockStocks.filter(item => {
    if (params.warehouseId && params.warehouseId !== 'all' && item.warehouseId !== params.warehouseId) return false;
    if (productCode && !item.productCode.toLocaleLowerCase().includes(productCode)) return false;
    if (productName && !item.productName.toLocaleLowerCase().includes(productName)) return false;
    return matchesInventoryHealth(item, params.inventoryHealth)
      && matchesReservationState(item, params.reservationState);
  });
  filtered = filtered.sort((a, b) => a.warehouseCode.localeCompare(b.warehouseCode) || a.productCode.localeCompare(b.productCode));
  const summary = buildSummary(filtered);
  const start = (params.pageNum - 1) * params.pageSize;
  return normalizeStockPage({
    records: filtered.slice(start, start + params.pageSize),
    total: filtered.length,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
    summary,
  });
}

export function listWarehouseStocks(params: WarehouseStockQuery) {
  if (useMockApi) return Promise.resolve(filterStocks(params));
  const { warehouseId, productCode, productName, inventoryHealth, reservationState, ...rest } = params;
  return getResult<WarehouseStockPage>('/warehouse/stocks', {
    ...rest,
    ...(warehouseId && warehouseId !== 'all' ? { warehouseId } : {}),
    ...(productCode?.trim() ? { productCode: productCode.trim() } : {}),
    ...(productName?.trim() ? { productName: productName.trim() } : {}),
    ...(inventoryHealth && inventoryHealth !== 'all' ? { inventoryHealth } : {}),
    ...(reservationState && reservationState !== 'all' ? { reservationState } : {}),
  }).then(normalizeStockPage);
}
