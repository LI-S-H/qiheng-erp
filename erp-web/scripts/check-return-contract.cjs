const fs = require('node:fs');
const path = require('node:path');
const SwaggerParser = require('@apidevtools/swagger-parser');

const projectRoot = path.resolve(__dirname, '..', '..');
const readProjectFile = (...segments) => fs.readFileSync(path.join(projectRoot, ...segments), 'utf8');

const openapiPath = path.join(projectRoot, 'docs', 'api', 'erp-openapi.yaml');
const returnSchema = readProjectFile('docs', 'database', 'schema', 'returns.md');
const schemaSql = readProjectFile('docs', 'database', 'init', '01-schema.sql');
const purchaseApiSource = readProjectFile('erp-web', 'src', 'modules', 'purchase', 'api.ts');
const salesApiSource = readProjectFile('erp-web', 'src', 'modules', 'sales', 'api.ts');
const warehouseApiSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'warehouses', 'api.ts');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function tableDdl(tableName) {
  const start = schemaSql.indexOf(`CREATE TABLE \`${tableName}\` (`);
  const end = schemaSql.indexOf(') ENGINE=', start);
  assert(start >= 0 && end > start, `当前初始化 SQL 缺少 ${tableName} 表`);
  return schemaSql.slice(start, end);
}

async function checkApiContracts() {
  const api = await SwaggerParser.parse(openapiPath);
  const canonicalOperations = [
    ['get', '/returns'], ['post', '/returns'], ['get', '/returns/source-orders'],
    ['get', '/returns/source-orders/{sourceOrderId}/items'], ['get', '/returns/{returnOrderId}'],
    ['put', '/returns/{returnOrderId}'], ['delete', '/returns/{returnOrderId}'],
    ['post', '/returns/{returnOrderId}/submit'], ['post', '/returns/{returnOrderId}/approve'],
    ['post', '/returns/{returnOrderId}/cancel'],
  ];
  for (const [method, path] of canonicalOperations) {
    assert(api.paths[path]?.[method], `统一退货 OpenAPI 缺少 ${method.toUpperCase()} ${path}`);
  }
  for (const legacyPath of ['/purchase/returns', '/sales/returns']) {
    assert(!api.paths[legacyPath], `OpenAPI 不得保留重复退货路径：${legacyPath}`);
  }
  for (const action of ['submit', 'approve', 'cancel']) {
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
      queries: [
        'supplierCode', 'supplierName', 'contactName', 'status', 'scoreStatus',
        'overallScoreMin', 'overallScoreMax', 'serviceScoreMin', 'serviceScoreMax',
        'scoreBasisAmountMin', 'scoreBasisAmountMax', 'avgDeliveryDaysMin', 'avgDeliveryDaysMax',
        'pageNum', 'pageSize',
      ],
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
  const columns = {
    return_order: ['id', 'return_no', 'return_type', 'source_order_id', 'source_order_no', 'party_id', 'warehouse_id', 'expected_execution_date', 'handling_type', 'reason_code', 'return_reason', 'total_amount', 'status', 'version'],
    return_order_item: ['id', 'return_order_id', 'source_order_item_id', 'product_id', 'quantity_precision', 'source_fulfilled_qty', 'requested_qty', 'approved_qty', 'processed_qty', 'unit_price', 'total_amount'],
  };
  for (const [table, fields] of Object.entries(columns)) {
    const ddl = table === 'return_order' ? orderDdl : itemDdl;
    for (const field of fields) {
      assert(new RegExp('`' + field + '`\\s+', 'i').test(ddl), `当前初始化 SQL 缺少字段：${table}.${field}`);
      assert(returnSchema.includes('`' + field + '`'), `当前退货结构文档缺少字段：${table}.${field}`);
    }
  }
  for (const field of ['source_fulfilled_qty', 'requested_qty', 'approved_qty', 'processed_qty', 'unit_price', 'total_amount']) {
    assert(new RegExp('`' + field + '`\\s+bigint\\s+NOT NULL', 'i').test(itemDdl), `退货明细字段类型不符合当前约定：${field}`);
  }
  for (const index of ['uk_return_order_no', 'idx_return_order_source', 'idx_return_order_party', 'idx_return_order_warehouse', 'idx_return_order_status']) {
    assert(orderDdl.includes('`' + index + '`'), `退货单缺少索引：${index}`);
  }
  for (const name of ['uk_return_order_item_source', 'idx_return_order_item_order', 'idx_return_order_item_product', 'idx_return_order_item_source_item', 'chk_return_order_item_approved_qty', 'chk_return_order_item_processed_qty', 'chk_return_order_item_quantity_precision', 'chk_return_order_item_requested_qty', 'chk_return_order_item_source_fulfilled_qty']) {
    assert(itemDdl.includes('`' + name + '`'), `退货明细缺少索引或检查约束：${name}`);
  }
  for (const [name, requiredParts] of [
    ['chk_return_order_item_quantity_precision', [/`quantity_precision`\s+between\s+0\s+and\s+2/i]],
    ['chk_return_order_item_source_fulfilled_qty', [/`source_fulfilled_qty`\s*>\s*0/i]],
    ['chk_return_order_item_requested_qty', [/`requested_qty`\s*>\s*0/i, /`requested_qty`\s*<=\s*`source_fulfilled_qty`/i]],
    ['chk_return_order_item_approved_qty', [/`approved_qty`\s*>=\s*0/i, /`approved_qty`\s*<=\s*`requested_qty`/i]],
    ['chk_return_order_item_processed_qty', [/`processed_qty`\s*>=\s*0/i, /`processed_qty`\s*<=\s*`approved_qty`/i]],
  ]) {
    const line = itemDdl.split('\n').find(value => value.includes('`' + name + '`')) || '';
    assert(/\bCHECK\b/i.test(line) && requiredParts.every(pattern => pattern.test(line)), `退货数量检查约束内容不正确：${name}`);
  }
}

Promise.resolve()
  .then(checkApiContracts)
  .then(checkDdlAndSeeds)
  .then(() => console.log('RETURN_CONTRACT_OK: 前端退货请求、OpenAPI、当前退货表字段及约束检查通过'))
  .catch(error => {
    console.error(`RETURN_CONTRACT_INVALID: ${error.message}`);
    process.exitCode = 1;
  });
