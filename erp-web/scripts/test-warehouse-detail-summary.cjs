const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

function loadTsModule(filePath, dependencies = {}) {
  const source = fs.readFileSync(filePath, 'utf8');
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
const qty = loadTsModule(path.join(root, 'src/shared/utils/qty.ts'));
const { summarizeWarehouseDetailQuantities } = loadTsModule(
  path.join(root, 'src/shared/utils/warehouse-detail-summary.ts'),
  { '@/shared/utils/qty': qty },
);

const discrete = summarizeWarehouseDetailQuantities([
  { value: 2.5, unitName: '箱', quantityPrecision: 1 },
  { value: 3, unitName: '件', quantityPrecision: 0 },
  { value: 4, unitName: '套', quantityPrecision: 0 },
  { value: 5, unitName: '本', quantityPrecision: 0 },
  { value: 6, unitName: '卡', quantityPrecision: 0 },
]);
assert.equal(discrete.text, '20.5 件');
assert.match(discrete.fullText, /^20.5 件（原始：/);
assert.match(discrete.fullText, /2.5 箱/);
assert.match(discrete.fullText, /3 件/);
assert.match(discrete.fullText, /4 套/);
assert.match(discrete.fullText, /5 本/);
assert.match(discrete.fullText, /6 卡/);

const kilograms = summarizeWarehouseDetailQuantities([
  { value: 1.25, unitName: 'kg', quantityPrecision: 2 },
  { value: 2.75, unitName: 'KG', quantityPrecision: 2 },
]);
assert.equal(kilograms.text, '4.00 kg');

const mixedWeight = summarizeWarehouseDetailQuantities([
  { value: 500, unitName: 'g', quantityPrecision: 0 },
  { value: 1.5, unitName: 'kg', quantityPrecision: 2 },
]);
assert.equal(mixedWeight.groupCount, 2);
assert.match(mixedWeight.fullText, /500 g/);
assert.match(mixedWeight.fullText, /1.50 kg/);

const guardedUnits = summarizeWarehouseDetailQuantities([
  { value: 2, unitName: '米', quantityPrecision: 0 },
  { value: 1, unitName: '箱', quantityPrecision: 0 },
  { value: 7, unitName: '未知单位', quantityPrecision: 0 },
]);
assert.equal(guardedUnits.groupCount, 3);
assert.match(guardedUnits.fullText, /2 米/);
assert.match(guardedUnits.fullText, /1 件/);
assert.match(guardedUnits.fullText, /7 未知单位/);

const signed = summarizeWarehouseDetailQuantities([
  { value: -3, unitName: '箱', quantityPrecision: 0 },
  { value: 1, unitName: '套', quantityPrecision: 0 },
], { signed: true });
assert.equal(signed.text, '-2 件');
assert.equal(summarizeWarehouseDetailQuantities([{ value: null, unitName: '件', quantityPrecision: 0 }]).text, '-');

console.log('UNIT_SUMMARY_OK');