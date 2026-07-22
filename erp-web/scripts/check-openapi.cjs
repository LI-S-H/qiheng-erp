const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..', '..');
const readProjectFile = (...segments) => fs.readFileSync(path.join(projectRoot, ...segments), 'utf8');

const source = readProjectFile('docs', 'api', 'erp-openapi.yaml');
const pageDesign = readProjectFile('docs', 'frontend-page-design.md');
const databaseOverview = readProjectFile('docs', 'database', 'mvp-database-design-overview.md');
const permissionSchema = readProjectFile('docs', 'database', 'mvp-system-permission-schema.md');
const projectPlan = readProjectFile('docs', 'erp-project-plan.md');
const databaseSql = readProjectFile('docs', 'database', 'sql', '001_mvp_system_permission.sql');
const productSql = readProjectFile('docs', 'database', 'sql', '002_mvp_product.sql');
const authStoreSource = readProjectFile('erp-web', 'src', 'modules', 'auth', 'stores', 'authStore.ts');
const httpSource = readProjectFile('erp-web', 'src', 'api', 'http.ts');
const storageSource = readProjectFile('erp-web', 'src', 'shared', 'constants', 'storage.ts');
const frontendDevelopmentGuide = readProjectFile('docs', 'frontend-development-guide.md');
const agentInstructions = readProjectFile('AGENTS.md');
const frontendPreflightSource = readProjectFile('erp-web', 'scripts', 'frontend-preflight.cjs');
const frontendPreflightCheckSource = readProjectFile('erp-web', 'scripts', 'check-frontend-preflight.cjs');
const preCommitHook = readProjectFile('.githooks', 'pre-commit');
const productApiSource = readProjectFile('erp-web', 'src', 'modules', 'product', 'products', 'api.ts');
const productTypeSource = readProjectFile('erp-web', 'src', 'modules', 'product', 'products', 'types.ts');
const productViewSource = readProjectFile('erp-web', 'src', 'modules', 'product', 'products', 'views', 'ProductManageView.vue');
const warehouseSql = readProjectFile('docs', 'database', 'sql', '003_mvp_warehouse.sql');
const warehouseSchema = readProjectFile('docs', 'database', 'mvp-warehouse-schema.md');
const returnSchema = readProjectFile('docs', 'database', 'mvp-return-schema.md');
const returnTypeSource = readProjectFile('erp-web', 'src', 'modules', 'returns', 'types.ts');
const returnViewSource = readProjectFile('erp-web', 'src', 'modules', 'returns', 'views', 'ReturnOrderManagePage.vue');
const purchaseReturnApiSource = readProjectFile('erp-web', 'src', 'modules', 'purchase', 'returns', 'api.ts');
const purchaseReturnViewSource = readProjectFile('erp-web', 'src', 'modules', 'purchase', 'returns', 'views', 'PurchaseReturnManageView.vue');
const salesReturnApiSource = readProjectFile('erp-web', 'src', 'modules', 'sales', 'returns', 'api.ts');
const salesReturnViewSource = readProjectFile('erp-web', 'src', 'modules', 'sales', 'returns', 'views', 'SalesReturnManageView.vue');
const warehouseApiSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'warehouses', 'api.ts');
const warehouseTypeSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'warehouses', 'types.ts');
const warehouseViewSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'warehouses', 'views', 'WarehouseManageView.vue');
const warehouseStockApiSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'stocks', 'api.ts');
const warehouseStockTypeSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'stocks', 'types.ts');
const warehouseStockViewSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'stocks', 'views', 'WarehouseStockManageView.vue');
const stockBillApiSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'stock-bills', 'api.ts');
const stockBillTypeSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'stock-bills', 'types.ts');
const stockBillViewSource = readProjectFile('erp-web', 'src', 'modules', 'warehouse', 'stock-bills', 'views', 'StockBillManageView.vue');
const aiApiSource = readProjectFile('erp-web', 'src', 'modules', 'ai', 'api.ts');
const aiViewSource = readProjectFile('erp-web', 'src', 'modules', 'ai', 'views', 'AiAssistantView.vue');

const routerSource = readProjectFile('erp-web', 'src', 'router', 'index.ts');
const listRefreshSource = readProjectFile('erp-web', 'src', 'shared', 'composables', 'use-list-refresh.ts');
const pagedQuerySource = readProjectFile('erp-web', 'src', 'shared', 'composables', 'use-paged-query.ts');
const listViewSources = [
  readProjectFile('erp-web', 'src', 'modules', 'system', 'users', 'views', 'UserManageView.vue'),
  readProjectFile('erp-web', 'src', 'modules', 'system', 'roles', 'views', 'RoleManageView.vue'),
  readProjectFile('erp-web', 'src', 'modules', 'system', 'depts', 'views', 'DeptManageView.vue'),
  readProjectFile('erp-web', 'src', 'modules', 'system', 'permissions', 'views', 'PermissionManageView.vue'),
  readProjectFile('erp-web', 'src', 'modules', 'product', 'categories', 'views', 'ProductCategoryManageView.vue'),
  productViewSource,
  warehouseViewSource,
  warehouseStockViewSource,
  stockBillViewSource,
  readProjectFile('erp-web', 'src', 'modules', 'purchase', 'suppliers', 'views', 'SupplierManageView.vue'),
  readProjectFile('erp-web', 'src', 'modules', 'purchase', 'supplier-products', 'views', 'SupplierProductManageView.vue'),
  readProjectFile('erp-web', 'src', 'modules', 'purchase', 'orders', 'views', 'PurchaseOrderManageView.vue'),
  returnViewSource,
  readProjectFile('erp-web', 'src', 'modules', 'sales', 'customers', 'views', 'CustomerManageView.vue'),
  readProjectFile('erp-web', 'src', 'modules', 'sales', 'orders', 'views', 'SalesOrderManageView.vue'),
];
const anchoredSelectSource = readProjectFile('erp-web', 'src', 'components', 'common', 'AnchoredSelect.vue');
const treeSelectSource = readProjectFile('erp-web', 'src', 'components', 'common', 'TreeSelect.vue');
const exclusiveDropdownSource = readProjectFile('erp-web', 'src', 'shared', 'composables', 'use-exclusive-dropdown.ts');
const categoryApiSource = readProjectFile('erp-web', 'src', 'modules', 'product', 'categories', 'api.ts');
const apiNormalizerSource = readProjectFile('erp-web', 'src', 'shared', 'utils', 'api-normalizers.ts');
const userApiSource = readProjectFile('erp-web', 'src', 'modules', 'system', 'users', 'api.ts');
const roleApiSource = readProjectFile('erp-web', 'src', 'modules', 'system', 'roles', 'api.ts');
const deptApiSource = readProjectFile('erp-web', 'src', 'modules', 'system', 'depts', 'api.ts');
const sqlDirectory = path.join(projectRoot, 'docs', 'database', 'sql');
const allSql = fs.readdirSync(sqlDirectory)
  .filter(fileName => fileName.endsWith('.sql'))
  .map(fileName => fs.readFileSync(path.join(sqlDirectory, fileName), 'utf8'))
  .join('\n');

const requiredPaths = [
  '/auth/login:',
  '/auth/me:',
  '/ai/assistant/conversations/{conversationId}/messages:',
  '/system/users:',
  '/system/roles:',
  '/system/depts:',
  '/system/depts/batch/status:',
  '/system/permissions:',
  '/system/permissions/{permissionId}:',
  '/system/permissions/batch/status:',
  '/system/permissions/options:',
  '/products:',
  '/products/batch/status:',
  '/products/batch/delete:',
  '/products/{productId}:',
  '/products/{productId}/status:',
  '/product/categories:',
  '/product/categories/batch/status:',
  '/product/categories/batch/delete:',
  '/product/categories/{categoryId}:',
  '/product/categories/{categoryId}/status:',
  '/warehouse/warehouses:',
  '/warehouse/warehouses/batch/status:',
  '/warehouse/warehouses/batch/delete:',
  '/warehouse/warehouses/{warehouseId}:',
  '/warehouse/warehouses/{warehouseId}/status:',
  '/warehouse/stocks:',
  '/warehouse/inbound-bills:',
  '/warehouse/outbound-bills:',
  '/warehouse/stock-bills:',
  '/warehouse/work-bills/{workBillId}:',
  '/warehouse/work-bills/{workBillId}/submit:',
  '/warehouse/work-bills/{workBillId}/confirm:',
  '/warehouse/work-bills/{workBillId}/cancel:',
  '/purchase/returns:',
  '/purchase/returns/source-orders:',
  '/purchase/returns/source-orders/{sourceOrderId}/items:',
  '/purchase/returns/{returnOrderId}:',
  '/purchase/returns/{returnOrderId}/submit:',
  '/purchase/returns/{returnOrderId}/approve:',
  '/purchase/returns/{returnOrderId}/reject:',
  '/purchase/returns/{returnOrderId}/cancel:',
  '/sales/returns:',
  '/sales/returns/source-orders:',
  '/sales/returns/source-orders/{sourceOrderId}/items:',
  '/sales/returns/{returnOrderId}:',
  '/sales/returns/{returnOrderId}/submit:',
  '/sales/returns/{returnOrderId}/approve:',
  '/sales/returns/{returnOrderId}/reject:',
  '/sales/returns/{returnOrderId}/cancel:',
];

for (const requiredPath of requiredPaths) {
  if (!source.includes(`  ${requiredPath}`)) throw new Error(`OpenAPI 缺少路径：${requiredPath}`);
}

const definitions = new Set([...source.matchAll(/^    ([A-Za-z0-9_]+):\s*$/gm)].map(match => match[1]));
const references = [...source.matchAll(/#\/components\/(?:schemas|parameters|responses)\/([A-Za-z0-9_]+)/g)].map(match => match[1]);
const missing = [...new Set(references.filter(name => !definitions.has(name)))];
if (missing.length > 0) throw new Error(`OpenAPI 存在断开的 $ref：${missing.join(', ')}`);

if (source.includes('deptName: 总部')) throw new Error('OpenAPI 仍残留“总部”部门口径');
if (!source.includes('扁平列表') || !source.includes('parentId')) throw new Error('部门接口未明确扁平列表套约');
if (!source.includes('product_category` 未删除数据的扁平数组') || !source.includes('productCount')) {
  throw new Error('产品分类接口未明确扁平列表和产品数量聚合契约');
}
if (/\n\s*- name: keyword\s*$/m.test(source)) {
  throw new Error('OpenAPI 列表查询不得使用未声明匹配边界的 keyword 参数');
}
const purchaseReturnListStart = source.indexOf('  /purchase/returns:');
const purchaseReturnSourcesStart = source.indexOf('  /purchase/returns/source-orders:', purchaseReturnListStart);
const purchaseReturnListContract = source.slice(purchaseReturnListStart, purchaseReturnSourcesStart);
if (purchaseReturnListStart < 0 || purchaseReturnSourcesStart < 0
  || !purchaseReturnListContract.includes('\n    get:') || !purchaseReturnListContract.includes('\n    post:')) {
  throw new Error('采购退回列表必须同时提供 GET 查询和 POST 创建接口');
}
for (const parameterName of ['returnNo', 'sourceOrderNo', 'supplierId', 'warehouseId', 'status', 'pageNum', 'pageSize']) {
  if (!purchaseReturnListContract.includes(`- name: ${parameterName}`)) {
    throw new Error(`采购退回列表缺少独立查询参数：${parameterName}`);
  }
}
if (purchaseReturnListContract.includes('- name: keyword')) {
  throw new Error('采购退回列表不得使用含义不明的 keyword 参数');
}
for (const fragment of [
  'availableReturnQty',
  'sourceFulfilledQty - occupiedQty',
  'returnType: { type: string, enum: [PURCHASE_RETURN]',
  'source_type=PURCHASE_RETURN_ORDER',
  'entry_mode=SOURCE_GENERATED',
  'purchase:query',
  'purchase:create',
  'purchase:manage',
]) {
  if (!source.includes(fragment)) throw new Error(`采购退回 OpenAPI 缺少关键契约：${fragment}`);
}
for (const fragment of ['`return_order`', '`return_order_item`', '草稿不占用数量', '采购退货确认前必须重新校验对应仓库的可用库存']) {
  if (!returnSchema.includes(fragment)) throw new Error(`退货数据库设计缺少权威规则：${fragment}`);
}
const purchaseReturnCreateStart = source.indexOf('    PurchaseReturnOrderCreateRequest:');
const purchaseReturnUpdateStart = source.indexOf('    ReturnOrderUpdateRequest:', purchaseReturnCreateStart);
const purchaseReturnCreateSchema = source.slice(purchaseReturnCreateStart, purchaseReturnUpdateStart);
if (purchaseReturnCreateStart < 0 || purchaseReturnUpdateStart < 0
  || !purchaseReturnCreateSchema.includes('enum: [PURCHASE_RETURN]')) {
  throw new Error('采购退回创建请求必须由 adapter 固定提交 PURCHASE_RETURN');
}
const purchaseReturnUpdateEnd = source.indexOf('    ReturnOrderDraftItemRequest:', purchaseReturnUpdateStart);
const purchaseReturnUpdateSchema = source.slice(purchaseReturnUpdateStart, purchaseReturnUpdateEnd);
if (purchaseReturnUpdateStart < 0 || purchaseReturnUpdateEnd < 0 || /\n\s+returnType:/.test(purchaseReturnUpdateSchema)) {
  throw new Error('采购退回类型创建后不可修改，更新 DTO 不得包含 returnType');
}
const returnDraftItemStart = source.indexOf('    ReturnOrderDraftItemRequest:');
const returnDraftItemEnd = source.indexOf('    ReturnOrderApproveRequest:', returnDraftItemStart);
const returnDraftItemSchema = source.slice(returnDraftItemStart, returnDraftItemEnd);
if (returnDraftItemStart < 0 || returnDraftItemEnd < 0 || /\n\s+returnOrderItemId:/.test(returnDraftItemSchema)) {
  throw new Error('退货草稿明细 DTO 只允许来源明细 ID、申请数量和备注，不得提交后端生成的 returnOrderItemId');
}
for (const [schemaName, schemaText] of [
  ['采购退回创建请求', purchaseReturnCreateSchema],
  ['采购退回更新请求', purchaseReturnUpdateSchema],
]) {
  for (const readOnlyField of [
    'sourceOrderNo', 'partyId', 'partyCode', 'partyName', 'warehouseName', 'productId', 'productCode',
    'productName', 'unitName', 'quantityPrecision', 'sourceFulfilledQty', 'occupiedQty', 'availableReturnQty',
    'unitPrice', 'totalAmount', 'status', 'statusReason', 'createdById', 'submittedAt', 'approvedById',
    'approvedQty', 'processedQty', 'createTime', 'updateTime',
  ]) {
    if (new RegExp(`\\n\\s+${readOnlyField}:`).test(schemaText)) {
      throw new Error(`${schemaName}不得提交只读字段：${readOnlyField}`);
    }
  }
}
const purchaseReturnDetailPathStart = source.indexOf('  /purchase/returns/{returnOrderId}:');
const purchaseReturnSubmitPathStart = source.indexOf('  /purchase/returns/{returnOrderId}/submit:', purchaseReturnDetailPathStart);
const purchaseReturnDetailPath = source.slice(purchaseReturnDetailPathStart, purchaseReturnSubmitPathStart);
for (const method of ['get:', 'put:', 'delete:']) {
  if (!purchaseReturnDetailPath.includes(`    ${method}`)) throw new Error(`采购退回详情资源缺少 ${method}`);
}
for (const action of ['submit', 'approve', 'reject', 'cancel']) {
  const actionStart = source.indexOf(`  /purchase/returns/{returnOrderId}/${action}:`);
  const nextPath = source.indexOf('\n  /', actionStart + 4);
  const actionContract = source.slice(actionStart, nextPath < 0 ? source.length : nextPath);
  if (actionStart < 0 || !actionContract.includes('\n    post:') || !actionContract.includes('requestBody:')) {
    throw new Error(`采购退回 ${action} 动作缺少 POST 或请求体契约`);
  }
}
for (const fragment of [
  "getResult<ReturnOrderPage>('/purchase/returns', params)",
  "postResult<ReturnOrderDetail, ReturnOrderCreateRequest>('/purchase/returns', request)",
  'http.put<Result<ReturnOrderDetail>>(`/purchase/returns/${returnOrderId}`, payload)',
  'http.delete(`/purchase/returns/${returnOrderId}`, { data: { version } })',
  '`/purchase/returns/${returnOrderId}/submit`',
  '`/purchase/returns/${returnOrderId}/approve`',
  '`/purchase/returns/${returnOrderId}/reject`',
  '`/purchase/returns/${returnOrderId}/cancel`',
  "getResult<ReturnableSourceOrderPage>('/purchase/returns/source-orders'",
  '`/purchase/returns/source-orders/${sourceOrderId}/items`',
  "returnType: 'PURCHASE_RETURN'",
  "supplierId: query.partyId && query.partyId !== 'all' ? query.partyId : undefined",
]) {
  if (!purchaseReturnApiSource.includes(fragment)) throw new Error(`采购退回前端适配层缺少接口契约：${fragment}`);
}
for (const fragment of ['normalizeReturnDetail', 'normalizeReturnItem', 'normalizeSourceItem', 'availableReturnQty']) {
  if (!purchaseReturnApiSource.includes(fragment)) throw new Error(`采购退回前端适配层缺少响应规范化或服务端可退量字段：${fragment}`);
}
for (const fragment of [
  'ListFilterPanel', 'ListFilterActions', 'ListSummaryStrip', 'ListLoadingOverlay', 'DataTablePagination',
  'RemoteSearchSelect', 'AnchoredSelect', 'OrderDatePicker', 'RowActionsMenu', 'ConfirmDialog', 'PromptDialog',
  'usePagedQuery', 'props.config.service', 'availableReturnQty', 'businessLabel',
]) {
  if (!returnViewSource.includes(fragment)) throw new Error(`通用退货页面未复用标准组件或缺少关键实现：${fragment}`);
}
for (const fragment of [
  "returnType: 'PURCHASE_RETURN'", "query: 'purchase:query'", "create: 'purchase:create'", "manage: 'purchase:manage'",
  'listReturns: listPurchaseReturns', 'createReturn: createPurchaseReturn', 'searchSourceOrders: searchPurchaseReturnSourceOrders',
]) {
  if (!purchaseReturnViewSource.includes(fragment)) throw new Error(`采购退回页面配置缺少适配或权限：${fragment}`);
}
if (returnViewSource.includes("@/modules/purchase/") || returnViewSource.includes("@/modules/sales/")) {
  throw new Error('通用退货页面不得反向依赖采购或销售模块');
}
for (const readonlyField of ['returnOrderItemId', 'sourceOrderNo', 'partyId', 'partyName', 'warehouseName', 'availableReturnQty', 'approvedQty', 'processedQty']) {
  const formPayloadStart = returnTypeSource.indexOf('export interface ReturnOrderFormPayload');
  const createRequestStart = returnTypeSource.indexOf('export interface ReturnOrderCreateRequest', formPayloadStart);
  const formPayloadSource = returnTypeSource.slice(formPayloadStart, createRequestStart);
  if (new RegExp(`\\n\\s+${readonlyField}[?:]:`).test(formPayloadSource)) {
    throw new Error(`采购退回前端表单 DTO 不得包含只读字段：${readonlyField}`);
  }
}
for (const fragment of ['normalizeQuantityPrecision', 'normalizeQuantity(item.sourceFulfilledQty', 'normalizeQuantity(item.availableReturnQty']) {
  if (!purchaseReturnApiSource.includes(fragment)) throw new Error(`采购退回数量精度边界缺少显式校验：${fragment}`);
}
if (!purchaseReturnApiSource.includes("normalizeQuantity(draft.requestedQty, sourceItem.quantityPrecision, 'requestedQty')")
  || purchaseReturnApiSource.includes('Number.isInteger(draft.requestedQty * factor)')) {
  throw new Error('采购退回 Mock 数量精度校验必须使用浮点容差，不得直接依赖乘法后的 Number.isInteger');
}
if (purchaseReturnApiSource.includes('Math.min(2, Math.max(0, item.quantityPrecision))')) {
  throw new Error('采购退回来源数量精度不得静默夹到 0～2');
}
if (!purchaseReturnApiSource.includes('function nextMockReturnItemId()')
  || purchaseReturnApiSource.includes('`${returnOrderId}1${String(index + 1)')) {
  throw new Error('采购退回 Mock 明细必须使用独立 19 位 ID，不得在 19 位主键后继续拼接');
}
const salesReturnListStart = source.indexOf('  /sales/returns:');
const salesReturnSourcesStart = source.indexOf('  /sales/returns/source-orders:', salesReturnListStart);
const salesReturnListContract = source.slice(salesReturnListStart, salesReturnSourcesStart);
if (salesReturnListStart < 0 || salesReturnSourcesStart < 0
  || !salesReturnListContract.includes('\n    get:') || !salesReturnListContract.includes('\n    post:')) {
  throw new Error('销售退货列表必须同时提供 GET 查询和 POST 创建接口');
}
for (const parameterName of ['returnNo', 'sourceOrderNo', 'customerId', 'warehouseId', 'status', 'pageNum', 'pageSize']) {
  if (!salesReturnListContract.includes(`- name: ${parameterName}`)) {
    throw new Error(`销售退货列表缺少独立查询参数：${parameterName}`);
  }
}
if (salesReturnListContract.includes('- name: keyword')) throw new Error('销售退货列表不得使用含义不明的 keyword 参数');
for (const fragment of [
  'sales_order_item.outbound_qty',
  'returnType: { type: string, enum: [SALES_RETURN]',
  'source_type=SALES_RETURN_ORDER',
  'inbound_type=SALES_RETURN',
  'sales:query',
  'sales:create',
  'sales:manage',
]) {
  if (!source.includes(fragment)) throw new Error(`销售退货 OpenAPI 缺少关键契约：${fragment}`);
}
const salesReturnCreateStart = source.indexOf('    SalesReturnOrderCreateRequest:');
const salesReturnCreateEnd = source.indexOf('    ReturnOrderUpdateRequest:', salesReturnCreateStart);
const salesReturnCreateSchema = source.slice(salesReturnCreateStart, salesReturnCreateEnd);
if (salesReturnCreateStart < 0 || salesReturnCreateEnd < 0 || !salesReturnCreateSchema.includes('enum: [SALES_RETURN]')) {
  throw new Error('销售退货创建请求必须由 adapter 固定提交 SALES_RETURN');
}
for (const readOnlyField of [
  'sourceOrderNo', 'partyId', 'partyCode', 'partyName', 'warehouseName', 'productId', 'productCode',
  'productName', 'unitName', 'quantityPrecision', 'sourceFulfilledQty', 'occupiedQty', 'availableReturnQty',
  'unitPrice', 'totalAmount', 'status', 'statusReason', 'createdById', 'submittedAt', 'approvedById',
  'approvedQty', 'processedQty', 'createTime', 'updateTime',
]) {
  if (new RegExp(`\\n\\s+${readOnlyField}:`).test(salesReturnCreateSchema)) {
    throw new Error(`销售退货创建请求不得提交只读字段：${readOnlyField}`);
  }
}
const salesReturnDetailPathStart = source.indexOf('  /sales/returns/{returnOrderId}:');
const salesReturnSubmitPathStart = source.indexOf('  /sales/returns/{returnOrderId}/submit:', salesReturnDetailPathStart);
const salesReturnDetailPath = source.slice(salesReturnDetailPathStart, salesReturnSubmitPathStart);
for (const method of ['get:', 'put:', 'delete:']) {
  if (!salesReturnDetailPath.includes(`    ${method}`)) throw new Error(`销售退货详情资源缺少 ${method}`);
}
for (const action of ['submit', 'approve', 'reject', 'cancel']) {
  const actionStart = source.indexOf(`  /sales/returns/{returnOrderId}/${action}:`);
  const nextPath = source.indexOf('\n  /', actionStart + 4);
  const actionContract = source.slice(actionStart, nextPath < 0 ? source.length : nextPath);
  if (actionStart < 0 || !actionContract.includes('\n    post:') || !actionContract.includes('requestBody:')) {
    throw new Error(`销售退货 ${action} 动作缺少 POST 或请求体契约`);
  }
}
for (const fragment of [
  "getResult<ReturnOrderPage>('/sales/returns', params)",
  "postResult<ReturnOrderDetail, ReturnOrderCreateRequest>('/sales/returns', request)",
  'http.put<Result<ReturnOrderDetail>>(`/sales/returns/${returnOrderId}`, payload)',
  'http.delete(`/sales/returns/${returnOrderId}`, { data: { version } })',
  '`/sales/returns/${returnOrderId}/submit`',
  '`/sales/returns/${returnOrderId}/approve`',
  '`/sales/returns/${returnOrderId}/reject`',
  '`/sales/returns/${returnOrderId}/cancel`',
  "getResult<ReturnableSourceOrderPage>('/sales/returns/source-orders'",
  '`/sales/returns/source-orders/${sourceOrderId}/items`',
  "returnType: 'SALES_RETURN'",
  "customerId: query.partyId && query.partyId !== 'all' ? query.partyId : undefined",
  'item.outboundQty',
]) {
  if (!salesReturnApiSource.includes(fragment)) throw new Error(`销售退货前端适配层缺少接口契约：${fragment}`);
}
for (const fragment of ['normalizeReturnDetail', 'normalizeReturnItem', 'normalizeSourceItem', 'availableReturnQty', 'normalizeQuantityPrecision']) {
  if (!salesReturnApiSource.includes(fragment)) throw new Error(`销售退货前端适配层缺少响应规范化或数量边界：${fragment}`);
}
if (!salesReturnApiSource.includes("normalizeQuantity(draft.requestedQty, sourceItem.quantityPrecision, 'requestedQty')")
  || salesReturnApiSource.includes('Number.isInteger(draft.requestedQty * factor)')
  || salesReturnApiSource.includes('Math.min(2, Math.max(0, item.quantityPrecision))')) {
  throw new Error('销售退货数量精度必须显式校验并使用浮点容差，不得静默截断或夹取');
}
if (!salesReturnApiSource.includes('function nextMockReturnItemId()')
  || salesReturnApiSource.includes('`${returnOrderId}1${String(index + 1)')) {
  throw new Error('销售退货 Mock 明细必须使用独立 19 位 ID，不得在 19 位主键后继续拼接');
}
if (source.includes('原采购明细单价快照')) {
  throw new Error('共享退货来源明细单价描述不得残留采购专属语义');
}
for (const fragment of [
  "returnType: 'SALES_RETURN'", "query: 'sales:query'", "create: 'sales:create'", "manage: 'sales:manage'",
  "returnNoPlaceholder: '如 SR202607001'", "fulfilledQuantityLabel: '已出库'", 'listReturns: listSalesReturns', 'createReturn: createSalesReturn',
  'searchSourceOrders: searchSalesReturnSourceOrders',
]) {
  if (!salesReturnViewSource.includes(fragment)) throw new Error(`销售退货页面配置缺少适配、权限或履约文案：${fragment}`);
}
if (!routerSource.includes("path: 'sales/returns'") || !readProjectFile('erp-web', 'src', 'layouts', 'MainLayout.vue').includes("index: '/sales/returns'")) {
  throw new Error('销售退货缺少路由或导航入口');
}
for (const fragment of [
  '查询范围包含当前分类及其全部后代分类',
  'productCode',
  'productName',
  'brandName',
  'barcode',
]) {
  if (!source.includes(fragment)) throw new Error(`OpenAPI 缺少字段级查询或分类后代查询契约：${fragment}`);
}
for (const fragment of ['# 前端开发规范', '一个筛选控件必须对应一个明确的查询参数', '禁止为了减少筛选框使用含义不明的 `keyword`', '历史问题清单']) {
  if (!frontendDevelopmentGuide.includes(fragment)) throw new Error(`前端开发规范缺少强制规则：${fragment}`);
}
for (const fragment of ['非用户输入字段不得渲染为可编辑控件', '同一页面同一时刻只能打开一个下拉弹层', '人民币显示 `￥`', '业务状态不能使用普通下拉任意修改']) {
  if (!frontendDevelopmentGuide.includes(fragment)) throw new Error(`前端开发规范缺少表单字段或下拉交互规则：${fragment}`);
}
for (const fragment of ['前端开发强制前置流程', 'npm run preflight:frontend -- <scope>', '禁止使用 `--no-verify`']) {
  if (!agentInstructions.includes(fragment)) throw new Error(`AGENTS.md 缺少前端开发门禁规则：${fragment}`);
}
if (!frontendPreflightSource.includes('FRONTEND_PREFLIGHT_OK')
  || !frontendPreflightCheckSource.includes('FRONTEND_PREFLIGHT_REQUIRED')
  || !preCommitHook.includes('check-frontend-preflight.cjs')) {
  throw new Error('前端开发预检脚本或 Git pre-commit 门禁不完整');
}
for (const fragment of ['normalizeBinaryStatus', 'normalizeFiniteNumber', 'normalizeStringId']) {
  if (!apiNormalizerSource.includes(fragment) || !productApiSource.includes(fragment)) {
    throw new Error(`产品 API 缺少响应字段转换：${fragment}`);
  }
}
if (!categoryApiSource.includes('normalizeCategory') || !categoryApiSource.includes('getMockProductCategoryScope')) {
  throw new Error('产品分类 API 缺少下拉响应转换或父子分类范围计算');
}
if (!roleApiSource.includes('normalizeRoleOption') || !deptApiSource.includes('normalizeDeptOption')) {
  throw new Error('用户筛选下拉的角色和部门选项缺少响应类型转换');
}
for (const fragment of [
  '级联停用全部下级分类，并停用当前分类和全部下级分类直接关联的未删除产品',
  '员工账号不自动停用',
  '存在下级部门或已绑定用户时返回 409',
]) {
  if (!source.includes(fragment)) throw new Error(`OpenAPI 缺少层级停用或删除保护契约：${fragment}`);
}
for (const fragment of ['前端只展示通用风险说明', '不能作为是否允许操作的最终依据', '编辑表单内修改状态和列表批量启停']) {
  if (!readProjectFile('docs', 'frontend-style-guide.md').includes(fragment)) {
    throw new Error(`前端规范缺少层级操作风险规则：${fragment}`);
  }
}
if (/Element Plus/i.test(pageDesign)) throw new Error('前端设计文档仍残留 Element Plus 技术选型');
if (!storageSource.includes('AUTH_TOKEN_NAME_STORAGE_KEY')
  || !authStoreSource.includes('loginResult.tokenName')
  || !httpSource.includes("config.headers.set(tokenName, token)")) {
  throw new Error('Sa-Token 前端鉴权必须保存登录响应 tokenName，并用它动态设置请求头');
}

const requiredContractFragments = [
  "pattern: '^[A-Za-z][A-Za-z0-9_]{2,63}$'",
  'minItems: 1',
  'minLength: 1',
  '#/components/responses/Conflict',
];
for (const fragment of requiredContractFragments) {
  if (!source.includes(fragment)) throw new Error(`OpenAPI 缺少必填或唯一性约束：${fragment}`);
}

if (!databaseSql.includes("dept_id BIGINT NOT NULL COMMENT '所属部门ID'")) {
  throw new Error('数据库 DDL 与用户所属部门必填套约不一致');
}
if (!databaseSql.includes('CREATE TABLE IF NOT EXISTS sys_permission')
  || !databaseSql.includes('UNIQUE KEY uk_sys_permission_code')
  || !databaseSql.includes('KEY idx_sys_permission_module_action (module_code, action_type)')
  || !databaseSql.includes('KEY idx_sys_permission_deleted_status_sort (deleted, status, sort_order)')) {
  throw new Error('数据库 DDL 缺少权限目录表、唯一索引或列表查询索引');
}
if (!source.includes("pattern: '^[a-z][a-z0-9]*(?::[a-z][a-z0-9]*){1,3}$'")) {
  throw new Error('OpenAPI 缺少权限码格式约束');
}
if (source.includes('moduleName:')) {
  throw new Error('OpenAPI 不应要求后端返回数据库中不存在的权限模块名称字段');
}

const categorySchemaStart = source.indexOf('    ProductCategory:');
const categorySchemaEnd = source.indexOf('    ProductCategoryCreateRequest:', categorySchemaStart);
const categorySchema = source.slice(categorySchemaStart, categorySchemaEnd);
if (categorySchemaStart < 0 || categorySchemaEnd < 0 || categorySchema.includes('children:')
  || categorySchema.includes('parentName:') || categorySchema.includes('categoryPath:')) {
  throw new Error('产品分类返回结构不应包含前端可计算的树节点、上级名称或层级路径');
}
const productTableStart = productSql.indexOf('CREATE TABLE IF NOT EXISTS product (');
const productTableEnd = productSql.indexOf(') ENGINE=', productTableStart);
const productTableDdl = productSql.slice(productTableStart, productTableEnd);
if (productTableStart < 0 || productTableEnd < 0 || /\bcategory_name\b/i.test(productTableDdl)) {
  throw new Error('product 主数据表不应冗余 category_name，分类名称应按 category_id 关联查询');
}

const productSchemaStart = source.indexOf('    Product:');
const productSchemaEnd = source.indexOf('    ProductFormRequest:', productSchemaStart);
const productSchema = source.slice(productSchemaStart, productSchemaEnd);
if (productSchemaStart < 0 || productSchemaEnd < 0 || !productSchema.includes('categoryName:')
  || !productSchema.includes('nullable: true') || !productSchema.includes('readOnly: true')
  || productSchema.includes('categoryPath:')) {
  throw new Error('产品档案必须只返回可关联查询的分类名称，不能要求后端拼接分类层级路径');
}
if (!productSchema.includes('未分类时返回 `null`')) {
  throw new Error('产品档案未分类时必须明确返回 categoryName: null，不能要求后端拼装空字符串');
}
const productFormStart = source.indexOf('    ProductFormRequest:');
const productFormEnd = source.indexOf('    ProductStatusRequest:', productFormStart);
const productFormSchema = source.slice(productFormStart, productFormEnd);
if (productFormStart < 0 || productFormEnd < 0 || productFormSchema.includes('productCode:')) {
  throw new Error('产品创建和编辑请求不得接收由后端生成的 productCode');
}
if (!source.includes('产品编码由后端统一生成') || !source.includes('产品编码创建后不可修改')) {
  throw new Error('OpenAPI 未明确产品编码的后端生成和不可修改规则');
}
const productFormTypeStart = productTypeSource.indexOf('export interface ProductFormPayload');
const productFormTypeEnd = productTypeSource.indexOf('export interface ProductBatchIdsPayload', productFormTypeStart);
const productFormType = productTypeSource.slice(productFormTypeStart, productFormTypeEnd);
if (productFormTypeStart < 0 || productFormTypeEnd < 0 || productFormType.includes('productCode')) {
  throw new Error('前端产品提交 DTO 不得包含由后端生成的 productCode');
}
if (!productApiSource.includes('generateMockProductCode()')
  || !productViewSource.includes('data-product-code-display')
  || !productViewSource.includes('data-currency-prefix')) {
  throw new Error('产品编码系统生成展示或金额货币前缀实现不完整');
}
for (const fragment of ['warehouseCode', 'warehouseName', 'contactName', 'contactPhone', '多个有效条件按 AND 组合']) {
  if (!source.includes(fragment)) throw new Error(`仓库管理 OpenAPI 缺少字段级查询契约：${fragment}`);
}
const warehouseSchemaStart = source.indexOf('    Warehouse:');
const warehouseSchemaEnd = source.indexOf('    WarehouseCreateRequest:', warehouseSchemaStart);
const warehouseResponseSchema = source.slice(warehouseSchemaStart, warehouseSchemaEnd);
if (warehouseSchemaStart < 0 || warehouseSchemaEnd < 0 || !warehouseResponseSchema.includes('warehouseCode:')
  || !warehouseResponseSchema.includes('readOnly: true')) {
  throw new Error('仓库返回结构必须包含创建后只读的仓库编码');
}
const warehouseUpdateStart = source.indexOf('    WarehouseUpdateRequest:');
const warehouseUpdateEnd = source.indexOf('    WarehouseStatusRequest:', warehouseUpdateStart);
const warehouseUpdateSchema = source.slice(warehouseUpdateStart, warehouseUpdateEnd);
if (warehouseUpdateStart < 0 || warehouseUpdateEnd < 0 || warehouseUpdateSchema.includes('warehouseCode:')) {
  throw new Error('仓库编辑请求不得包含创建后不可修改的 warehouseCode');
}
const warehouseCreateStart = source.indexOf('    WarehouseCreateRequest:');
const warehouseCreateEnd = source.indexOf('    WarehouseUpdateRequest:', warehouseCreateStart);
const warehouseCreateSchema = source.slice(warehouseCreateStart, warehouseCreateEnd);
const warehouseCreateRequired = warehouseCreateSchema.match(/required:\s*\[([^\]]*)\]/)?.[1] || '';
if (warehouseCreateStart < 0 || warehouseCreateEnd < 0
  || warehouseCreateSchema.includes('warehouseCode:')) {
  throw new Error('仓库创建请求不得包含由后端生成的 warehouseCode');
}
if (warehouseCreateRequired.split(',').map(field => field.trim()).includes('remark')) {
  throw new Error('仓库创建请求的 remark 为选填字段，不得列入 required');
}
const warehouseFormTypeStart = warehouseTypeSource.indexOf('export interface WarehouseFormPayload');
const warehouseFormTypeEnd = warehouseTypeSource.indexOf('export type WarehouseCreatePayload', warehouseFormTypeStart);
if (warehouseFormTypeStart < 0 || warehouseFormTypeEnd < 0
  || warehouseTypeSource.slice(warehouseFormTypeStart, warehouseFormTypeEnd).includes('warehouseCode:')) {
  throw new Error('前端仓库创建和编辑 DTO 不得包含 warehouseCode');
}
if (!warehouseTypeSource.includes("Omit<WarehouseFormPayload, 'remark'> & { remark?: string }")) {
  throw new Error('前端仓库创建 DTO 必须将 remark 声明为选填字段');
}
for (const fragment of ['normalizeStringId', 'normalizeBinaryStatus', 'normalizeWarehousePage']) {
  if (!warehouseApiSource.includes(fragment)) throw new Error(`仓库 API 缺少响应字段转换：${fragment}`);
}
for (const fragment of ['data-warehouse-code', '保存后由系统生成', 'warehouseDisableWarning', 'table-fixed']) {
  if (!warehouseViewSource.includes(fragment)) throw new Error(`仓库管理页面缺少关键交互实现：${fragment}`);
}
if (!warehouseApiSource.includes('generateMockWarehouseCode()') || !source.includes('仓库编码由后端统一生成')) {
  throw new Error('仓库编码的后端生成契约或 Mock 实现不完整');
}
for (const fragment of [
  'availableQty 必须等于 stockQty - lockedQty',
  "health === 'LOW_STOCK'",
  "state === 'PARTIALLY_LOCKED'",
  'inventoryHealth',
  'reservationState',
  'WarehouseStockSummary',
  'warehouseCount',
  'productCount',
  'lowStockCount',
  'noAvailableCount',
  'lockedCount',
]) {
  if (!warehouseStockApiSource.includes(fragment) && !warehouseStockTypeSource.includes(fragment)) {
    throw new Error(`库存管理前端契约缺少：${fragment}`);
  }
}
for (const fragment of [
  'stock_qty - locked_qty',
  '0 < available_qty <= safety_stock_qty',
  '不同单位的库存数量不得跨产品汇总',
  "enum: [NORMAL, LOW_STOCK, NO_AVAILABLE, OUT_OF_STOCK]",
  "enum: [UNLOCKED, PARTIALLY_LOCKED, FULLY_LOCKED]",
  "data: { $ref: '#/components/schemas/WarehouseStockPage' }",
]) {
  if (!source.includes(fragment)) throw new Error(`库存管理 OpenAPI 缺少：${fragment}`);
}
if (!warehouseStockViewSource.includes('库存变更请通过出入库或库存调整业务完成')
  || !warehouseStockViewSource.includes('<ListFilterPanel layout="content"')
  || !warehouseStockViewSource.includes('data-filter-size="wide"')
  || !warehouseStockViewSource.includes('库存健康')
  || !warehouseStockViewSource.includes('占用情况')
  || !pageDesign.includes('## 15. 仓库库存模块：库存管理')) {
  throw new Error('库存管理页面边界、响应式布局或页面设计文档不完整');
}
for (const fragment of [
  'StockBillType',
  'StockBillStatus',
  'StockBillDetail',
  'StockBillSummary',
  'normalizeNullableStringId',
  'normalizeStockBillItem',
  'StockBillEntryMode',
  'listStockBills',
  'getStockBillDetail',
  'createStockBill',
  'updateStockBill',
  'submitStockBill',
  'confirmStockBill',
  'cancelStockBill',
]) {
  if (!stockBillApiSource.includes(fragment) && !stockBillTypeSource.includes(fragment)) {
    throw new Error(`入库单/出库单前端契约缺少：${fragment}`);
  }
}
for (const fragment of [
  '按 `inbound_bill_item.inbound_bill_id` 聚合返回明细条数和入库量摘要',
  '按 `outbound_bill_item.outbound_bill_id` 聚合返回明细条数和出库量摘要',
  '入库列表主列固定显示供应商',
  '出库列表主列固定显示客户',
  '库存调整没有来源对象时不展示来源对象字段，且不自动生成反向入库单或出库单',
  'enum: [PURCHASE_IN, SALES_OUT, PURCHASE_RETURN, SALES_RETURN, ADJUST_IN, ADJUST_OUT]',
  'enum: [DRAFT, PENDING_CONFIRM, CONFIRMED, CANCELLED]',
  'enum: [SOURCE_GENERATED, MANUAL_SUPPLEMENT, MANUAL_ADJUSTMENT]',
  'name: entryMode',
  '对应 `inbound_bill.entry_mode`',
  '对应 `outbound_bill.entry_mode`',
  'required: [billType, sourceNo, warehouseId, manualReason, items, remark]',
  'responsibleById',
  'sourcePartyName',
  'quantitySummary',
  'totalCurrentQty',
  'planQty',
  'processedQty',
  'pendingQty',
  'quantityPrecision',
  "schema: { $ref: '#/components/schemas/StockBillCreateRequest' }",
  "schema: { $ref: '#/components/schemas/StockBillUpdateRequest' }",
  '仅允许 `DRAFT -> PENDING_CONFIRM`',
  '仅允许 `PENDING_CONFIRM -> CONFIRMED`',
  '仅允许 `DRAFT/PENDING_CONFIRM -> CANCELLED`',
  "data: { $ref: '#/components/schemas/StockBillPage' }",
  "data: { $ref: '#/components/schemas/StockBillDetail' }",
]) {
  if (!source.includes(fragment)) throw new Error(`入库单/出库单 OpenAPI 缺少：${fragment}`);
}
if (!stockBillViewSource.includes('新增入库单')
  || !stockBillViewSource.includes('新增出库单')
  || !stockBillViewSource.includes('手工补录')
  || !stockBillViewSource.includes('sourcePartyName')
  || !stockBillViewSource.includes('planQtyLabel')
  || !stockBillViewSource.includes('pendingQtyLabel')
  || !stockBillViewSource.includes('remainingAfterText')
  || !stockBillViewSource.includes('qualifiedQty')
  || !stockBillViewSource.includes('defectiveQty')
  || !stockBillViewSource.includes('responsibleByName')
  || !stockBillViewSource.includes('itemQuantityStep')
  || !stockBillViewSource.includes('openSubmitDetail(row)')
  || !stockBillViewSource.includes('openConfirmDetail(row)')
  || !stockBillViewSource.includes('handleConfirm(detail)')
  || !stockBillViewSource.includes('handleSubmit(detail)')
  || !stockBillViewSource.includes('handleCancel(row)')
  || !stockBillViewSource.includes('<ListFilterPanel layout="content"')
  || !stockBillViewSource.includes('data-filter-size="compact"')
  || !stockBillViewSource.includes('entryModeOptions')
  || !stockBillViewSource.includes('listQtyLabel')
  || !stockBillViewSource.includes('billTotalQuantityText')
  || !stockBillViewSource.includes('data-stock-bill-expanded-item-id')
  || !stockBillViewSource.includes('toggleRowDetail')
  || !stockBillViewSource.includes('submitStockBill')
  || !stockBillViewSource.includes('stock-bill-table-scroll')
  || !stockBillViewSource.includes('stock-bill-form-table-scroll')
  || !stockBillViewSource.includes('detail-field-grid')
  || !stockBillViewSource.includes('本次入库数量')
  || !stockBillViewSource.includes('本次出库数量')
  || !pageDesign.includes('## 16. 仓库库存模块：入库单与出库单')
  || !pageDesign.includes('DRAFT -> PENDING_CONFIRM')
  || !warehouseSchema.includes('CONFIRMED` 后不允许任何修改或取消')
  || !warehouseSchema.includes('100 倍整数存储')) {
  throw new Error('入库单/出库单新增、编辑、状态流转、详情或页面设计文档不完整');
}
for (const fragment of ['入库单号 / 商品', '类型 / 来源', '往来方 / 仓库', '状态 / 操作']) {
  if (stockBillViewSource.includes(fragment)) throw new Error(`入库单/出库单主表列不得混合字段：${fragment}`);
}
if (!stockBillViewSource.includes('entryModeOptions')
  || !source.includes('创建调整入库或补录采购入库、销售退货入库草稿')
  || !source.includes('创建调整出库或补录销售出库、采购退货出库草稿')
  || !warehouseSchema.includes('库存调整使用 `entry_mode=MANUAL_ADJUSTMENT`')) {
  throw new Error('库存调整功能合并到入库单/出库单页面的契约或设计文档不完整');
}
const pagedQueryCallPattern = /usePagedQuery\(\{\s*query,\s*busy:\s*queryBusy,\s*pending:\s*queryPending,\s*load:\s*[A-Za-z_$][\w$]*,\s*resetFilters:\s*\(\)\s*=>\s*\{[\s\S]*?\},\s*\}\)/;
const hasSharedListQuery = viewSource => viewSource.includes('useListRefresh(queryBusy, queryPending')
  || pagedQueryCallPattern.test(viewSource);
const hasResetLoading = viewSource => /function handleReset\(\) \{[\s\S]*?queryPending\.value = true;[\s\S]*?debouncedSearch\(\);[\s\S]*?\n\}/.test(viewSource)
  || pagedQueryCallPattern.test(viewSource);

if (!listRefreshSource.includes('useDebounceFn') || !listRefreshSource.includes('pending.value = true')
  || !pagedQuerySource.includes('useDebounceFn') || !pagedQuerySource.includes('pending.value = true')
  || !pagedQuerySource.includes('resetFilters()') || !pagedQuerySource.includes('useListRefresh(busy, pending, load, pageDelay)')
  || listViewSources.some(viewSource => !hasSharedListQuery(viewSource))) {
  throw new Error('已完成列表页未统一接入刷新防抖和即时加载状态');
}
if (listViewSources.some(viewSource => !hasResetLoading(viewSource))) {
  throw new Error('已完成列表页未统一接入重置防抖和即时加载状态');
}
for (const fragment of [
  '仓库编码创建后不可修改',
  '同步 `warehouse_stock.warehouse_name`',
  '存在 `warehouse_stock` 库存余额或入库单、出库单、库存流水时禁止删除',
]) {
  if (!source.includes(fragment) && !warehouseSchema.includes(fragment)) {
    throw new Error(`仓库管理缺少后端业务边界：${fragment}`);
  }
}
if (!source.includes('存在 `warehouse_stock` 库存余额或入库单、出库单、库存流水时返回 409')) {
  throw new Error('仓库删除接口缺少 409 Conflict 引用保护契约');
}
if (!warehouseSql.includes('UNIQUE KEY uk_warehouse_code (warehouse_code)')) {
  throw new Error('仓库表缺少仓库编码唯一索引');
}
if (!exclusiveDropdownSource.includes("erp:dropdown-open")
  || !anchoredSelectSource.includes('useExclusiveDropdown(open)')
  || !treeSelectSource.includes('useExclusiveDropdown(isOpen)')) {
  throw new Error('共享下拉组件未接入全局互斥机制');
}
for (const fragment of [
  '启用时后端必须校验产品所属分类仍为启用状态',
  '已被库存、采购或销售业务数据引用时返回 409',
  '任一产品已被库存、采购或销售业务数据引用时整批返回 409',
]) {
  if (!source.includes(fragment)) throw new Error(`OpenAPI 缺少产品档案业务约束：${fragment}`);
}

const updateRequestStart = source.indexOf('    SystemPermissionUpdateRequest:');
const updateRequestEnd = source.indexOf('    SystemPermissionStatusRequest:', updateRequestStart);
const updateRequest = source.slice(updateRequestStart, updateRequestEnd);
if (updateRequestStart < 0 || updateRequestEnd < 0 || updateRequest.includes('permissionCode:')) {
  throw new Error('权限更新请求不得包含创建后不可修改的 permissionCode');
}

const tableCount = [...allSql.matchAll(/^CREATE TABLE IF NOT EXISTS\s+/gm)].length;
if (tableCount !== 30) throw new Error(`数据库设计文档声明 30 张表，当前 DDL 实际为 ${tableCount} 张`);
if (!databaseOverview.includes('共设计并已落 DDL 30 张表') || !databaseOverview.includes('`sys_permission`')
  || !returnSchema.includes('表：return_order（退货单主表）')
  || !returnSchema.includes('表：return_order_item（退货单明细表）')) {
  throw new Error('数据库总览必须同步 30 张已落 DDL 的表，并包含 sys_permission 与两张退货表');
}
for (const [name, apiSource, typeSource, viewSource] of [
  ['仓库列表', warehouseApiSource, warehouseTypeSource, warehouseViewSource],
  ['库存余额', warehouseStockApiSource, warehouseStockTypeSource, warehouseStockViewSource],
]) {
  if (!typeSource.includes('total: number | null') || !typeSource.includes('hasNext?: boolean')) {
    throw new Error(`${name}前端类型未保留可空 total 和可选 hasNext`);
  }
  if (!apiSource.includes("typeof page.hasNext === 'boolean'") || !apiSource.includes('page.total === null')) {
    throw new Error(`${name}接口适配层丢失 total/hasNext 契约字段`);
  }
  if (!viewSource.includes('hasNext ??') || !viewSource.includes('records.length >=')) {
    throw new Error(`${name}页面未优先使用后端 hasNext`);
  }
}
const paginationContractCases = [
  { name: '满页但后端确认无下一页', page: { records: Array(10), pageSize: 10, hasNext: false }, expected: false },
  { name: '总数为空且后端确认有下一页', page: { records: Array(10), pageSize: 10, total: null, hasNext: true }, expected: true },
  { name: '后端未返回 hasNext 时按满页降级', page: { records: Array(10), pageSize: 10 }, expected: true },
];
for (const testCase of paginationContractCases) {
  const actual = testCase.page.hasNext ?? testCase.page.records.length >= testCase.page.pageSize;
  if (actual !== testCase.expected) throw new Error(`分页契约用例失败：${testCase.name}`);
}
for (const forbidden of [
  'buildWorkbenchFromMessage',
  'createPurchaseOrder',
  'createStockBill',
  '1920000000000000007',
  "content.includes('采购')",
  "content.includes('调拨')",
]) {
  if (aiViewSource.includes(forbidden)) throw new Error(`AI 生产组件仍包含前端业务数据推断：${forbidden}`);
}
if (!aiApiSource.includes("import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true'")) {
  throw new Error('AI Mock 未受 DEV 环境边界保护');
}
if (!aiApiSource.includes('/ai/assistant/conversations/${conversationId}/messages')
  || !aiApiSource.includes('signal')
  || !aiViewSource.includes('historySequence')
  || !aiViewSource.includes('message.workbench')) {
  throw new Error('AI 非 Mock 历史请求、竞态保护或结构化工作框消费未完整接入');
}

const stalePermissionDescriptions = [
  /没有设计[^\n]*sys_permission/,
  /暂不设计[^\n]*独立权限表/,
  /后续[^\n]*新增[^\n]*sys_permission/,
];
for (const document of [databaseOverview, permissionSchema, projectPlan]) {
  if (stalePermissionDescriptions.some(pattern => pattern.test(document))) {
    throw new Error('项目文档仍残留权限目录表的旧设计口径');
  }
}

console.log(`OPENAPI_OK: ${references.length} 个引用完整，30 张表已落 DDL，系统权限、退货、产品与仓库库存契约已对齐`);
