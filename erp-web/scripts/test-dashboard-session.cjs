const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const { createPinia, setActivePinia } = require('pinia');

const root = path.resolve(__dirname, '..');
function loadSource(relative, dependencies, extra = '') {
  const source = fs.readFileSync(path.join(root, relative), 'utf8')
    .replace("const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';", 'const useMockApi = false;')
    .replace("import.meta.env.VITE_API_BASE_URL", "'/api'");
  const output = ts.transpileModule(source + extra, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  new Function('exports', 'require', 'module', output)(module.exports,
    id => dependencies[id] || require(id), module);
  return module.exports;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function main() {
  setActivePinia(createPinia());
  let calls = 0;
  let next = deferred();
  const storeModule = loadSource('src/stores/dashboardOverviewStore.ts', {
    '@/modules/dashboard/api': { getDashboardOverview: () => { calls++; return next.promise; } },
    '@/api/http': { getApiErrorMessage: error => error.message },
  });
  const store = storeModule.useDashboardOverviewStore();
  const first = store.refresh();
  const joined = store.refresh(true);
  assert.equal(calls, 1, '布局与工作台并发加载应共用同一请求');
  assert.equal(store.loading, true);
  next.resolve({ pendingCount: 1, todos: [] });
  assert.equal(await first, true);
  assert.equal(await joined, true);
  assert.equal(await store.refresh(), false, '正常结果应受60秒节流');
  assert.equal(calls, 1);

  next = deferred();
  const failed = store.refresh(true);
  const rejected = assert.rejects(failed, /刷新失败/);
  next.reject(new Error('刷新失败'));
  await rejected;
  assert.equal(store.overview.pendingCount, 1, '刷新失败保留旧数据');
  assert.equal(store.refreshError, '刷新失败');
  assert.equal(store.loading, false);

  // 旧会话成功响应与 finally 都不能改变新会话的请求状态。
  next = deferred();
  const oldSuccess = next;
  const oldRequest = store.refresh(true);
  store.reset();
  assert.equal(store.overview, null);
  next = deferred();
  const newSuccess = next;
  const newRequest = store.refresh(true);
  oldSuccess.resolve({ pendingCount: 999 });
  assert.equal(await oldRequest, false);
  assert.equal(store.loading, true);
  assert.equal(store.overview, null);
  newSuccess.resolve({ pendingCount: 2 });
  assert.equal(await newRequest, true);
  assert.equal(store.overview.pendingCount, 2);

  next = deferred();
  const oldFailure = next;
  const oldFailedRequest = store.refresh(true);
  store.reset();
  next = deferred();
  const current = store.refresh(true);
  oldFailure.reject(new Error('旧账号错误'));
  assert.equal(await oldFailedRequest, false);
  assert.equal(store.refreshError, null);
  assert.equal(store.loading, true);
  next.resolve({ pendingCount: 3 });
  await current;

  const values = new Map();
  global.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  const constants = loadSource('src/shared/constants/storage.ts', {});
  const logout = deferred();
  const authModule = loadSource('src/modules/auth/stores/authStore.ts', {
    '@/shared/constants/storage': constants,
    '@/stores/dashboardOverviewStore': storeModule,
    '../api': {
      loginApi: async () => ({ token: 'test-session', tokenName: 'satoken', user: { username: 'test' } }),
      logoutApi: () => logout.promise,
    },
  });
  const auth = authModule.useAuthStore();
  await auth.login({ username: 'test', password: 'test' });
  assert.equal(store.overview, null, '登录成功清除旧账号缓存');
  store.overview = { pendingCount: 4 };
  const loggingOut = auth.logout();
  assert.equal(store.overview, null, '注销HTTP等待期间已清空缓存');
  logout.resolve();
  await loggingOut;
  assert.equal(auth.token, '');

  // 运行真实 HTTP 拦截器，验证旧会话401不能注销新账号。
  let onRequest;
  let onError;
  let redirects = 0;
  global.window = { location: { set href(value) { redirects++; } } };
  const axios = {
    create: () => ({ defaults: { timeout: 15000 }, interceptors: {
      request: { use: callback => { onRequest = callback; } },
      response: { use: (success, failure) => { onError = failure; } },
    } }),
  };
  loadSource('src/api/http.ts', {
    axios: { default: axios },
    'vue-sonner': { toast: { error: () => undefined } },
    '@/shared/constants/storage': constants,
    '@/shared/utils/page-loading': { beginPageLoading: () => () => undefined },
  });
  values.set(constants.AUTH_TOKEN_STORAGE_KEY, 'old-session');
  const config = onRequest({ method: 'GET', headers: { set: () => undefined }, suppressErrorToast: true });
  values.set(constants.AUTH_TOKEN_STORAGE_KEY, 'new-session');
  await assert.rejects(onError({ config, response: { status: 401 }, message: 'unauthorized' }));
  assert.equal(values.get(constants.AUTH_TOKEN_STORAGE_KEY), 'new-session');
  assert.equal(redirects, 0);
  config.sessionToken = 'new-session';
  await assert.rejects(onError({ config, response: { status: 401 }, message: 'unauthorized' }));
  assert.equal(values.has(constants.AUTH_TOKEN_STORAGE_KEY), false);
  assert.equal(redirects, 1);

  const { normalizeMetric } = loadSource('src/modules/dashboard/api.ts', {
    '@/api/http': { getResult: () => Promise.reject(new Error('不应访问网络')) },
    '@/shared/utils/api-normalizers': loadSource('src/shared/utils/api-normalizers.ts', {}),
    '@/shared/utils/money': {},
    '@/shared/utils/qty': {},
  }, '\nexport { normalizeMetric };');
  const metric = { key: 'MONTH_SALES', label: '本月销售额', value: 100, unit: '元',
    changeRate: null, compareText: null, status: 'neutral', comparisonState: 'UNAVAILABLE' };
  assert.equal(normalizeMetric(metric).comparisonState, 'UNAVAILABLE');
  assert.equal(normalizeMetric({ ...metric, comparisonState: 'NO_BASELINE' }).value, 100);
  assert.equal(normalizeMetric({ ...metric, value: null, comparisonState: null }).value, null);
  assert.equal(normalizeMetric({ ...metric, changeRate: 0, comparisonState: 'AVAILABLE' }).changeRate, 0);
  for (const invalid of [
    { ...metric, comparisonState: undefined },
    { ...metric, comparisonState: 'AVAILABLE' },
    { ...metric, value: null },
    { ...metric, changeRate: 1 },
  ]) assert.throws(() => normalizeMetric(invalid), /状态|comparisonState/);

  console.log('UNIT_DASHBOARD_SESSION_OK: 并发合并、节流、刷新失败、换账号、旧401和指标对比契约均通过');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
