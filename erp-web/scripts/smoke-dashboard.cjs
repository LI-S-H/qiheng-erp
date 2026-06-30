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
    if (!firstTodoText.includes('库存扣减失败')) {
      throw new Error(`业务待办应优先展示高优异常任务，当前第一条为：${firstTodoText}`);
    }
    await page.getByRole('button', { name: /库存扣减失败/ }).waitFor();
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
    await quickTodoDialog.getByRole('button', { name: '前往完成' }).first().waitFor();
    await page.keyboard.press('Escape');
    await quickTodoDialog.waitFor({ state: 'hidden' });

    await page.getByRole('button', { name: '查看详情销售商品排行' }).click();
    const productDialog = page.getByRole('dialog', { name: '销售商品排行详情' });
    await productDialog.getByText('快干印台').waitFor();
    await page.keyboard.press('Escape');
    await productDialog.waitFor({ state: 'hidden' });

    await page.getByRole('button', { name: '查看详情业务待办' }).click();
    const todoDialog = page.getByRole('dialog', { name: '业务待办详情' });
    await todoDialog.getByText('外部同步异常').waitFor();
    await todoDialog.getByText('智能助手异常建议').waitFor();
    await todoDialog.getByText('STOCK_DEDUCT_TX_FAILED').waitFor();
    await todoDialog.getByText('USB-C扩展坞（P0013）').waitFor();
    if ((await todoDialog.getByText('参考采购价').count()) < 2) {
      throw new Error('采购价格复核应展示参考采购价证据指标');
    }
    if ((await todoDialog.getByText('最近采购价').count()) < 2) {
      throw new Error('采购价格复核应展示最近采购价证据指标');
    }
    await todoDialog.getByText('华北连锁零售（C003）').waitFor();
    if ((await todoDialog.getByText('授信额度').count()) < 2) {
      throw new Error('客户信用复核应展示授信额度证据指标');
    }
    if (await todoDialog.getByRole('button', { name: '前往处理' }).count()) {
      throw new Error('业务待办详情不应再展示前往处理跳转按钮');
    }
    if (await todoDialog.getByRole('button', { name: '自动完成' }).count()) {
      throw new Error('业务待办详情不应再展示自动完成按钮');
    }
    await todoDialog.getByRole('button', { name: '查看详情' }).first().waitFor();
    await todoDialog.getByRole('button', { name: '完成处理' }).first().waitFor();
    await page.keyboard.press('Escape');
    await todoDialog.waitFor({ state: 'hidden' });
    await page.waitForTimeout(600);

    console.log('SMOKE_OK: 工作台经营概览页面通过');
  },
});
