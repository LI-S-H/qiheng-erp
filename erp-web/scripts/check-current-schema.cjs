const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const sql = fs.readFileSync(path.join(root, 'docs/database/init/01-schema.sql'), 'utf8');
const schemaDir = path.join(root, 'docs/database/schema');
const documents = fs.readdirSync(schemaDir)
  .filter(name => name.endsWith('.md') && name !== 'README.md')
  .map(name => fs.readFileSync(path.join(schemaDir, name), 'utf8'))
  .join('\n');
const tablePattern = /CREATE\s+TABLE\s+`([^`]+)`\s*\(([\s\S]*?)\)\s*ENGINE\s*=[^;]*;/gi;
const tables = [...sql.matchAll(tablePattern)];
if (tables.length !== 37 || new Set(tables.map(match => match[1])).size !== 37) {
  throw new Error('当前初始化 SQL 应包含 37 张名称唯一的表');
}
if (/^\s*DROP\s+TABLE\b/im.test(sql)) throw new Error('空库初始化不得包含 DROP TABLE');
let count = 0;
for (const [, name, ddl] of tables) {
  const header = `## \`${name}\``;
  const start = documents.indexOf(header);
  const end = documents.indexOf('\n## `', start + header.length);
  if (start < 0) throw new Error(`数据库说明缺少表：${name}`);
  const section = documents.slice(start, end < 0 ? undefined : end);
  for (const line of ddl.split('\n')) {
    const field = /^\s*`([^`]+)`\s+/.exec(line);
    if (field) {
      count += 1;
      if (!section.includes(`| \`${field[1]}\` |`)) {
        throw new Error(`数据库说明缺少字段：${name}.${field[1]}`);
      }
      continue;
    }
    const constraint = line.trim().replace(/,$/, '');
    if (/^(PRIMARY KEY|(?:UNIQUE )?KEY|CONSTRAINT|FOREIGN KEY)\b/i.test(constraint)
      && !section.includes(constraint)) {
      throw new Error(`数据库说明缺少索引或约束：${name}`);
    }
  }
}
if (count !== 579) throw new Error(`字段数与当前基线不符：${count}`);
console.log(`CURRENT_SCHEMA_OK: ${tables.length} 张表、${count} 个字段及其索引约束均有文档说明`);
