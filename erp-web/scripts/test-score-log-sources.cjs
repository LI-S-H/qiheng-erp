const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('node:assert/strict');
const ts = require('typescript');

// 从实际源码 AST 提取适配方法，不复制实现，避免测试与生产校验脱节。
function extractFunctions(file, names) {
  const source = fs.readFileSync(file, 'utf8');
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
  const found = ast.statements.filter(statement => ts.isFunctionDeclaration(statement) && names.includes(statement.name?.text));
  assert.equal(found.length, names.length, `未找到全部被测方法：${file}`);
  return found.map(statement => statement.getText(ast).replace(/^export\s+/, '')).join('\n');
}

const apiFile = path.resolve(__dirname, '../src/modules/purchase/api.ts');
const normalizerFile = path.resolve(__dirname, '../src/shared/utils/api-normalizers.ts');
const source = extractFunctions(normalizerFile, ['normalizeStringId', 'normalizeNullableStringId', 'normalizeFiniteNumber'])
  + '\n' + extractFunctions(apiFile, ['normalizeNullableFiniteNumber', 'normalizeScoreChangeLog']);
const sandbox = {};
vm.runInNewContext(ts.transpile(source, { target: ts.ScriptTarget.ES2022 }), sandbox);
const normalize = sandbox.normalizeScoreChangeLog;
const base = {
  scoreChangeLogId: '2017000000000000001', supplierId: '2010000000000000001', supplierProductId: null,
  metricType: 'QUALITY', metricScoreBefore: 90, metricScoreAfter: 90,
  productRecommendScoreBefore: null, productRecommendScoreAfter: null,
  supplierOverallScoreBefore: 88, supplierOverallScoreAfter: 89,
  triggerType: 'INBOUND_TRIGGER', batchNo: 'SC2026100600001', ruleVersion: 'RULE',
  relatedSources: [{ businessType: 'PURCHASE_ORDER', businessId: '9007199254740993', businessNo: null }],
  operatorType: 'SYSTEM', operatorId: null, operatorName: '完全入库重算', reason: '衍生分校正', createTime: '2026-10-06 10:00:00',
};
let checks = 0;
const valid = normalize(structuredClone(base));
assert.equal(valid.relatedSources[0].businessId, '9007199254740993'); checks++;
assert.equal(valid.relatedSources[0].businessNo, null); checks++;
assert.equal(normalize({ ...base, relatedSources: [] }).relatedSources.length, 0); checks++;
assert.equal(normalize({ ...base, operatorType: 'USER', operatorId: '9007199254740995', operatorName: '采购员' }).operatorId, '9007199254740995'); checks++;
for (const businessType of ['PURCHASE_ORDER', 'SUPPLIER_PRODUCT', 'PRODUCT', 'SUPPLIER']) {
  assert.equal(normalize({ ...base, relatedSources: [{ ...base.relatedSources[0], businessType }] }).relatedSources[0].businessType, businessType); checks++;
}
const manySources = Array.from({ length: 12 }, (_, index) => ({ ...base.relatedSources[0], businessId: (9007199254740993n + BigInt(index)).toString() }));
const manyResult = normalize({ ...base, relatedSources: manySources });
assert.equal(manyResult.relatedSources.length, 12); checks++;
assert.equal(manyResult.relatedSources[11].businessId, '9007199254741004'); checks++;
for (const relatedSources of [undefined, null, '', {}, [null], [[]], [{ ...base.relatedSources[0], businessType: 'INBOUND' }],
  [{ ...base.relatedSources[0], businessId: 123 }], [{ ...base.relatedSources[0], businessId: '0' }],
  [{ ...base.relatedSources[0], businessId: '-1' }], [{ ...base.relatedSources[0], businessNo: undefined }],
  [{ ...base.relatedSources[0], businessNo: 123 }], [{ ...base.relatedSources[0], businessNo: 'A'.repeat(65) }]]) {
  assert.throws(() => normalize({ ...base, relatedSources })); checks++;
}
for (const patch of [{ operatorName: '' }, { operatorName: ' ' }, { operatorName: '人'.repeat(101) },
  { operatorType: 'USER', operatorId: null }, { operatorType: 'USER', operatorId: 123 },
  { operatorType: 'SYSTEM', operatorId: '123' }]) {
  assert.throws(() => normalize({ ...base, ...patch })); checks++;
}
const dialogSource = fs.readFileSync(path.resolve(__dirname, '../src/modules/purchase/components/SupplierScoreChangeLogDialog.vue'), 'utf8');
const dialogScript = dialogSource.match(/<script setup[^>]*>([\s\S]*?)<\/script>/)?.[1];
assert.ok(dialogScript, '未找到评分日志组件脚本');
const dialogAst = ts.createSourceFile('score-log-dialog.ts', dialogScript, ts.ScriptTarget.Latest, true);
const derivedFunction = dialogAst.statements.find(statement => ts.isFunctionDeclaration(statement) && statement.name?.text === 'isDerivedCorrection');
assert.ok(derivedFunction, '未找到衍生分展示判断');
vm.runInNewContext(ts.transpile(derivedFunction.getText(dialogAst), { target: ts.ScriptTarget.ES2022 }), sandbox);
assert.equal(sandbox.isDerivedCorrection(base), true); checks++;
assert.equal(sandbox.isDerivedCorrection({ ...base, metricScoreAfter: 91 }), false); checks++;
assert.equal(sandbox.isDerivedCorrection({ ...base, metricScoreBefore: null, metricScoreAfter: null }), true); checks++;
assert.equal(sandbox.isDerivedCorrection({ ...base, supplierOverallScoreAfter: 88 }), false); checks++;
console.log(`SCORE_LOG_SOURCES_CONTRACT_OK checks=${checks}`);
