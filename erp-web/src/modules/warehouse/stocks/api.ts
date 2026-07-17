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
  { stockId: '1931000000000000001', warehouseId: '1930000000000000003', warehouseCode: 'WH003', warehouseName: '华北中心仓', productId: '1920000000000000007', productCode: 'P000007', productName: '每日坚果混合装', unitName: '盒', stockQty: 31, lockedQty: 0, availableQty: 31, safetyStockQty: 6, version: 0, updateTime: '2026-07-01 09:12:00' },
  { stockId: '1931000000000000002', warehouseId: '1930000000000000001', warehouseCode: 'WH001', warehouseName: '华东中心仓', productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', unitName: '盒', stockQty: 7, lockedQty: 0, availableQty: 7, safetyStockQty: 8, version: 0, updateTime: '2026-07-01 10:05:00' },
  { stockId: '1940000000000000001', warehouseId: '1930000000000000001', warehouseCode: 'WH001', warehouseName: '华东中心仓', productId: '1920000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', stockQty: 86, lockedQty: 18, availableQty: 68, safetyStockQty: 12, version: 0, updateTime: '2026-06-14 09:20:00' },
  { stockId: '1940000000000000002', warehouseId: '1930000000000000001', warehouseCode: 'WH001', warehouseName: '华东中心仓', productId: '1920000000000000021', productCode: 'P000021', productName: '中性签字笔', unitName: '盒', stockQty: 42, lockedQty: 12, availableQty: 30, safetyStockQty: 30, version: 0, updateTime: '2026-06-14 09:15:00' },
  { stockId: '1940000000000000003', warehouseId: '1930000000000000002', warehouseCode: 'WH002', warehouseName: '华南中心仓', productId: '1920000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', stockQty: 54, lockedQty: 0, availableQty: 54, safetyStockQty: 12, version: 0, updateTime: '2026-06-14 08:50:00' },
  { stockId: '1940000000000000004', warehouseId: '1930000000000000002', warehouseCode: 'WH002', warehouseName: '华南中心仓', productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', unitName: '箱', stockQty: 13, lockedQty: 3, availableQty: 10, safetyStockQty: 15, version: 0, updateTime: '2026-06-14 08:45:00' },
  { stockId: '1940000000000000005', warehouseId: '1930000000000000002', warehouseCode: 'WH002', warehouseName: '华南中心仓', productId: '1920000000000000027', productCode: 'P000027', productName: '热敏标签纸', unitName: '卷', stockQty: 0, lockedQty: 0, availableQty: 0, safetyStockQty: 40, version: 0, updateTime: '2026-06-14 08:40:00' },
  { stockId: '1940000000000000006', warehouseId: '1930000000000000003', warehouseCode: 'WH003', warehouseName: '华北中心仓', productId: '1920000000000000033', productCode: 'P000033', productName: '浓缩洗衣液', unitName: '瓶', stockQty: 9, lockedQty: 0, availableQty: 9, safetyStockQty: 10, version: 0, updateTime: '2026-06-13 17:25:00' },
  { stockId: '1940000000000000007', warehouseId: '1930000000000000004', warehouseCode: 'WH004', warehouseName: '西南中心仓', productId: '1920000000000000034', productCode: 'P000034', productName: '厨房清洁湿巾', unitName: '包', stockQty: 48, lockedQty: 16, availableQty: 32, safetyStockQty: 18, version: 0, updateTime: '2026-06-13 16:48:00' },
  { stockId: '1940000000000000008', warehouseId: '1930000000000000004', warehouseCode: 'WH004', warehouseName: '西南中心仓', productId: '1920000000000000037', productCode: 'P000037', productName: '加厚垃圾袋', unitName: '卷', stockQty: 25, lockedQty: 5, availableQty: 20, safetyStockQty: 25, version: 0, updateTime: '2026-06-13 16:45:00' },
  { stockId: '1940000000000000009', warehouseId: '1930000000000000005', warehouseCode: 'WH005', warehouseName: '武汉中转仓', productId: '1920000000000000008', productCode: 'P000008', productName: '海盐苏打饼干', unitName: '箱', stockQty: 19, lockedQty: 0, availableQty: 19, safetyStockQty: 5, version: 0, updateTime: '2026-06-13 15:20:00' },
  { stockId: '1940000000000000010', warehouseId: '1930000000000000005', warehouseCode: 'WH005', warehouseName: '武汉中转仓', productId: '1920000000000000038', productCode: 'P000038', productName: '无痕粘钩', unitName: '卡', stockQty: 11, lockedQty: 1, availableQty: 10, safetyStockQty: 15, version: 0, updateTime: '2026-06-13 15:18:00' },
  { stockId: '1940000000000000011', warehouseId: '1930000000000000006', warehouseCode: 'WH006', warehouseName: '西安中转仓', productId: '1920000000000000022', productCode: 'P000022', productName: '彩色便利贴', unitName: '本', stockQty: 63, lockedQty: 0, availableQty: 63, safetyStockQty: 20, version: 0, updateTime: '2026-06-13 14:35:00' },
  { stockId: '1940000000000000012', warehouseId: '1930000000000000007', warehouseCode: 'WH007', warehouseName: '杭州电商仓', productId: '1920000000000000044', productCode: 'P000044', productName: '无线办公鼠标', unitName: '个', stockQty: 8, lockedQty: 8, availableQty: 0, safetyStockQty: 8, version: 0, updateTime: '2026-06-13 13:10:00' },
  { stockId: '1940000000000000013', warehouseId: '1930000000000000008', warehouseCode: 'WH008', warehouseName: '南京备货仓', productId: '1920000000000000043', productCode: 'P000043', productName: 'USB-C扩展坞', unitName: '个', stockQty: 4, lockedQty: 4, availableQty: 0, safetyStockQty: 4, version: 0, updateTime: '2026-06-13 11:55:00' },
  { stockId: '1940000000000000014', warehouseId: '1930000000000000003', warehouseCode: 'WH003', warehouseName: '华北中心仓', productId: '1920000000000000008', productCode: 'P000008', productName: '海盐苏打饼干', unitName: '箱', stockQty: 24, lockedQty: 4, availableQty: 20, safetyStockQty: 5, version: 0, updateTime: '2026-07-01 09:15:00' },
  { stockId: '1940000000000000015', warehouseId: '1930000000000000006', warehouseCode: 'WH006', warehouseName: '西安中转仓', productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', unitName: '箱', stockQty: 18, lockedQty: 4, availableQty: 14, safetyStockQty: 15, version: 0, updateTime: '2026-07-02 14:20:00' },
  { stockId: '1940000000000000016', warehouseId: '1930000000000000008', warehouseCode: 'WH008', warehouseName: '南京备货仓', productId: '1920000000000000012', productCode: 'P000012', productName: '东北长粒香大米', unitName: 'kg', stockQty: 25.5, lockedQty: 0, availableQty: 25.5, safetyStockQty: 20.5, version: 0, updateTime: '2026-07-03 10:30:00' },
  { stockId: '1940000000000000017', warehouseId: '1930000000000000011', warehouseCode: 'WH011', warehouseName: '郑州临时仓', productId: '1920000000000000034', productCode: 'P000034', productName: '厨房清洁湿巾', unitName: '包', stockQty: 27, lockedQty: 0, availableQty: 27, safetyStockQty: 18, version: 0, updateTime: '2026-07-04 11:10:00' },
  { stockId: '1940000000000000018', warehouseId: '1930000000000000012', warehouseCode: 'WH012', warehouseName: '合肥样品仓', productId: '1920000000000000037', productCode: 'P000037', productName: '加厚垃圾袋', unitName: '卷', stockQty: 16, lockedQty: 2, availableQty: 14, safetyStockQty: 25, version: 0, updateTime: '2026-07-05 16:45:00' },
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
      ? { ...item, stockQty: afterQty, lockedQty: afterLockedQty, availableQty: afterQty - afterLockedQty, version: item.version + 1, updateTime: timestamp }
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
      version: 0,
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
    version: normalizeFiniteNumber(item.version, 'version'),
    updateTime: String(item.updateTime),
  };
}

function normalizeSummary(summary: WarehouseStockSummary): WarehouseStockSummary {
  return {
    warehouseCount: normalizeFiniteNumber(summary.warehouseCount, 'warehouseCount'),
    productCount: normalizeFiniteNumber(summary.productCount, 'productCount'),
    lowStockCount: normalizeFiniteNumber(summary.lowStockCount, 'lowStockCount'),
    noAvailableCount: normalizeFiniteNumber(summary.noAvailableCount, 'noAvailableCount'),
    lockedCount: normalizeFiniteNumber(summary.lockedCount, 'lockedCount'),
  };
}

function normalizeStockPage(page: WarehouseStockPage): WarehouseStockPage {
  const records = page.records.map(normalizeStock);
  const total = page.total === null || page.total === undefined ? null : Number(page.total);
  return {
    records,
    total: total === null ? null : Number.isFinite(total) ? total : null,
    ...(typeof page.hasNext === 'boolean' ? { hasNext: page.hasNext } : {}),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
    summary: normalizeSummary(buildSummary(records)),
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
    warehouseCount: new Set(records.map(item => item.warehouseId)).size,
    productCount: new Set(records.map(item => item.productId)).size,
    lowStockCount: records.filter(item => item.availableQty > 0 && item.availableQty <= item.safetyStockQty).length,
    noAvailableCount: records.filter(item => item.availableQty === 0).length,
    lockedCount: records.filter(item => item.lockedQty > 0).length,
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
  const start = (params.pageNum - 1) * params.pageSize;
  return normalizeStockPage({
    records: filtered.slice(start, start + params.pageSize),
    total: filtered.length,
    hasNext: start + params.pageSize < filtered.length,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
    summary: buildSummary(filtered.slice(start, start + params.pageSize)),
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
