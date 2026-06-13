const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const projectRoot = path.resolve(__dirname, '..', '..');
const stampPath = path.join(projectRoot, '.codex', 'frontend-preflight.json');

const git = args => execFileSync('git', args, { cwd: projectRoot, encoding: 'utf8' }).trim();
const stagedFiles = git(['diff', '--cached', '--name-only', '--diff-filter=ACMR'])
  .split(/\r?\n/)
  .filter(Boolean)
  .map(file => file.replace(/\\/g, '/'));

const isFrontendRelevant = file => (
  file.startsWith('erp-web/')
  || file.startsWith('docs/frontend-')
  || file === 'docs/api/erp-openapi.yaml'
  || file.startsWith('docs/database/')
);
const relevantFiles = stagedFiles.filter(isFrontendRelevant);

if (relevantFiles.length === 0) {
  console.log('FRONTEND_PREFLIGHT_SKIP: 本次暂存内容不涉及前端');
  process.exit(0);
}

const requiredScopes = new Set();
let requiresSharedScope = false;
for (const file of relevantFiles) {
  if (file.startsWith('erp-web/src/modules/system/')) requiredScopes.add('system');
  else if (file.startsWith('erp-web/src/modules/product/')) requiredScopes.add('product');
  else if (file.startsWith('erp-web/src/modules/purchase/')) requiredScopes.add('purchase');
  else if (file.startsWith('erp-web/src/modules/sales/')) requiredScopes.add('sales');
  else if (file.startsWith('erp-web/src/modules/warehouse/')) requiredScopes.add('warehouse');
  else if (file.startsWith('erp-web/src/modules/ai/')) requiredScopes.add('ai');
  else if (file.includes('mvp-system-permission-schema')) requiredScopes.add('system');
  else if (file.includes('mvp-product-schema')) requiredScopes.add('product');
  else if (file.includes('mvp-purchase-schema')) requiredScopes.add('purchase');
  else if (file.includes('mvp-sales-schema')) requiredScopes.add('sales');
  else if (file.includes('mvp-warehouse-schema')) requiredScopes.add('warehouse');
  else if (file.includes('mvp-ai-schema')) requiredScopes.add('ai');
  else if (file.startsWith('erp-web/')) requiresSharedScope = true;
}
if (requiresSharedScope || requiredScopes.size === 0) requiredScopes.add('shared');

const fail = message => {
  console.error(`FRONTEND_PREFLIGHT_REQUIRED: ${message}`);
  console.error(`请先执行：cd erp-web && npm run preflight:frontend -- ${[...requiredScopes].join(' ')}`);
  process.exit(1);
};

if (!fs.existsSync(stampPath)) fail('未找到前端开发预检凭证');

let stamp;
try {
  stamp = JSON.parse(fs.readFileSync(stampPath, 'utf8'));
} catch {
  fail('前端开发预检凭证无法解析');
}

const currentHead = git(['rev-parse', 'HEAD']);
if (stamp.baseCommit !== currentHead) fail('凭证不属于当前 Git 提交');

const readAt = Date.parse(stamp.readAt);
const maxAgeMs = 8 * 60 * 60 * 1000;
if (!Number.isFinite(readAt) || Date.now() - readAt > maxAgeMs) fail('凭证已超过 8 小时有效期');

const stampScopes = new Set(stamp.scopes || []);
const missingScopes = [...requiredScopes].filter(scope => !stampScopes.has(scope));
if (missingScopes.length > 0) fail(`凭证未覆盖模块：${missingScopes.join(', ')}`);

const sha256 = content => crypto.createHash('sha256').update(content).digest('hex');
for (const [relativePath, expectedHash] of Object.entries(stamp.documents || {})) {
  const absolutePath = path.join(projectRoot, relativePath);
  if (!fs.existsSync(absolutePath)) fail(`已阅读文档被删除：${relativePath}`);
  const actualHash = sha256(fs.readFileSync(absolutePath, 'utf8'));
  if (actualHash !== expectedHash) fail(`已阅读文档发生变化：${relativePath}`);
}

for (const requiredDocument of [
  'docs/frontend-development-guide.md',
  'docs/frontend-style-guide.md',
  'docs/api/erp-openapi.yaml',
]) {
  if (!stamp.documents?.[requiredDocument]) fail(`凭证缺少必读文档：${requiredDocument}`);
}

console.log(`FRONTEND_PREFLIGHT_OK: 已覆盖 ${[...requiredScopes].join(', ')}，规范哈希与当前 HEAD 一致`);
