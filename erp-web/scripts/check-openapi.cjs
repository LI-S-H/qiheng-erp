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
const sqlDirectory = path.join(projectRoot, 'docs', 'database', 'sql');
const allSql = fs.readdirSync(sqlDirectory)
  .filter(fileName => fileName.endsWith('.sql'))
  .map(fileName => fs.readFileSync(path.join(sqlDirectory, fileName), 'utf8'))
  .join('\n');

const requiredPaths = [
  '/auth/login:',
  '/auth/me:',
  '/system/users:',
  '/system/roles:',
  '/system/depts:',
  '/system/depts/batch/status:',
  '/system/permissions:',
  '/system/permissions/{permissionId}:',
  '/system/permissions/batch/status:',
  '/system/permissions/options:',
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
if (/Element Plus/i.test(pageDesign)) throw new Error('前端设计文档仍残留 Element Plus 技术选型');

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

const updateRequestStart = source.indexOf('    SystemPermissionUpdateRequest:');
const updateRequestEnd = source.indexOf('    SystemPermissionStatusRequest:', updateRequestStart);
const updateRequest = source.slice(updateRequestStart, updateRequestEnd);
if (updateRequestStart < 0 || updateRequestEnd < 0 || updateRequest.includes('permissionCode:')) {
  throw new Error('权限更新请求不得包含创建后不可修改的 permissionCode');
}

const tableCount = [...allSql.matchAll(/^CREATE TABLE IF NOT EXISTS\s+/gm)].length;
if (tableCount !== 21) throw new Error(`数据库设计文档声明 21 张表，当前 DDL 实际为 ${tableCount} 张`);
if (!databaseOverview.includes('共设计 21 张表') || !databaseOverview.includes('`sys_permission`')) {
  throw new Error('数据库总览未同步 21 张表或 sys_permission 权限目录表');
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

console.log(`OPENAPI_OK: ${references.length} 个引用完整，21 张数据库表及系统权限契约已对齐`);
