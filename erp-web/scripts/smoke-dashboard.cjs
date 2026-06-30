const { runSmoke } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/dashboard',
  screenshot: 'smoke-dashboard.png',
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
    await page.getByText('今日销售额').waitFor();
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
    if ((await page.locator('.dashboard-todo').count()) < 8) {
      throw new Error('业务待办主卡应展示至少 8 条摘要');
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

    const refreshButton = page.getByRole('button', { name: '刷新', exact: true });
    await refreshButton.click();
    await page.locator('[data-list-loading]').waitFor({ state: 'visible', timeout: 1000 });
    if (!(await refreshButton.isDisabled())) throw new Error('工作台刷新期间按钮未禁用');
    await page.locator('[data-list-loading]').waitFor({ state: 'hidden', timeout: 5000 });

    await page.getByRole('button', { name: /销售单待审核/ }).click();
    const quickTodoDialog = page.getByRole('dialog', { name: '业务待办详情' });
    await quickTodoDialog.getByText('销售单待审核').waitFor();
    await quickTodoDialog.getByText('处理对应业务后自动完成').first().waitFor();
    await quickTodoDialog.getByRole('button', { name: '查看详情' }).first().waitFor();
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
    await salesApproveTodo.getByText('SO202606004').waitFor();
    await salesApproveTodo.getByText('授信占用偏高').waitFor();

    const priceReviewTodo = todoDialog.locator('.dashboard-detail-todo').filter({ hasText: '采购价偏离参考价' });
    await priceReviewTodo.getByRole('button', { name: '查看详情' }).click();
    await priceReviewTodo.getByText('USB-C扩展坞（P000013）').waitFor();
    if ((await priceReviewTodo.getByText('参考采购价').count()) < 2) {
      throw new Error('采购价格复核应展示参考采购价证据指标');
    }
    if ((await priceReviewTodo.getByText('最近采购价').count()) < 2) {
      throw new Error('采购价格复核应展示最近采购价证据指标');
    }

    const creditReviewTodo = todoDialog.locator('.dashboard-detail-todo').filter({ hasText: '客户信用待复核' });
    await creditReviewTodo.getByRole('button', { name: '查看详情' }).click();
    await creditReviewTodo.getByText('杭州蓝湖办公采购（C002）').waitFor();
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

    console.log('SMOKE_OK: 工作台经营概览页面通过');
  },
});
