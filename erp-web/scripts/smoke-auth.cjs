const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/auth-login',
);

fs.mkdirSync(screenshotDirectory, { recursive: true });

function durationToMilliseconds(value) {
  const normalized = value.trim();
  return normalized.endsWith('ms')
    ? Number.parseFloat(normalized)
    : Number.parseFloat(normalized) * 1000;
}

async function assertNoPageOverflow(page) {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  if (dimensions.scrollWidth > dimensions.clientWidth + 1) {
    throw new Error(`登录页发生横向溢出：${JSON.stringify(dimensions)}`);
  }
}

async function dispatchCapsLockState(passwordInput, enabled) {
  await passwordInput.evaluate((element, capsLockEnabled) => {
    const event = new KeyboardEvent('keydown', { bubbles: true, key: 'A' });
    Object.defineProperty(event, 'getModifierState', {
      value: key => key === 'CapsLock' && capsLockEnabled,
    });
    element.dispatchEvent(event);
  }, enabled);
}

async function run() {
  await runSmoke({
    route: '/login',
    autoLogin: false,
    viewport: { width: 1440, height: 900 },
    screenshot: path.join(screenshotDirectory, 'auth-login-normal.png'),
    async test(page) {
      await page.getByRole('heading', { name: '登录启衡 ERP' }).waitFor();
      await page.locator('[data-development-badge]').waitFor();
      await page.locator('[data-development-credentials]').waitFor();

      const usernameInput = page.getByLabel('登录账号');
      const passwordInput = page.getByLabel('登录密码');
      if (await usernameInput.inputValue() !== 'admin' || await passwordInput.inputValue() !== '123456') {
        throw new Error('开发环境演示账号没有按预期预填');
      }

      const shellMotion = await page.locator('.login-shell').evaluate(element => ({
        name: getComputedStyle(element).animationName,
        duration: getComputedStyle(element).animationDuration,
      }));
      if (shellMotion.name === 'none' || durationToMilliseconds(shellMotion.duration) < 100) {
        throw new Error(`普通模式登录入场动效异常：${JSON.stringify(shellMotion)}`);
      }

      const toggle = page.getByRole('button', { name: '显示密码' });
      const toggleHitState = await toggle.evaluate(element => {
        const rect = element.getBoundingClientRect();
        const hitTarget = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
        return {
          rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
          zIndex: getComputedStyle(element).zIndex,
          pointerEvents: getComputedStyle(element).pointerEvents,
          hitTag: hitTarget?.tagName,
          hitId: hitTarget?.id,
          hitIsToggle: hitTarget === element || Boolean(hitTarget && element.contains(hitTarget)),
        };
      });
      if (!toggleHitState.hitIsToggle) {
        throw new Error(`密码显隐按钮命中区域被遮挡：${JSON.stringify(toggleHitState)}`);
      }
      await toggle.click();
      if (await passwordInput.getAttribute('type') !== 'text') throw new Error('显示密码操作未生效');
      await page.getByRole('button', { name: '隐藏密码' }).click();
      if (await passwordInput.getAttribute('type') !== 'password') throw new Error('隐藏密码操作未生效');

      await passwordInput.focus();
      await dispatchCapsLockState(passwordInput, true);
      await page.locator('[data-caps-lock-hint]').waitFor();
      await dispatchCapsLockState(passwordInput, false);
      await page.locator('[data-caps-lock-hint]').waitFor({ state: 'detached' });

      await assertNoPageOverflow(page);
    },
  });

  await runSmoke({
    route: '/login?redirect=/product/products',
    autoLogin: false,
    reducedMotion: 'reduce',
    viewport: { width: 1280, height: 720 },
    screenshot: path.join(screenshotDirectory, 'auth-login-redirect-result.png'),
    async test(page) {
      await page.getByRole('heading', { name: '登录启衡 ERP' }).waitFor();

      const reducedDuration = await page.locator('.login-shell').evaluate(
        element => getComputedStyle(element).animationDuration,
      );
      if (durationToMilliseconds(reducedDuration) > 1) {
        throw new Error(`减少动态效果模式下登录动效未关闭：${reducedDuration}`);
      }

      const usernameInput = page.getByLabel('登录账号');
      const passwordInput = page.getByLabel('登录密码');
      await usernameInput.fill('');
      await passwordInput.fill('');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.getByText('请输入登录账号', { exact: true }).waitFor();
      await page.getByText('请输入登录密码', { exact: true }).waitFor();
      if (await usernameInput.getAttribute('aria-invalid') !== 'true'
        || await passwordInput.getAttribute('aria-invalid') !== 'true') {
        throw new Error('登录必填错误没有同步到 aria-invalid');
      }

      await usernameInput.fill('admin');
      await passwordInput.fill('wrong-password');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.locator('[data-login-error]').getByText('开发环境账号或密码错误', { exact: true }).waitFor();
      if (await page.getByRole('button', { name: '登录', exact: true }).isDisabled()) {
        throw new Error('登录失败后提交锁没有释放');
      }

      await passwordInput.fill('123456');
      await page.screenshot({
        path: path.join(screenshotDirectory, 'auth-login-reduced-motion.png'),
        fullPage: true,
      });
      await passwordInput.press('Enter');
      await page.waitForURL(url => url.pathname === '/product/products');
      await page.getByRole('heading', { name: '产品档案' }).waitFor();
      await assertNoPageOverflow(page);
    },
  });

  await runSmoke({
    route: '/login',
    autoLogin: false,
    serverMode: 'preview',
    viewport: { width: 1440, height: 900 },
    screenshot: path.join(screenshotDirectory, 'auth-login-production.png'),
    async test(page) {
      await page.getByRole('heading', { name: '登录启衡 ERP' }).waitFor();
      if (await page.locator('[data-development-badge]').count() !== 0
        || await page.locator('[data-development-credentials]').count() !== 0) {
        throw new Error('生产构建仍显示开发环境标识或演示账号');
      }
      await page.getByText('使用企业账号进入管理平台', { exact: true }).waitFor();
      if (await page.getByLabel('登录账号').inputValue() !== ''
        || await page.getByLabel('登录密码').inputValue() !== '') {
        throw new Error('生产构建不应预填登录凭据');
      }
      await assertNoPageOverflow(page);
    },
  });

  console.log('SMOKE_OK: 登录环境边界、表单反馈、密码操作、动效与重定向通过');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
