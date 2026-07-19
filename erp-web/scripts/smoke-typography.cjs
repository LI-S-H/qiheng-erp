const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'docs/qa-screenshots/2026-07-19-harmonyos-font',
);

fs.mkdirSync(screenshotDirectory, { recursive: true });

async function assertTypography(page) {
  await page.getByRole('heading', { name: '入库单' }).waitFor();
  await page.waitForLoadState('networkidle').catch(() => undefined);
  const fontLoadError = await page.evaluate(async () => {
    await document.fonts.ready;
    try {
      await document.fonts.load('400 14px "HarmonyOS Sans SC"', '库存管理 123456');
      return null;
    } catch (error) {
      return error instanceof Error ? error.message : String(error);
    }
  });
  await page.waitForTimeout(250);

  const state = await page.evaluate((loadError) => {
    const pageTitle = document.querySelector('.page-title');
    const summaryValue = document.querySelector('.summary-item dd');
    const table = document.querySelector('[data-slot="table"]');
    const bodyStyle = getComputedStyle(document.body);
    const titleStyle = pageTitle ? getComputedStyle(pageTitle) : null;
    const summaryStyle = summaryValue ? getComputedStyle(summaryValue) : null;

    return {
      fontReady: document.fonts.check('400 14px "HarmonyOS Sans SC"', '库存管理 123456'),
      fontLoadError: loadError,
      fontFaces: Array.from(document.fonts)
        .filter(fontFace => fontFace.family.includes('HarmonyOS Sans SC'))
        .map(fontFace => ({ status: fontFace.status, weight: fontFace.weight, style: fontFace.style })),
      bodyFontFamily: bodyStyle.fontFamily,
      bodyFontWeight: bodyStyle.fontWeight,
      bodyNumericVariant: bodyStyle.fontVariantNumeric,
      titleFontFamily: titleStyle?.fontFamily,
      titleFontWeight: titleStyle?.fontWeight,
      summaryFontWeight: summaryStyle?.fontWeight,
      fontResourceLoaded: performance.getEntriesByType('resource')
        .some(entry => entry.name.includes('HarmonyOS_Sans_SC') && entry.name.includes('.ttf')),
      fontResourceCount: performance.getEntriesByType('resource')
        .filter(entry => entry.name.includes('HarmonyOS_Sans_SC') && entry.name.includes('.ttf')).length,
      tablePresent: Boolean(table),
      viewportWidth: document.documentElement.clientWidth,
      documentWidth: document.documentElement.scrollWidth,
    };
  }, fontLoadError);

  const expectedFontWeights = ['400', '500', '600 700'];
  const loadedFontWeights = state.fontFaces
    .filter(fontFace => fontFace.status === 'loaded')
    .map(fontFace => fontFace.weight);
  if (state.fontLoadError || !state.fontReady || !state.fontResourceLoaded
    || !expectedFontWeights.every(weight => loadedFontWeights.includes(weight))) {
    throw new Error(`HarmonyOS Sans SC 字体资源未完成加载：${JSON.stringify(state)}`);
  }
  if (!state.bodyFontFamily.includes('HarmonyOS Sans SC') || state.bodyFontWeight !== '400') {
    throw new Error(`正文没有使用预期字体和字重：${JSON.stringify(state)}`);
  }
  if (state.titleFontWeight !== '650' || state.summaryFontWeight !== '650') {
    throw new Error(`标题或核心数字字重层级不正确：${JSON.stringify(state)}`);
  }
  if (state.bodyNumericVariant !== 'tabular-nums') {
    throw new Error(`业务数字未启用等宽数字：${JSON.stringify(state)}`);
  }
  if (!state.tablePresent || state.documentWidth > state.viewportWidth + 1) {
    throw new Error(`字体替换后页面或表格布局异常：${JSON.stringify(state)}`);
  }
}

async function run() {
  await runSmoke({
    route: '/warehouse/inbound-bills',
    viewport: { width: 1440, height: 900 },
    screenshot: path.join(screenshotDirectory, 'inbound-bills-1440x900.png'),
    test: assertTypography,
  });

  await runSmoke({
    route: '/warehouse/inbound-bills',
    viewport: { width: 1115, height: 838 },
    screenshot: path.join(screenshotDirectory, 'inbound-bills-1115x838.png'),
    test: assertTypography,
  });

  console.log('SMOKE_OK: HarmonyOS Sans SC 字体加载、字重层级、等宽数字和页面宽度通过');
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
