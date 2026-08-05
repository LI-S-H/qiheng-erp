const fs = require('node:fs');
const path = require('node:path');
const SwaggerParser = require('@apidevtools/swagger-parser');

const projectRoot = path.resolve(__dirname, '..', '..');
const readProjectFile = (...segments) => fs.readFileSync(path.join(projectRoot, ...segments), 'utf8');

const openapiPath = path.join(projectRoot, 'docs', 'api', 'erp-openapi.yaml');
const returnSchema = readProjectFile('docs', 'database', 'mvp-return-schema.md');
const returnSql = readProjectFile('docs', 'database', 'sql', '008_mvp_return.sql');
const purchaseSql = readProjectFile('docs', 'database', 'sql', '004_mvp_purchase.sql');
const salesSql = readProjectFile('docs', 'database', 'sql', '005_mvp_sales.sql');
const productSql = readProjectFile('docs', 'database', 'sql', '002_mvp_product.sql');
const warehouseSql = readProjectFile('docs', 'database', 'sql', '003_mvp_warehouse.sql');
const systemSql = readProjectFile('docs', 'database', 'sql', '001_mvp_system_permission.sql');
const purchaseApiSource = readProjectFile('erp-web', 'src', 'modules', 'purchase', 'api.ts');
const salesApiSource = readProjectFile('erp-web', 'src', 'modules', 'sales', 'api.ts');
const warehouseApiSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'warehouses', 'api.ts');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function tableDdl(tableName) {
  const start = returnSql.indexOf(`CREATE TABLE IF NOT EXISTS ${tableName} (`);
  const end = returnSql.indexOf(') ENGINE=', start);
  assert(start >= 0 && end > start, `退货 DDL 缺少 ${tableName} 表`);
  return returnSql.slice(start, end);
}

function seedBlock(tableName) {
  const start = returnSql.indexOf(`INSERT INTO ${tableName} (`);
  const end = returnSql.indexOf('ON DUPLICATE KEY UPDATE', start);
  assert(start >= 0 && end > start, `退货 SQL 缺少 ${tableName} 幂等种子`);
  return returnSql.slice(start, end);
}

async function checkApiContracts() {
  const api = await SwaggerParser.parse(openapiPath);
  const canonicalOperations = [
    ['get', '/returns'], ['post', '/returns'], ['get', '/returns/source-orders'],
    ['get', '/returns/source-orders/{sourceOrderId}/items'], ['get', '/returns/{returnOrderId}'],
    ['put', '/returns/{returnOrderId}'], ['delete', '/returns/{returnOrderId}'],
    ['post', '/returns/{returnOrderId}/submit'], ['post', '/returns/{returnOrderId}/approve'],
    ['post', '/returns/{returnOrderId}/reject'], ['post', '/returns/{returnOrderId}/cancel'],
  ];
  for (const [method, path] of canonicalOperations) {
    assert(api.paths[path]?.[method], `统一退货 OpenAPI 缺少 ${method.toUpperCase()} ${path}`);
  }
  for (const legacyPath of ['/purchase/returns', '/sales/returns']) {
    assert(!api.paths[legacyPath], `OpenAPI 不得保留重复退货路径：${legacyPath}`);
  }
  for (const action of ['submit', 'approve', 'reject', 'cancel']) {
    const operation = api.paths[`/returns/{returnOrderId}/${action}`].post;
    assert(operation.security?.some(item => 'SaTokenAuth' in item), `统一退货 ${action} 动作缺少鉴权声明`);
    assert(operation.parameters?.some(parameter => parameter.in === 'path' && parameter.name === 'returnOrderId'),
      `统一退货 ${action} 动作缺少 returnOrderId 路径参数`);
  }
  assert(JSON.stringify(api.paths['/returns'].get.parameters.map(parameter => parameter.name))
    === JSON.stringify(['returnType', 'returnNo', 'sourceOrderNo', 'partyId', 'warehouseId', 'status', 'pageNum', 'pageSize']),
  '统一退货列表查询参数与前端不一致');
  assert(JSON.stringify(api.paths['/returns/source-orders'].get.parameters.map(parameter => parameter.name))
    === JSON.stringify(['returnType', 'sourceOrderNo', 'pageSize']),
  '统一退货来源订单查询参数与前端不一致');
  // 统一退货入口曾因路径迁移只保留了响应描述。这里明确约束每个操作的请求和返回契约，避免校验只确认“路径存在”。
  const operation = (method, path) => api.paths[path][method];
  const requestSchemaRef = (target) => target.requestBody?.content?.['application/json']?.schema?.$ref;
  const successSchema = (target) => target.responses?.['200']?.content?.['application/json']?.schema;
  const resultDataRef = (target) => successSchema(target)?.allOf?.[1]?.properties?.data?.$ref;
  const resultArrayItemRef = (target) => successSchema(target)?.allOf?.[1]?.properties?.data?.items?.$ref;
  const successEmpty = (target) => successSchema(target)?.$ref === '#/components/schemas/EmptyResult';
  const ensureSecurity = (target, label) => assert(target.security?.some(item => 'SaTokenAuth' in item), `${label} 缺少 SaTokenAuth 鉴权声明`);
  const ensureErrors = (target, label, expected) => {
    for (const status of expected) assert(target.responses?.[status], `${label} 缺少 ${status} 错误响应`);
  };

  const returnList = operation('get', '/returns');
  assert(resultDataRef(returnList) === '#/components/schemas/ReturnOrderPage', 'GET /returns 成功返回未声明 ReturnOrderPage');
  ensureSecurity(returnList, 'GET /returns');
  ensureErrors(returnList, 'GET /returns', ['400', '401', '403']);

  const returnCreate = operation('post', '/returns');
  assert(requestSchemaRef(returnCreate) === '#/components/schemas/ReturnOrderCreateRequest', 'POST /returns 请求体错误');
  assert(resultDataRef(returnCreate) === '#/components/schemas/ReturnOrderDetail', 'POST /returns 成功返回未声明 ReturnOrderDetail');
  ensureSecurity(returnCreate, 'POST /returns');
  ensureErrors(returnCreate, 'POST /returns', ['400', '401', '403', '409']);

  const sourceOrderSearch = operation('get', '/returns/source-orders');
  assert(resultArrayItemRef(sourceOrderSearch) === '#/components/schemas/ReturnableSourceOrder', '来源订单搜索成功返回未声明 ReturnableSourceOrder 数组');
  ensureSecurity(sourceOrderSearch, 'GET /returns/source-orders');
  ensureErrors(sourceOrderSearch, 'GET /returns/source-orders', ['400', '401', '403']);

  const sourceItemList = operation('get', '/returns/source-orders/{sourceOrderId}/items');
  assert(resultArrayItemRef(sourceItemList) === '#/components/schemas/ReturnableSourceOrderItem', '来源明细查询成功返回未声明 ReturnableSourceOrderItem 数组');
  ensureSecurity(sourceItemList, 'GET /returns/source-orders/{sourceOrderId}/items');
  ensureErrors(sourceItemList, 'GET /returns/source-orders/{sourceOrderId}/items', ['400', '401', '403', '404']);

  const returnDetail = operation('get', '/returns/{returnOrderId}');
  assert(resultDataRef(returnDetail) === '#/components/schemas/ReturnOrderDetail', 'GET /returns/{returnOrderId} 成功返回未声明 ReturnOrderDetail');
  ensureSecurity(returnDetail, 'GET /returns/{returnOrderId}');
  ensureErrors(returnDetail, 'GET /returns/{returnOrderId}', ['401', '403', '404']);

  const returnUpdate = operation('put', '/returns/{returnOrderId}');
  assert(requestSchemaRef(returnUpdate) === '#/components/schemas/ReturnOrderUpdateRequest', 'PUT /returns/{returnOrderId} 请求体错误');
  assert(resultDataRef(returnUpdate) === '#/components/schemas/ReturnOrderDetail', 'PUT /returns/{returnOrderId} 成功返回未声明 ReturnOrderDetail');
  ensureSecurity(returnUpdate, 'PUT /returns/{returnOrderId}');
  ensureErrors(returnUpdate, 'PUT /returns/{returnOrderId}', ['400', '401', '403', '404', '409']);

  const returnDelete = operation('delete', '/returns/{returnOrderId}');
  assert(requestSchemaRef(returnDelete) === '#/components/schemas/OptimisticLockVersionRequest', 'DELETE /returns/{returnOrderId} 请求体错误');
  assert(successEmpty(returnDelete), 'DELETE /returns/{returnOrderId} 成功返回未声明 EmptyResult');
  ensureSecurity(returnDelete, 'DELETE /returns/{returnOrderId}');
  ensureErrors(returnDelete, 'DELETE /returns/{returnOrderId}', ['400', '401', '403', '404', '409']);

  for (const [action, requestSchema] of [
    ['submit', 'OptimisticLockVersionRequest'],
    ['approve', 'ReturnOrderApproveRequest'],
    ['reject', 'ReturnOrderReasonActionRequest'],
    ['cancel', 'ReturnOrderReasonActionRequest'],
  ]) {
    const target = operation('post', `/returns/{returnOrderId}/${action}`);
    assert(requestSchemaRef(target) === `#/components/schemas/${requestSchema}`, `${action} 操作请求体错误`);
    assert(successEmpty(target), `${action} 操作成功返回未声明 EmptyResult`);
    ensureSecurity(target, `${action} 操作`);
    ensureErrors(target, `${action} 操作`, ['400', '401', '403', '404', '409']);
  }

  for (const [name, source, returnType] of [
    ['采购退货', readProjectFile('erp-web', 'src', 'modules', 'purchase', 'returns', 'api.ts'), 'PURCHASE_RETURN'],
    ['销售退货', readProjectFile('erp-web', 'src', 'modules', 'sales', 'returns', 'api.ts'), 'SALES_RETURN'],
  ]) {
    assert(source.includes("const RETURN_API = '/returns'"), `${name}前端未使用统一 /returns 入口`);
    assert(source.includes(`returnType: '${returnType}'`), `${name}前端未传入 returnType=${returnType}`);
    assert(!source.includes(`/${returnType === 'PURCHASE_RETURN' ? 'purchase' : 'sales'}/returns`), `${name}前端仍调用旧退货路径`);
  }
  for (const selector of [
    {
      name: '采购退回供应商选择器',
      path: '/purchase/suppliers',
      queries: ['supplierCode', 'supplierName', 'contactName', 'status', 'pageNum', 'pageSize'],
      source: purchaseApiSource,
      fragments: ["getResult<PageResult<SupplierListItem>>('/purchase/suppliers'", 'export async function searchSupplierOptions', 'pageNum: 1', 'status: 1'],
    },
    {
      name: '销售退货客户选择器',
      path: '/sales/customers',
      queries: ['customerCode', 'customerName', 'contactName', 'status', 'pageNum', 'pageSize'],
      source: salesApiSource,
      fragments: ["getResult<PageResult<CustomerListItem>>('/sales/customers'", 'export async function searchCustomerOptions', 'pageNum: 1', 'status: 1'],
    },
    {
      name: '退货仓库选择器',
      path: '/warehouse/warehouses',
      queries: ['warehouseCode', 'warehouseName', 'contactName', 'contactPhone', 'status', 'pageNum', 'pageSize'],
      source: warehouseApiSource,
      fragments: ["getResult<WarehousePage>('/warehouse/warehouses'", 'export function listWarehouses'],
    },
  ]) {
    const operation = api.paths[selector.path]?.get;
    assert(operation, `${selector.name}复用接口缺少 GET ${selector.path}`);
    assert(JSON.stringify(operation.parameters.map(parameter => parameter.name)) === JSON.stringify(selector.queries), `${selector.name}查询参数与 OpenAPI 不一致`);
    for (const fragment of selector.fragments) assert(selector.source.includes(fragment), `${selector.name}前端实现缺少：${fragment}`);
  }

  assert(purchaseApiSource.includes("...keywordField(keyword, 'supplierCode', 'supplierName')"), '采购退回供应商搜索未按编码或名称映射到独立查询字段');
  assert(salesApiSource.includes("...keywordField(keyword, 'customerCode', 'customerName')"), '销售退货客户搜索未按编码或名称映射到独立查询字段');
  for (const source of [purchaseApiSource, salesApiSource]) {
    assert(source.includes("...keywordField(keyword, 'warehouseCode', 'warehouseName')"), '退货仓库搜索未按编码或名称映射到独立查询字段');
  }
}

function checkDdlAndSeeds() {
  const orderDdl = tableDdl('return_order');
  const itemDdl = tableDdl('return_order_item');

  for (const fragment of [
    'id BIGINT NOT NULL', 'return_no VARCHAR(64) NOT NULL', 'return_type VARCHAR(32) NOT NULL',
    'source_order_id BIGINT NOT NULL', 'source_order_no VARCHAR(64) NOT NULL', 'party_id BIGINT NOT NULL',
    'party_code VARCHAR(64) NOT NULL', 'party_name VARCHAR(200) NOT NULL', 'warehouse_id BIGINT NOT NULL',
    'warehouse_name VARCHAR(100) NOT NULL', 'expected_execution_date DATE DEFAULT NULL',
    "handling_type VARCHAR(32) NOT NULL DEFAULT 'REFUND'", "reason_code VARCHAR(32) NOT NULL DEFAULT 'OTHER'",
    "return_reason VARCHAR(500) NOT NULL DEFAULT ''", 'total_amount INT NOT NULL DEFAULT 0',
    "status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'", "status_reason VARCHAR(500) NOT NULL DEFAULT ''",
    'created_by_id BIGINT DEFAULT NULL', "created_by_name VARCHAR(100) NOT NULL DEFAULT ''",
    'submitted_at DATETIME DEFAULT NULL', 'approved_by_id BIGINT DEFAULT NULL',
    "approved_by_name VARCHAR(100) NOT NULL DEFAULT ''", 'approved_at DATETIME DEFAULT NULL',
    'create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
    'update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP',
    'deleted TINYINT NOT NULL DEFAULT 0', "remark VARCHAR(500) NOT NULL DEFAULT ''", 'version INT NOT NULL DEFAULT 0',
    'UNIQUE KEY uk_return_order_no (return_no)', 'KEY idx_return_order_source (return_type, source_order_id)',
    'KEY idx_return_order_party (return_type, party_id)', 'KEY idx_return_order_warehouse (warehouse_id)',
    'KEY idx_return_order_status (return_type, deleted, status, create_time)',
  ]) assert(orderDdl.includes(fragment), `return_order DDL 与设计文档不一致，缺少：${fragment}`);

  for (const fragment of [
    'id BIGINT NOT NULL', 'return_order_id BIGINT NOT NULL', 'source_order_item_id BIGINT NOT NULL',
    'product_id BIGINT NOT NULL', 'product_code VARCHAR(64) NOT NULL', 'product_name VARCHAR(200) NOT NULL',
    "unit_name VARCHAR(32) NOT NULL DEFAULT '件'", 'quantity_precision TINYINT NOT NULL DEFAULT 0',
    'source_fulfilled_qty INT NOT NULL DEFAULT 0', 'requested_qty INT NOT NULL DEFAULT 0',
    'approved_qty INT NOT NULL DEFAULT 0', 'processed_qty INT NOT NULL DEFAULT 0',
    'unit_price INT NOT NULL DEFAULT 0', 'total_amount INT NOT NULL DEFAULT 0',
    'create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
    'update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP',
    "remark VARCHAR(500) NOT NULL DEFAULT ''", 'UNIQUE KEY uk_return_order_item_source (return_order_id, source_order_item_id)',
    'KEY idx_return_order_item_order (return_order_id)', 'KEY idx_return_order_item_product (product_id)',
    'KEY idx_return_order_item_source_item (source_order_item_id)',
    'CHECK (quantity_precision BETWEEN 0 AND 2)', 'CHECK (source_fulfilled_qty > 0)',
    'CHECK (requested_qty > 0 AND requested_qty <= source_fulfilled_qty)',
    'CHECK (approved_qty >= 0 AND approved_qty <= requested_qty)',
    'CHECK (processed_qty >= 0 AND processed_qty <= approved_qty)',
  ]) assert(itemDdl.includes(fragment), `return_order_item DDL 与设计文档不一致，缺少：${fragment}`);

  assert(!/\bFOREIGN KEY\b/i.test(orderDdl + itemDdl), '退货 MVP 设计明确不创建物理外键');
  assert(returnSql.includes('inbound_qty <> ROUND(inbound_qty, 2)') && returnSql.includes('outbound_qty <> ROUND(outbound_qty, 2)'), '退货 SQL 缺少历史四位小数数据检查');

  const orderSeeds = seedBlock('return_order');
  const itemSeeds = seedBlock('return_order_item');
  assert((orderSeeds.match(/^\(20\d{17},/gm) || []).length === 12, 'return_order 应提供采购、销售各 6 条状态种子');
  assert((itemSeeds.match(/^\(20\d{17},/gm) || []).length === 12, 'return_order_item 应与 12 条退货单逐一对应');
  for (const type of ['PURCHASE_RETURN', 'SALES_RETURN']) {
    for (const status of ['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED', 'CANCELLED']) {
      assert(new RegExp(`'${type}'[^\\n]*'${status}'`).test(orderSeeds), `${type} 种子缺少 ${status} 状态`);
    }
  }
  for (const id of [...orderSeeds.matchAll(/^\((\d+),/gm), ...itemSeeds.matchAll(/^\((\d+),/gm)].map(match => match[1])) {
    assert(/^\d{19}$/.test(id), `退货种子主键必须是 19 位 BIGINT：${id}`);
  }

  assert(purchaseSql.includes("(2012000000000000101, 'PO202606001'") && purchaseSql.includes('(2012100000000000101, 2012000000000000101'), '采购退货种子引用的采购订单或明细不存在');
  assert(salesSql.includes("(2022000000000000101, 'SO202606001'") && salesSql.includes('(2022100000000000101, 2022000000000000101'), '销售退货种子引用的销售订单或明细不存在');
  for (const [name, source, id] of [
    ['采购退货产品', productSql, '1920000000000000001'], ['销售退货产品', productSql, '1920000000000000001'],
    ['采购退货仓库', warehouseSql, '1930000000000000001'], ['销售退货仓库', warehouseSql, '1930000000000000001'],
    ['采购创建人', systemSql, '1900000000000000002'], ['销售创建人', systemSql, '1900000000000000003'],
  ]) assert(source.includes(id), `${name}种子依赖不存在：${id}`);
  assert(warehouseSql.includes("1930000000000000001, 'WH001', '华东中心仓', 1920000000000000001, 'P000001'")
    && warehouseSql.includes("8600, 1800"), '采购退回执行种子缺少同仓库、同产品的可用库存');

  for (const validationAlias of [
    'invalid_purchase_return_seed_relation_count',
    'invalid_sales_return_seed_relation_count',
    'invalid_return_seed_amount_count',
    'invalid_return_seed_status_audit_count',
    'invalid_return_seed_occupancy_count',
    'invalid_return_seed_work_bill_count',
    'invalid_return_seed_stock_ledger_count',
    'invalid_return_seed_stock_balance_count',
    'invalid_purchase_return_seed_stock_count',
  ]) {
    assert(returnSql.includes(`AS ${validationAlias}`), `退货 SQL 缺少种子业务一致性检查：${validationAlias}`);
  }
  for (const fragment of [
    "'PURCHASE_RETURN', 'PURCHASE_RETURN_ORDER', 2030000000000000003",
    "'SALES_RETURN', 'SALES_RETURN_ORDER', 2040000000000000003",
    "'PARTIAL_EXECUTED', 'COMPLETED'",
    'business_source_item_id',
    'SUM(roi.approved_qty - roi.processed_qty)',
    'SET stock_qty = 8400',
  ]) assert(returnSql.includes(fragment), `退货仓库执行事实或剩余库存校验缺少：${fragment}`);

  for (const field of ['return_no', 'return_type', 'source_order_id', 'party_id', 'warehouse_id', 'expected_execution_date', 'handling_type', 'reason_code', 'total_amount', 'status', 'version']) {
    assert(returnSchema.includes(`\`${field}\``), `退货数据库设计缺少字段说明：${field}`);
  }
}

Promise.resolve()
  .then(checkApiContracts)
  .then(checkDdlAndSeeds)
  .then(() => console.log('RETURN_CONTRACT_OK: 前端 22 个退货请求操作、OpenAPI、2 张 DDL 与 24 条种子记录已对齐'))
  .catch(error => {
    console.error(`RETURN_CONTRACT_INVALID: ${error.message}`);
    process.exitCode = 1;
  });
