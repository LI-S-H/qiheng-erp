// 初始化种子的静态契约检查；不连接数据库，也不执行任何 SQL。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, 'docs/database/sql', name), 'utf8');
const purchase = read('004_mvp_purchase.sql');
const warehouse = read('003_mvp_warehouse.sql');

// 种子金额和雪花 ID 保持字符串/BigInt，不能经过 Number 丢失精度。
function splitRow(row) {
  const values = [];
  let quoted = false;
  let start = 0;
  for (let i = 0; i < row.length; i++) {
    if (row[i] === "'") {
      if (quoted && row[i + 1] === "'") { i++; continue; }
      quoted = !quoted;
    } else if (row[i] === ',' && !quoted) {
      values.push(row.slice(start, i).trim());
      start = i + 1;
    }
  }
  assert.equal(quoted, false, '种子字符串引号必须闭合');
  values.push(row.slice(start).trim());
  return values.map(value => value.startsWith("'") ? value.slice(1, -1).replace(/''/g, "'") : value);
}

function rows(sql, table) {
  const result = [];
  const insert = new RegExp(`INSERT(?: IGNORE)? INTO ${table}\\s*\\(([^)]+)\\)\\s*VALUES\\s*([\\s\\S]*?)(?:ON DUPLICATE KEY UPDATE|;)`, 'g');
  for (const match of sql.matchAll(insert)) {
    const fields = match[1].split(',').map(field => field.trim());
    for (const line of match[2].split(/\r?\n/).map(value => value.trim()).filter(value => value.startsWith('('))) {
      const values = splitRow(line.replace(/^\(/, '').replace(/\),?$/, ''));
      assert.equal(values.length, fields.length, `${table} 种子列数不匹配`);
      result.push(Object.fromEntries(fields.map((field, index) => [field, values[index]])));
    }
  }
  assert.ok(result.length > 0, `${table} 必须解析到种子`);
  return result;
}

const orders = rows(purchase, 'purchase_order');
const items = rows(purchase, 'purchase_order_item');
const relations = rows(purchase, 'supplier_product');
const relationById = new Map(relations.map(row => [row.id, row]));
for (const order of orders) {
  const lines = items.filter(item => item.purchase_order_id === order.id);
  assert.ok(lines.length > 0, `采购单 ${order.purchase_no} 缺少明细`);
  assert.equal(lines.reduce((sum, item) => sum + BigInt(item.total_amount), 0n), BigInt(order.total_amount), `${order.purchase_no} 主明细金额不一致`);
  for (const line of lines) {
    assert.equal(BigInt(line.quantity) * BigInt(line.unit_price) / 100n, BigInt(line.total_amount), `${order.purchase_no} 数量、单价放大口径不一致`);
    const relation = relationById.get(line.supplier_product_id);
    assert.ok(relation && relation.supplier_id === order.supplier_id && relation.product_id === line.product_id, `${order.purchase_no} 供货关系归属错误`);
  }
}

const inbound = rows(warehouse, 'inbound_bill').filter(row => row.inbound_type === 'PURCHASE_IN');
const inboundItems = rows(warehouse, 'inbound_bill_item');
for (const bill of inbound) {
  const order = orders.find(row => row.purchase_no === bill.source_no);
  assert.ok(order, `入库种子 ${bill.inbound_no} 缺少采购来源`);
  for (const batch of inboundItems.filter(row => row.inbound_bill_id === bill.id)) {
    const matched = items.filter(row => row.purchase_order_id === order.id && row.product_id === batch.product_id);
    assert.equal(matched.length, 1, `入库明细 ${batch.id} 必须唯一对应采购明细`);
    assert.ok(BigInt(matched[0].unit_price) > 0n, `入库明细 ${batch.id} 必须有有效单价来源`);
  }
}

// 每条来源、单价、交期修复都限定明确种子 ID，禁止扩展到全库历史记录。
const snapshots = [...purchase.matchAll(/UPDATE (?:inbound_bill_item|inbound_bill|stock_bill_item)\b[\s\S]*?;/g)].map(match => match[0]);
assert.equal(snapshots.length, 5, '采购初始化应只有五条定向种子补全语句');
for (const statement of snapshots) {
  assert.match(statement, /(?:inbound_bill|stock_bill)\.id IN \(/, '种子修复必须限制主单 ID');
  const scoped = statement.match(/(?:inbound_bill|stock_bill)\.id IN \(([^)]+)\)/)[1].split(',').map(id => id.trim());
  const expected = statement.includes('UPDATE stock_bill_item') ? ['1990000000000000001'] : inbound.map(row => row.id);
  assert.deepEqual([...scoped].sort(), [...expected].sort(), '种子 ID 范围必须与仓库初始化数据一致');
}
const price = snapshots.find(statement => statement.includes('SET inbound_bill_item.unit_price'));
assert.ok(price, '必须回填入库单价快照');
assert.match(price, /purchase_order_item\.id = inbound_bill_item\.source_item_id/, '单价必须按来源明细 ID 回填');
assert.match(price, /inbound_bill_item\.unit_price = 0/, '不能覆盖已有入库单价快照');
assert.doesNotMatch(purchase, /UPDATE\s+purchase_order(?:_item)?\s+SET[\s\S]*?\*\s*100\s*\)/i, '初始化禁止再次放大已有金额或数量');
assert.match(purchase, /采购种子缺少有效来源、单价或预计到货快照/, '初始化必须校验补全后的快照');
assert.equal(read('013_backfill_development_supplier_scores.sql').split(/\r?\n/).filter(line => line.trim() && !line.trim().startsWith('--')).length, 0, '弃用评分脚本不得包含可执行语句');
console.log(`PURCHASE_SEED_CONTRACT_OK: ${orders.length} 张采购单、${items.length} 条明细、${inbound.length} 张采购入库种子，定向快照与金额口径正确`);
