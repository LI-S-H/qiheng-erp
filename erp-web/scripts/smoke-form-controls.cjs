const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'docs/qa-screenshots/2026-07-19-shared-form-controls',
);

fs.mkdirSync(screenshotDirectory, { recursive: true });

async function assertSharedFormControls(page, {
  heading,
  filterLabel,
  actionButtonLabel,
  screenshotPrefix,
  minimumComboboxes = 1,
  verifyRemoteOptions = false,
}) {
  await page.getByRole('heading', { name: heading }).waitFor();
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(250);

  const filter = page.getByRole('search', { name: filterLabel });
  const state = await filter.evaluate((element, expectedActionButtonLabel) => {
    const inputPaddingLeft = [...element.querySelectorAll('[data-slot="input"]')]
      .map(input => getComputedStyle(input).paddingLeft);
    const comboboxes = [...element.querySelectorAll('[data-slot="select-trigger"], [role="combobox"]')]
      .map(control => ({
        paddingLeft: getComputedStyle(control).paddingLeft,
        lineHeight: getComputedStyle(control).lineHeight,
      }));
    const addButton = [...document.querySelectorAll('[data-slot="button"]')]
      .find(button => button.textContent?.trim() === expectedActionButtonLabel);
    const filterActionIconCount = element.querySelectorAll('[data-list-filter-actions] [data-slot="button"] svg').length;
    const filterActionButtons = [...element.querySelectorAll('[data-list-filter-actions] [data-slot="button"]')]
      .map(button => ({
        height: getComputedStyle(button).height,
        fontSize: getComputedStyle(button).fontSize,
        lineHeight: getComputedStyle(button).lineHeight,
        borderRadius: getComputedStyle(button).borderRadius,
      }));

    return {
      inputPaddingLeft,
      comboboxes,
      filterActionIconCount,
      filterActionButtons,
      addButton: addButton ? {
        alignItems: getComputedStyle(addButton).alignItems,
        justifyContent: getComputedStyle(addButton).justifyContent,
        height: getComputedStyle(addButton).height,
        fontSize: getComputedStyle(addButton).fontSize,
        lineHeight: getComputedStyle(addButton).lineHeight,
        fontWeight: getComputedStyle(addButton).fontWeight,
        borderRadius: getComputedStyle(addButton).borderRadius,
        paddingTop: getComputedStyle(addButton).paddingTop,
        paddingBottom: getComputedStyle(addButton).paddingBottom,
      } : null,
    };
  }, actionButtonLabel);

  if (!state.inputPaddingLeft.length || state.inputPaddingLeft.some(value => value !== '11px')) {
    throw new Error(`筛选输入框左内边距未统一校正：${JSON.stringify(state)}`);
  }
  if (state.comboboxes.length < minimumComboboxes
    || state.comboboxes.some(control => control.paddingLeft !== '11px' || control.lineHeight === 'normal')) {
    throw new Error(`筛选下拉触发器对齐异常：${JSON.stringify(state)}`);
  }
  if (state.filterActionIconCount !== 0) {
    throw new Error(`查询和重置按钮不应保留常态前置图标：${JSON.stringify(state)}`);
  }
  if (state.filterActionButtons.length !== 2
    || state.filterActionButtons.some(button => button.height !== '32px'
      || button.fontSize !== state.addButton?.fontSize
      || button.lineHeight !== state.addButton?.lineHeight
      || button.borderRadius !== state.addButton?.borderRadius)) {
    throw new Error(`查询和重置按钮未与工具栏操作按钮统一尺寸：${JSON.stringify(state)}`);
  }
  if (!state.addButton
    || state.addButton.alignItems !== 'center'
    || state.addButton.justifyContent !== 'center'
    || Number.parseFloat(state.addButton.lineHeight) <= Number.parseFloat(state.addButton.fontSize)
    || state.addButton.paddingTop !== '2px'
    || state.addButton.paddingBottom !== '0px'
    || Number(state.addButton.fontWeight) < 500) {
    throw new Error(`按钮文字未使用统一视觉居中规则：${JSON.stringify(state)}`);
  }

  const comboboxes = filter.locator('[role="combobox"]');
  for (let index = 0; index < await comboboxes.count(); index += 1) {
    const combobox = comboboxes.nth(index);
    const beforeHover = await combobox.evaluate(element => getComputedStyle(element).boxShadow);
    await combobox.hover();
    const afterHover = await combobox.evaluate(element => ({
      borderColor: getComputedStyle(element).borderColor,
      boxShadow: getComputedStyle(element).boxShadow,
    }));
    if (afterHover.boxShadow === 'none' || afterHover.boxShadow === beforeHover) {
      throw new Error(`下拉触发器悬停阴影未增强：${JSON.stringify({ index, beforeHover, afterHover })}`);
    }
  }
  const statusSelect = comboboxes.last();
  await statusSelect.hover();
  const viewportWidth = await page.evaluate(() => document.documentElement.clientWidth);
  await page.screenshot({
    path: path.join(screenshotDirectory, `${screenshotPrefix}-filters-hover-${viewportWidth}.png`),
    fullPage: true,
  });

  await statusSelect.click();
  const selectedOption = page.locator('[data-anchored-select-content] [data-slot="command-item"]').first();
  await selectedOption.waitFor();
  const selectedOptionStyle = await selectedOption.evaluate(element => ({
    boxShadow: getComputedStyle(element).boxShadow,
    transform: getComputedStyle(element).transform,
  }));
  if (selectedOptionStyle.boxShadow !== 'none' || selectedOptionStyle.transform !== 'none') {
    throw new Error(`已选下拉项不应保持悬停外观：${JSON.stringify(selectedOptionStyle)}`);
  }
  const option = page.locator('[data-anchored-select-content] [data-slot="command-item"]').nth(1);
  await option.waitFor();
  const optionBeforeHover = await option.evaluate(element => ({
    backgroundColor: getComputedStyle(element).backgroundColor,
    boxShadow: getComputedStyle(element).boxShadow,
  }));
  await option.hover();
  const optionAfterHover = await option.evaluate(element => ({
    backgroundColor: getComputedStyle(element).backgroundColor,
    boxShadow: getComputedStyle(element).boxShadow,
    transform: getComputedStyle(element).transform,
  }));
  if (optionAfterHover.boxShadow === 'none'
    || optionAfterHover.boxShadow === optionBeforeHover.boxShadow
    || optionAfterHover.backgroundColor === optionBeforeHover.backgroundColor
    || optionAfterHover.transform === 'none') {
    throw new Error(`下拉选项悬停反馈未生效：${JSON.stringify({ optionBeforeHover, optionAfterHover })}`);
  }
  await page.screenshot({
    path: path.join(screenshotDirectory, `${screenshotPrefix}-option-hover-${viewportWidth}.png`),
    fullPage: true,
  });
  await page.keyboard.press('Escape');

  if (verifyRemoteOptions) {
    const remoteCombobox = comboboxes.first();
    await remoteCombobox.click();
    const remoteOption = page.locator('[data-remote-search-select-content] [data-select-option]').first();
    await remoteOption.waitFor();
    if (await page.locator('[data-remote-search-select-content] .lucide-check').count() !== 0) {
      throw new Error('远程搜索下拉选项仍保留前置勾选图标');
    }
    const remoteBeforeHover = await remoteOption.evaluate(element => getComputedStyle(element).boxShadow);
    await remoteOption.hover();
    const remoteAfterHover = await remoteOption.evaluate(element => ({
      boxShadow: getComputedStyle(element).boxShadow,
      transform: getComputedStyle(element).transform,
    }));
    if (remoteAfterHover.boxShadow === 'none'
      || remoteAfterHover.boxShadow === remoteBeforeHover
      || remoteAfterHover.transform === 'none') {
      throw new Error(`远程搜索下拉选项悬停反馈未生效：${JSON.stringify({ remoteBeforeHover, remoteAfterHover })}`);
    }
    await page.keyboard.press('Escape');
  }
}

async function run() {
  await runSmoke({
    route: '/warehouse/warehouses',
    viewport: { width: 1440, height: 900 },
    screenshot: path.join(screenshotDirectory, 'warehouse-filters-1440x900.png'),
    test: page => assertSharedFormControls(page, {
      heading: '仓库管理',
      filterLabel: '仓库筛选',
      actionButtonLabel: '新增仓库',
      screenshotPrefix: 'warehouse',
    }),
  });

  await runSmoke({
    route: '/system/users',
    viewport: { width: 1440, height: 900 },
    screenshot: path.join(screenshotDirectory, 'users-button-center-1440x900.png'),
    test: page => assertSharedFormControls(page, {
      heading: '用户管理',
      filterLabel: '用户筛选',
      actionButtonLabel: '新增用户',
      screenshotPrefix: 'users',
      minimumComboboxes: 3,
    }),
  });

  await runSmoke({
    route: '/warehouse/inbound-bills',
    viewport: { width: 1440, height: 900 },
    screenshot: path.join(screenshotDirectory, 'inbound-bills-filters-1440x900.png'),
    test: page => assertSharedFormControls(page, {
      heading: '入库单',
      filterLabel: '入库单筛选',
      actionButtonLabel: '新增入库单',
      screenshotPrefix: 'inbound-bills',
      minimumComboboxes: 4,
      verifyRemoteOptions: true,
    }),
  });

  await runSmoke({
    route: '/warehouse/warehouses',
    viewport: { width: 1115, height: 838 },
    screenshot: path.join(screenshotDirectory, 'warehouse-filters-1115x838.png'),
    test: page => assertSharedFormControls(page, {
      heading: '仓库管理',
      filterLabel: '仓库筛选',
      actionButtonLabel: '新增仓库',
      screenshotPrefix: 'warehouse',
    }),
  });

  console.log('SMOKE_OK: 筛选输入、下拉悬停阴影和按钮视觉居中通过');
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
