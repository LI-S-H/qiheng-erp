const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const projectRoot = path.resolve(__dirname, '..', '..');
const stampPath = path.join(projectRoot, '.codex', 'frontend-preflight.json');

const scopeConfig = {
  system: {
    database: ['docs/database/mvp-system-permission-schema.md'],
    openapiPrefixes: ['/auth/', '/system/'],
  },
  product: {
    database: ['docs/database/mvp-product-schema.md'],
    openapiPrefixes: ['/product/', '/products'],
  },
  purchase: {
    database: ['docs/database/mvp-purchase-schema.md'],
    openapiPrefixes: ['/purchase/'],
  },
  sales: {
    database: ['docs/database/mvp-sales-schema.md'],
    openapiPrefixes: ['/sales/'],
  },
  warehouse: {
    database: ['docs/database/mvp-warehouse-schema.md'],
    openapiPrefixes: ['/warehouse/'],
  },
  ai: {
    database: ['docs/database/mvp-ai-schema.md'],
    openapiPrefixes: ['/ai/'],
  },
  shared: {
    database: ['docs/database/mvp-database-design-overview.md'],
    openapiPrefixes: [],
  },
};

const requestedScopes = [...new Set(process.argv.slice(2).map(value => value.trim()).filter(Boolean))];
if (requestedScopes.length === 0) {
  console.error('请指定前端开发范围，例如：npm run preflight:frontend -- product');
  process.exit(1);
}

const scopes = requestedScopes.includes('all') ? Object.keys(scopeConfig) : requestedScopes;
const invalidScopes = scopes.filter(scope => !scopeConfig[scope]);
if (invalidScopes.length > 0) {
  console.error(`未知 scope：${invalidScopes.join(', ')}`);
  console.error(`可用 scope：${[...Object.keys(scopeConfig), 'all'].join(', ')}`);
  process.exit(1);
}

const commonFiles = [
  'docs/frontend-development-guide.md',
  'docs/frontend-style-guide.md',
  'docs/database/database-design-conventions.md',
  'docs/api/erp-openapi.yaml',
];
const databaseFiles = [...new Set(scopes.flatMap(scope => scopeConfig[scope].database))];
const files = [...commonFiles, ...databaseFiles];

const readFile = relativePath => fs.readFileSync(path.join(projectRoot, relativePath), 'utf8');
const printDocument = relativePath => {
  console.log(`\n===== ${relativePath} =====\n`);
  console.log(readFile(relativePath));
};

for (const relativePath of files.filter(file => file !== 'docs/api/erp-openapi.yaml')) {
  printDocument(relativePath);
}

const openapi = readFile('docs/api/erp-openapi.yaml');
const prefixes = [...new Set(scopes.flatMap(scope => scopeConfig[scope].openapiPrefixes))];
console.log('\n===== docs/api/erp-openapi.yaml 相关路径 =====\n');
if (prefixes.length === 0) {
  console.log('当前 scope 为公共前端能力，请在实际修改涉及业务接口时补充对应业务 scope。');
} else {
  const lines = openapi.split(/\r?\n/);
  let printing = false;
  let matched = false;
  for (const line of lines) {
    const pathMatch = line.match(/^  (\/[^:]+):\s*$/);
    if (pathMatch) {
      printing = prefixes.some(prefix => pathMatch[1].startsWith(prefix));
      matched ||= printing;
    } else if (/^[A-Za-z]/.test(line)) {
      printing = false;
    }
    if (printing) console.log(line);
  }
  if (!matched) console.log('未找到匹配路径，请检查 OpenAPI 路径前缀或手动阅读对应接口。');
}

const sha256 = content => crypto.createHash('sha256').update(content).digest('hex');
const documentHashes = Object.fromEntries(files.map(relativePath => [relativePath, sha256(readFile(relativePath))]));
const baseCommit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: projectRoot, encoding: 'utf8' }).trim();

fs.mkdirSync(path.dirname(stampPath), { recursive: true });
fs.writeFileSync(stampPath, `${JSON.stringify({
  version: 1,
  readAt: new Date().toISOString(),
  baseCommit,
  scopes,
  documents: documentHashes,
}, null, 2)}\n`, 'utf8');

console.log('\nFRONTEND_PREFLIGHT_OK');
console.log(`范围：${scopes.join(', ')}`);
console.log(`基准提交：${baseCommit}`);
console.log('已生成本地预检凭证；现在可以开始修改对应范围的前端代码。');
