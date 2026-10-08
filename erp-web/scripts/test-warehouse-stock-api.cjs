const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

function loadTsModule(filePath, useMockApi, dependencies = {}) {
  const source = fs.readFileSync(filePath, 'utf8').replace(
    "const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';",
    `const useMockApi = ${useMockApi};`,
  );
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    fileName: filePath,
  }).outputText;
  const module = { exports: {} };
  const localRequire = id => dependencies[id] || require(id);
  new Function('exports', 'require', 'module', output)(module.exports, localRequire, module);
  return module.exports;
}

const root = path.resolve(__dirname, '..');
const normalizers = loadTsModule(path.join(root, 'src/shared/utils/api-normalizers.ts'), false);
const quantity = loadTsModule(path.join(root, 'src/shared/utils/qty.ts'), false);
const pageFixture = safetyStockQty => ({
  records: [{
    stockId: '1', warehouseId: '2', warehouseCode: 'WH001', warehouseName: '测试仓',
    productId: '3', productCode: 'P000001', productName: '测试产品', unitName: '件',
    quantityPrecision: 0, stockQty: 10, lockedQty: 0, availableQty: 10,
    safetyStockQty, version: 0, updateTime: '2026-10-07 10:00:00',
  }],
  total: 1, pageNum: 1, pageSize: 10,
  summary: { warehouseCount: 1, productCount: 1, lowStockCount: 0, noAvailableCount: 0, lockedCount: 0 },
});

async function assertSafetyStockIsRequired(value) {
  const api = loadTsModule(path.join(root, 'src/modules/warehouse/stocks/api.ts'), false, {
    '@/api/http': { getResult: () => Promise.resolve(pageFixture(value)) },
    '@/shared/utils/api-normalizers': normalizers,
    '@/shared/utils/qty': quantity,
  });
  await assert.rejects(
    api.listWarehouseStocks({ pageNum: 1, pageSize: 10 }),
    /safetyStockQty/,
  );
}

async function main() {
  for (const invalid of [null, undefined, '', '   ', true, false]) {
    await assertSafetyStockIsRequired(invalid);
  }

  const api = loadTsModule(path.join(root, 'src/modules/warehouse/stocks/api.ts'), false, {
    '@/api/http': { getResult: () => Promise.resolve(pageFixture('5.50')) },
    '@/shared/utils/api-normalizers': normalizers,
    '@/shared/utils/qty': quantity,
  });
  const normalized = await api.listWarehouseStocks({ pageNum: 1, pageSize: 10 });
  assert.equal(normalized.records[0].safetyStockQty, 5.5);

  const mockApi = loadTsModule(path.join(root, 'src/modules/warehouse/stocks/api.ts'), true, {
    '@/api/http': { getResult: () => Promise.reject(new Error('Mock 查询不应请求网络')) },
    '@/shared/utils/api-normalizers': normalizers,
    '@/shared/utils/qty': quantity,
  });
  const riskPage = await mockApi.listWarehouseStocks({ pageNum: 1, pageSize: 100, riskOnly: true });
  const riskCodes = riskPage.records.map(item => item.productCode);
  assert.ok(riskCodes.includes('P000021'), '可用库存恰好等于安全库存时应命中风险条件');
  assert.ok(riskCodes.includes('P000027'), '零可用库存时应命中风险条件');
  assert.ok(!riskCodes.includes('P000001'), '可用库存高于安全库存时不应命中风险条件');

  console.log('UNIT_STOCK_API_OK: 安全库存空值校验、数字字符串转换和风险边界通过');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
