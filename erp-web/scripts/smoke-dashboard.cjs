const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/dashboard',
);

fs.mkdirSync(screenshotDirectory, { recursive: true });

runSmoke({
  route: '/dashboard',
  screenshot: path.join(screenshotDirectory, 'dashboard-normal.png'),
  async test(page) {
    async function getVisibleTrendLabels(expectedMin = 1) {
      await page.waitForFunction(
        min => Array.from(document.querySelectorAll('.dashboard-trend-labels text'))
          .filter(node => getComputedStyle(node).display !== 'none').length >= min,
        expectedMin,
      );
      return page.locator('.dashboard-trend-labels text').evaluateAll(nodes =>
        nodes
          .filter(node => getComputedStyle(node).display !== 'none')
          .map(node => {
            const rect = node.getBoundingClientRect();
            return { left: rect.left, right: rect.right, text: node.textContent || '' };
          }),
      );
    }

    function assertTrendLabelsNotOverlap(labels, label) {
      for (let index = 1; index < labels.length; index += 1) {
        if (labels[index].left < labels[index - 1].right + 4) {
          throw new Error(`${label}经营趋势 x 轴标签重叠：${labels[index - 1].text} / ${labels[index].text}`);
        }
      }
    }

    function assertTrendLabelsEvenlySpaced(labels, label) {
      const centers = labels.map(item => (item.left + item.right) / 2);
      const gaps = centers.slice(1).map((center, index) => center - centers[index]);
      const maxGap = Math.max(...gaps);
      const minGap = Math.min(...gaps);
      if (maxGap - minGap > 4) {
        throw new Error(`${label}经营趋势 x 轴刻度间距不均匀：${gaps.map(item => item.toFixed(1)).join(', ')}`);
      }
    }

    await page.getByRole('heading', { name: '工作台' }).waitFor();
    const pageLoading = page.locator('[data-page-loading]');
    await pageLoading.waitFor({ state: 'visible' });
    const loadingMask = await pageLoading.evaluate(element => ({
      background: getComputedStyle(element).backgroundColor,
      opacity: getComputedStyle(element).opacity,
      zIndex: getComputedStyle(element).zIndex,
      before: getComputedStyle(element, '::before').content,
      after: getComputedStyle(element, '::after').content,
    }));
    if (loadingMask.background === 'rgba(0, 0, 0, 0)'
      || loadingMask.opacity !== '1'
      || loadingMask.zIndex !== '50'
      || loadingMask.before !== 'none'
      || loadingMask.after !== 'none') {
      throw new Error(`全局页面加载器必须使用单层不透明中性背景：${JSON.stringify(loadingMask)}`);
    }
    const dashboardSkeleton = page.locator('[data-dashboard-skeleton]');
    await dashboardSkeleton.waitFor({ state: 'visible' });
    if (await dashboardSkeleton.locator('.dashboard-panel').count() !== 6
      || await dashboardSkeleton.locator('.dashboard-skeleton__todo').count() !== 5) {
      throw new Error('工作台首次加载未保留完整的指标、待办与经营区块骨架');
    }
    await dashboardSkeleton.waitFor({ state: 'hidden' });
    await page.locator('[data-dashboard-refresh-status]').getByText(/更新于/).waitFor();
    await page.getByText('今日销售额').waitFor();
    const trendSummary = page.locator('[data-dashboard-trend-summary]');
    if (await trendSummary.locator(':scope > div').count() !== 4) {
      throw new Error('经营趋势应展示 4 项区间摘要');
    }
    await trendSummary.getByText('7日销售合计', { exact: true }).waitFor();
    if (await page.getByRole('button', { name: '7天' }).getAttribute('aria-pressed') !== 'true') {
      throw new Error('当前趋势周期没有通过 aria-pressed 标记');
    }
    await page.getByRole('img', { name: '近 7 日经营趋势' }).waitFor();
    if ((await page.locator('.dashboard-grid-lines line').count()) !== 5) {
      throw new Error('经营趋势应展示 5 条横向网格线');
    }
    if ((await page.locator('.dashboard-trend-points circle').count()) < 21) {
      throw new Error('经营趋势应为每个日期和指标标记折线点');
    }
    const trend7Labels = await getVisibleTrendLabels(7);
    assertTrendLabelsNotOverlap(trend7Labels, '7天');
    await page.getByRole('button', { name: '15天' }).click();
    await page.locator('.dashboard-trend-chart.is-transitioning').waitFor();
    await page.getByText('近 15 日销售、采购和毛利变化').waitFor();
    await trendSummary.getByText('15日销售合计', { exact: true }).waitFor();
    if (await page.getByRole('button', { name: '15天' }).getAttribute('aria-pressed') !== 'true'
      || await page.getByRole('button', { name: '7天' }).getAttribute('aria-pressed') !== 'false') {
      throw new Error('趋势周期切换后的 aria-pressed 状态错误');
    }
    const trend15Labels = await getVisibleTrendLabels(8);
    assertTrendLabelsNotOverlap(trend15Labels, '15天');
    await page.getByRole('button', { name: '30天' }).click();
    await page.locator('.dashboard-trend-chart.is-transitioning').waitFor();
    await page.getByText('近 30 日销售、采购和毛利变化').waitFor();
    const trend30Labels = await getVisibleTrendLabels(15);
    assertTrendLabelsNotOverlap(trend30Labels, '30天');
    assertTrendLabelsEvenlySpaced(trend30Labels, '30天');
    if (!(trend7Labels.length < trend15Labels.length && trend15Labels.length < trend30Labels.length)) {
      throw new Error(`经营趋势 x 轴标签数量应随天数递增，实际为 7天=${trend7Labels.length}, 15天=${trend15Labels.length}, 30天=${trend30Labels.length}`);
    }
    await page.getByRole('button', { name: '7天' }).click();
    await page.getByText('业务待办').waitFor();
    if ((await page.locator('.dashboard-todo').count()) < 7) {
      throw new Error('业务待办主卡应展示至少 7 条摘要');
    }
    const firstTodoText = await page.locator('.dashboard-todo').first().innerText();
    if (!firstTodoText.includes('系统异常')) {
      throw new Error(`业务待办应优先展示高优异常任务，当前第一条为：${firstTodoText}`);
    }
    await page.getByRole('button', { name: /系统异常/ }).waitFor();
    await page.getByText('库存预警').waitFor();
    if (await page.getByRole('button', { name: '查看详情订单流转' }).count()) {
      throw new Error('订单流转不应再展示详情按钮');
    }
    await page.getByRole('row').filter({ hasText: 'USB-C扩展坞' }).waitFor();
    await page.getByRole('button', { name: '查看详情库存预警' }).click();
    const stockDialog = page.getByRole('dialog', { name: '库存预警详情' });
    await stockDialog.getByText('USB-C扩展坞').waitFor();
    const stockHorizontalOverflow = await stockDialog.locator('.dashboard-detail-scroll').evaluate(element => element.scrollWidth - element.clientWidth);
    if (stockHorizontalOverflow > 2) throw new Error('库存预警详情不应出现横向滚动');
    await page.keyboard.press('Escape');
    await stockDialog.waitFor({ state: 'hidden' });

    const refreshButton = page.locator('.dashboard-heading-actions [data-slot="button"]');
    await refreshButton.click();
    await page.locator('[data-dashboard-refresh-status]').getByText('正在同步经营数据...', { exact: true }).waitFor();
    if (!(await refreshButton.isDisabled())) throw new Error('工作台刷新期间按钮未禁用');
    await page.locator('[data-dashboard-refresh-status]').getByText(/更新于/).waitFor();

    await page.getByRole('button', { name: /销售单待审核/ }).click();
    const quickTodoDialog = page.getByRole('dialog', { name: '销售单待审核详情' });
    await quickTodoDialog.locator('.dashboard-detail-todo').getByText('销售单待审核', { exact: true }).waitFor();
    if ((await quickTodoDialog.locator('.dashboard-detail-todo').count()) !== 1) {
      throw new Error('从业务待办单条摘要进入时，应只展示当前待办详情');
    }
    if (await quickTodoDialog.locator('.dashboard-detail-todo').filter({ hasText: '系统异常' }).count()) {
      throw new Error('从销售单待审核摘要进入时，不应展示系统异常等其他待办');
    }
    await quickTodoDialog.getByText('处理对应业务后自动完成').first().waitFor();
    await quickTodoDialog.getByRole('button', { name: '收起详情' }).first().waitFor();
    await quickTodoDialog.getByRole('button', { name: '前往完成' }).first().waitFor();
    await page.keyboard.press('Escape');
    await quickTodoDialog.waitFor({ state: 'hidden' });

    await page.getByRole('button', { name: '查看详情销售商品排行' }).click();
    const productDialog = page.getByRole('dialog', { name: '销售商品排行详情' });
    await productDialog.getByText('热敏标签纸').waitFor();
    await page.keyboard.press('Escape');
    await productDialog.waitFor({ state: 'hidden' });

    await page.getByRole('button', { name: '查看详情业务待办' }).click();
    const todoDialog = page.getByRole('dialog', { name: '业务待办详情' });
    const systemExceptionTodo = todoDialog.locator('.dashboard-detail-todo').filter({ hasText: '系统异常' });
    await systemExceptionTodo.locator('strong', { hasText: '系统异常' }).waitFor();
    await systemExceptionTodo.getByText('数据库记录').waitFor();
    await systemExceptionTodo.getByRole('button', { name: '查看详情' }).click();
    await systemExceptionTodo.getByText('AI-MCP-20260701-001').waitFor();
    await systemExceptionTodo.getByText('DLQ-ORDER-STOCK-00023').waitFor();
    await systemExceptionTodo.getByText('MCP超时').waitFor();
    const evidenceCollapseTransition = await systemExceptionTodo.locator('.dashboard-detail-evidence-collapse').evaluate(element => {
      const style = window.getComputedStyle(element);
      return {
        property: style.transitionProperty,
        duration: style.transitionDuration,
      };
    });
    if (
      !evidenceCollapseTransition.property.includes('grid-template-rows')
      || !evidenceCollapseTransition.property.includes('margin-top')
      || !evidenceCollapseTransition.property.includes('opacity')
      || !evidenceCollapseTransition.property.includes('transform')
    ) {
      throw new Error(`业务待办详情展开应包含折叠缓动：${JSON.stringify(evidenceCollapseTransition)}`);
    }
    const systemExceptionLayout = await todoDialog.locator('.dashboard-detail-scroll').evaluate(scroll => {
      const dialog = scroll.closest('[role="dialog"]');
      const todo = scroll.querySelector('.dashboard-detail-todo--system');
      const dialogRect = dialog.getBoundingClientRect();
      const scrollRect = scroll.getBoundingClientRect();
      const todoRect = todo.getBoundingClientRect();
      return {
        scrollOverflow: scroll.scrollWidth - scroll.clientWidth,
        scrollRightGap: dialogRect.right - scrollRect.right,
        todoRightOverflow: todoRect.right - scrollRect.right,
      };
    });
    if (systemExceptionLayout.scrollOverflow > 2 || systemExceptionLayout.todoRightOverflow > 2 || systemExceptionLayout.scrollRightGap < -2) {
      throw new Error(`系统异常展开详情不应向右撑开弹窗：${JSON.stringify(systemExceptionLayout)}`);
    }
    if (await systemExceptionTodo.getByRole('button', { name: '完成处理' }).count()) {
      throw new Error('系统异常来自数据库记录，不应在工作台展示完成处理按钮');
    }

    const salesApproveTodo = todoDialog.locator('.dashboard-detail-todo').filter({ hasText: '销售单待审核' });
    await salesApproveTodo.getByRole('button', { name: '查看详情' }).click();
    await salesApproveTodo.getByText('SO202607004').waitFor();
    await salesApproveTodo.getByText('审核前需复核客户信用').waitFor();

    const creditReviewTodo = todoDialog.locator('.dashboard-detail-todo').filter({ hasText: '客户信用待复核' });
    await creditReviewTodo.getByRole('button', { name: '查看详情' }).click();
    await creditReviewTodo.getByText('杭州蓝湖办公采购（C002）').waitFor();
    await creditReviewTodo.getByText('授信占用偏高').waitFor();
    if ((await creditReviewTodo.getByText('授信额度').count()) < 2) {
      throw new Error('客户信用复核应展示授信额度证据指标');
    }
    if (await todoDialog.getByRole('button', { name: '前往处理' }).count()) {
      throw new Error('业务待办详情不应再展示前往处理跳转按钮');
    }
    if (await todoDialog.getByRole('button', { name: '自动完成' }).count()) {
      throw new Error('业务待办详情不应再展示自动完成按钮');
    }
    await todoDialog.getByRole('button', { name: '查看详情' }).first().waitFor();
    await page.keyboard.press('Escape');
    await todoDialog.waitFor({ state: 'hidden' });
    await page.waitForTimeout(600);

  },
}).then(() => runSmoke({
  route: '/dashboard',
  reducedMotion: 'reduce',
  viewport: { width: 1280, height: 720 },
  screenshot: path.join(screenshotDirectory, 'dashboard-reduced-motion.png'),
  async test(page) {
    await page.getByRole('heading', { name: '工作台' }).waitFor();
    await page.getByRole('button', { name: '15天' }).click();
    await page.getByText('近 15 日销售、采购和毛利变化').waitFor();
    const summaryMotion = await page.locator('[data-dashboard-trend-summary]').evaluate(element => ({
      name: getComputedStyle(element).animationName,
      duration: getComputedStyle(element).animationDuration,
    }));
    const duration = summaryMotion.duration.endsWith('ms')
      ? Number.parseFloat(summaryMotion.duration)
      : Number.parseFloat(summaryMotion.duration) * 1000;
    if (duration > 1) throw new Error(`减少动态效果模式下趋势摘要仍有长动画：${JSON.stringify(summaryMotion)}`);

    await page.locator('.dashboard-todo').first().click();
    await page.getByRole('dialog').waitFor();
    await page.keyboard.press('Escape');
    await page.getByRole('dialog').waitFor({ state: 'hidden' });

    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await page.locator('[data-dashboard-refresh-status]').getByText('正在同步经营数据...', { exact: true }).waitFor();
    await page.locator('[data-dashboard-refresh-status]').getByText(/更新于/).waitFor();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`1280px 工作台发生横向溢出：${overflow}`);
  },
})).then(() => {
  console.log('SMOKE_OK: 工作台指标层级、趋势切换、待办下钻、刷新反馈与减弱动效通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
