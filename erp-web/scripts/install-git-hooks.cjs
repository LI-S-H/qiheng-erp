const path = require('path');
const { execFileSync } = require('child_process');

const projectRoot = path.resolve(__dirname, '..', '..');
execFileSync('git', ['config', 'core.hooksPath', '.githooks'], {
  cwd: projectRoot,
  stdio: 'inherit',
});

console.log('GIT_HOOKS_OK: core.hooksPath 已设置为 .githooks');
