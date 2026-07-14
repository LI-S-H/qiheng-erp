const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/ai-assistant-tasks',
);
const screenshotPath = filename => path.join(screenshotDirectory, filename);

fs.mkdirSync(screenshotDirectory, { recursive: true });

function durationToMilliseconds(value) {
  return value.split(',').reduce((maximum, item) => {
    const duration = item.trim().endsWith('ms')
      ? Number.parseFloat(item)
      : Number.parseFloat(item) * 1000;
    return Math.max(maximum, duration);
  }, 0);
}

async function observeNextTransition(page, selector) {
  await page.evaluate((targetSelector) => {
    window.__aiObservedTransition = null;
    window.__aiTransitionObserver?.disconnect();
    const observer = new MutationObserver(() => {
      const element = document.querySelector(targetSelector);
      if (!element) return;
      window.__aiObservedTransition = getComputedStyle(element).transitionDuration;
      observer.disconnect();
    });
    observer.observe(document.body, { attributes: true, childList: true, subtree: true });
    window.__aiTransitionObserver = observer;
  }, selector);
}

async function readObservedTransition(page, label) {
  await page.waitForFunction(() => Boolean(window.__aiObservedTransition), undefined, { timeout: 2000 });
  const duration = await page.evaluate(() => window.__aiObservedTransition);
  if (!duration) throw new Error(`未观察到${label}动效`);
  return durationToMilliseconds(duration);
}

async function assertExpandedChartCenteredInContent(page, label) {
  const geometry = await page.locator('[data-slot="dialog-content"]').evaluate((dialog) => {
    const main = document.querySelector('main');
    if (!main) throw new Error('未找到应用主内容区');

    const mainRect = main.getBoundingClientRect();
    const dialogRect = dialog.getBoundingClientRect();
    return {
      mainCenterX: mainRect.left + mainRect.width / 2,
      mainCenterY: mainRect.top + mainRect.height / 2,
      dialogCenterX: dialogRect.left + dialogRect.width / 2,
      dialogCenterY: dialogRect.top + dialogRect.height / 2,
      dialogLeft: dialogRect.left,
      dialogRight: dialogRect.right,
      mainLeft: mainRect.left,
      mainRight: mainRect.right,
    };
  });
  const deltaX = Math.abs(geometry.dialogCenterX - geometry.mainCenterX);
  const deltaY = Math.abs(geometry.dialogCenterY - geometry.mainCenterY);
  if (deltaX > 1 || deltaY > 1 || geometry.dialogLeft < geometry.mainLeft - 1 || geometry.dialogRight > geometry.mainRight + 1) {
    throw new Error(`${label}未按主内容区居中：${JSON.stringify({ ...geometry, deltaX, deltaY })}`);
  }
}

async function smokeAssistant() {
  await runSmoke({
    route: '/ai/assistant',
    screenshot: screenshotPath('ai-assistant-normal.png'),
    async test(page) {
      await page.locator('.ai-chat-stage').waitFor();
      await page.locator('.ai-composer').waitFor();

      if (await page.locator('.ai-chat-stage').getAttribute('aria-busy') !== 'false') {
        throw new Error('AI 助手初始状态不应标记为忙碌');
      }
      if (await page.locator('.ai-chat-stream').getAttribute('aria-live') !== 'polite') {
        throw new Error('AI 消息流缺少礼貌级动态播报');
      }
      const composerInput = page.getByLabel('经营问题');
      if (await composerInput.getAttribute('aria-describedby') !== 'ai-composer-hint') {
        throw new Error('AI 输入框缺少键盘操作提示关联');
      }
      const composerHint = await page.locator('#ai-composer-hint').innerText();
      if (!composerHint.includes('Enter 发送') || !composerHint.includes('Shift+Enter 换行')) {
        throw new Error(`AI 输入提示不完整：${composerHint}`);
      }

      const composer = await page.locator('.ai-composer').evaluate(element => {
        const rect = element.getBoundingClientRect();
        const dock = element.closest('[data-ai-composer-dock]');
        const dockRect = dock?.getBoundingClientRect();
        const stageRect = element.closest('.ai-chat-stage')?.getBoundingClientRect();
        const scrollRect = element.closest('.ai-chat-stage')?.querySelector('.ai-chat-scroll')?.getBoundingClientRect();
        const style = window.getComputedStyle(element);
        const dockStyle = dock ? window.getComputedStyle(dock) : null;
        return {
          height: rect.height,
          width: rect.width,
          dockBottomGap: dockRect ? Number((dockRect.bottom - rect.bottom).toFixed(1)) : -1,
          stageBottomGap: stageRect && dockRect ? Number((stageRect.bottom - dockRect.bottom).toFixed(1)) : -1,
          rowGap: dockRect && scrollRect ? Number((dockRect.top - scrollRect.bottom).toFixed(1)) : -1,
          dockBackground: dockStyle?.backgroundColor || '',
          boxShadow: style.boxShadow,
          parentClass: element.parentElement?.className || '',
          position: style.position,
          transform: style.transform,
        };
      });
      if (composer.height > 76
        || composer.width < 360
        || composer.dockBottomGap < 12
        || composer.dockBottomGap > 24
        || Math.abs(composer.stageBottomGap) > 1
        || Math.abs(composer.rowGap) > 1
        || composer.dockBackground === 'rgba(0, 0, 0, 0)'
        || composer.boxShadow !== 'none'
        || composer.position !== 'static'
        || composer.transform !== 'none'
        || !composer.parentClass.includes('ai-composer-dock')) {
        throw new Error(`AI composer layout invalid: ${JSON.stringify(composer)}`);
      }

      await page.locator('.ai-prompt-list__toggle').click();
      await page.waitForTimeout(220);
      const openPromptDrawers = await page.locator('.ai-prompt-list__drawer[data-state="open"]').count();
      if (openPromptDrawers !== 0) throw new Error(`Quick analysis drawer should be collapsed, open count=${openPromptDrawers}`);
      await page.locator('.ai-prompt-list__toggle').click();
      await page.locator('.ai-prompt-card').first().waitFor();

      const redundantCollapseButtons = await page.locator('.ai-conversation-more').filter({ hasText: '全部收起' }).count();
      if (redundantCollapseButtons !== 0) throw new Error(`Redundant all-collapse button should be removed, count=${redundantCollapseButtons}`);

      await page.locator('.ai-conversation-date').click();
      await page.locator('.ai-conversation-calendar button').first().waitFor();
      const conversationDateButtons = await page.locator('.ai-conversation-calendar button').count();
      if (conversationDateButtons > 1) {
        await page.locator('.ai-conversation-calendar button').nth(1).click();
        await page.locator('.ai-conversation-list [data-ai-fade-switch-content]').waitFor();
        await page.locator('.ai-conversation-calendar button').first().click();
        await page.locator('.ai-conversation-list [data-ai-fade-switch-content]').waitFor();
      }
      await page.keyboard.press('Escape');

      const extraConversationToggle = page.locator('.ai-conversation-more').first();
      if (await extraConversationToggle.count()) {
        await extraConversationToggle.click();
        await page.locator('.ai-conversation-extra__drawer[data-state="open"]').waitFor();
        const extraRows = await page.locator('.ai-conversation-extra__drawer .ai-conversation-row').count();
        if (extraRows < 1) throw new Error(`Expanded conversation drawer should reveal extra rows, count=${extraRows}`);
        const extraOpenOpacity = await page.locator('.ai-conversation-extra__drawer').evaluate(element => Number(window.getComputedStyle(element).opacity));
        if (extraOpenOpacity !== 1) throw new Error(`Extra conversation drawer should expand without opacity fade, opacity=${extraOpenOpacity}`);
        await extraConversationToggle.click();
        const extraCloseOpacity = await page.locator('.ai-conversation-extra__drawer').evaluate(element => Number(window.getComputedStyle(element).opacity));
        if (extraCloseOpacity !== 1) throw new Error(`Extra conversation drawer should collapse without opacity fade, opacity=${extraCloseOpacity}`);
        await page.locator('.ai-conversation-extra__drawer[data-state="closed"]').waitFor();
      }

      await page.locator('.ai-conversation-list__toggle').click();
      const rowsDuringClose = await page.locator('.ai-conversation-list__drawer .ai-conversation-row').count();
      if (rowsDuringClose < 1) throw new Error(`Conversation rows should remain mounted during collapse animation, count=${rowsDuringClose}`);
      const drawerOpacityDuringClose = await page.locator('.ai-conversation-list__drawer').evaluate(element => Number(window.getComputedStyle(element).opacity));
      if (drawerOpacityDuringClose !== 1) throw new Error(`Conversation drawer should collapse without opacity fade, opacity=${drawerOpacityDuringClose}`);
      await page.waitForTimeout(240);
      const closedConversationDrawer = await page.locator('.ai-conversation-list__drawer[data-state="closed"]').count();
      if (closedConversationDrawer !== 1) throw new Error(`Conversation drawer should be collapsed, count=${closedConversationDrawer}`);
      await page.locator('.ai-conversation-list__toggle').click();
      await page.locator('.ai-conversation-row').nth(1).waitFor();

      await page.locator('.ai-conversation-row').nth(1).click({ position: { x: 22, y: 22 } });
      await page.locator('.ai-chat-stage.is-switching').waitFor();
      const conversationTransitionDuration = await page.locator('.ai-chat-stream').evaluate(element => {
        const values = getComputedStyle(element).transitionDuration.split(',');
        return Math.max(...values.map(value => value.trim().endsWith('ms') ? Number.parseFloat(value) : Number.parseFloat(value) * 1000));
      });
      if (conversationTransitionDuration < 140 || conversationTransitionDuration > 300) {
        throw new Error(`会话切换动效时长异常：${conversationTransitionDuration}ms`);
      }
      await page.waitForTimeout(220);
      const switchingStageStillVisible = await page.locator('.ai-chat-stage.is-switching').count();
      if (switchingStageStillVisible !== 1) throw new Error(`Conversation switch transition should stay visible briefly, count=${switchingStageStillVisible}`);
      await page.locator('.ai-message-charts .ai-chart-card').first().waitFor();
      const visibleSources = await page.locator('.ai-source-strip:visible').count();
      if (visibleSources !== 0) throw new Error(`Source strip should be hidden, visible count=${visibleSources}`);

      await page.locator('.ai-chart-card__zoom').first().click();
      await page.locator('[data-ai-chart-expanded]').waitFor();
      await assertExpandedChartCenteredInContent(page, 'AI 助手放大图表');
      await page.locator('[data-slot="dialog-close"]').click();
      await page.locator('[data-ai-chart-expanded]').waitFor({ state: 'hidden' });

      const barRects = await page.locator('.ai-message-charts .ai-chart-card__bar-layer rect').count();
      if (barRects < 3) throw new Error(`Assistant bar chart should render bars, count=${barRects}`);
      const yLabels = await page.locator('.ai-message-charts .ai-chart-card__y-axis text').count();
      if (yLabels < 5) throw new Error(`Assistant charts should render y-axis labels, count=${yLabels}`);
      const xLabels = await page.locator('.ai-message-charts .ai-chart-card__x-axis text').count();
      if (xLabels < 6) throw new Error(`Assistant charts should render stable x-axis labels, count=${xLabels}`);
      const chartUnit = await page.locator('.ai-message-charts .ai-chart-card__unit').first().evaluate(element => element.textContent || '');
      if (!chartUnit.includes('单位')) throw new Error(`Assistant chart should render y-axis unit, got=${chartUnit}`);
      const legendText = await page.locator('.ai-message-charts .ai-chart-card__legend').first().innerText();
      if (!legendText.includes('USB-C扩展坞需求') || !legendText.includes('热敏标签纸需求')) {
        throw new Error(`Assistant line legend should use business labels, got=${legendText}`);
      }
      const baseline = await page.locator('.ai-message-charts .ai-chart-card__bar-layer rect').first().evaluate(rect => {
        const y = Number(rect.getAttribute('y'));
        const height = Number(rect.getAttribute('height'));
        return Number((y + height).toFixed(1));
      });
      if (Math.abs(baseline - 214) > 0.5) throw new Error(`Assistant bar chart baseline mismatch: ${baseline}`);

      await page.locator('.ai-workbench').waitFor();
      await page.locator('.ai-workbench-resizer').waitFor();

      await composerInput.fill('分析本周采购与库存风险');
      await composerInput.press('Enter');
      await page.getByRole('status').filter({ hasText: '正在分析' }).waitFor();
      if (await page.locator('.ai-chat-stage').getAttribute('aria-busy') !== 'true') {
        throw new Error('AI 分析期间消息区未标记忙碌状态');
      }
      const sendingButton = page.getByRole('button', { name: '正在分析', exact: true });
      if (!(await sendingButton.isDisabled())) throw new Error('AI 分析期间发送按钮未锁定');
      await page.getByRole('status').filter({ hasText: '正在分析' }).waitFor({ state: 'hidden', timeout: 5000 });
      if (await page.locator('.ai-chat-stage').getAttribute('aria-busy') !== 'false') {
        throw new Error('AI 回复完成后忙碌状态未恢复');
      }
      await page.getByText('分析本周采购与库存风险', { exact: true }).waitFor();

      const newConversationButton = page.getByRole('button', { name: '新建会话', exact: true });
      await newConversationButton.click();
      const creatingButton = page.getByRole('button', { name: '创建中', exact: true });
      await creatingButton.waitFor();
      if (!(await creatingButton.isDisabled())) throw new Error('新建会话期间按钮未锁定');
      await page.getByText('新会话已创建', { exact: true }).waitFor();
      await page.locator('.ai-chat-stage.is-switching').waitFor({ state: 'hidden', timeout: 3000 });
      const analysisConversation = page.locator('.ai-conversation-row').filter({ hasText: '补货建议讨论' }).first();
      await analysisConversation.click({ position: { x: 22, y: 22 } });
      await page.locator('.ai-chat-stage.is-switching').waitFor({ state: 'hidden', timeout: 3000 });
      await page.locator('.ai-message-charts .ai-chart-card').first().waitFor();
      await page.locator('[data-sonner-toast]').waitFor({ state: 'hidden', timeout: 6000 }).catch(() => undefined);

      console.log('SMOKE_OK: AI assistant page passed');
    },
  });
}

async function smokeTasksDesktopLayout() {
  await runSmoke({
    route: '/ai/tasks',
    viewport: { width: 1440, height: 900 },
    screenshot: screenshotPath('ai-tasks-layout-1440.png'),
    async test(page) {
      const state = await page.locator('.ai-task-table-wrap').evaluate(wrapper => {
        const container = wrapper.querySelector('[data-slot="table-container"]');
        const bodyCell = wrapper.querySelector('tbody [data-slot="table-cell"]');
        const typeBadge = wrapper.querySelector('.ai-task-dimension--type');
        const frequencyBadge = wrapper.querySelector('.ai-task-dimension--frequency');
        const actions = wrapper.querySelector('.ai-task-actions');
        const actionCell = actions?.closest('[data-slot="table-cell"]');
        const actionCellStyle = actionCell ? getComputedStyle(actionCell) : null;
        const actionContentWidth = actionCell && actionCellStyle
          ? actionCell.clientWidth
            - Number.parseFloat(actionCellStyle.paddingLeft)
            - Number.parseFloat(actionCellStyle.paddingRight)
          : 0;
        return {
          clientWidth: container?.clientWidth ?? 0,
          scrollWidth: container?.scrollWidth ?? 0,
          stickyCount: wrapper.querySelectorAll('[data-task-sticky]').length,
          bodyFontSize: bodyCell ? Number.parseFloat(getComputedStyle(bodyCell).fontSize) : 0,
          typeFontSize: typeBadge ? Number.parseFloat(getComputedStyle(typeBadge).fontSize) : 0,
          frequencyFontSize: frequencyBadge ? Number.parseFloat(getComputedStyle(frequencyBadge).fontSize) : 0,
          frequencyUsesRepeatIcon: Boolean(frequencyBadge?.querySelector('[data-task-frequency-icon].lucide-repeat-2')),
          frequencyUsesCalendarIcon: Boolean(frequencyBadge?.querySelector('.lucide-calendar-days')),
          actionsFit: Boolean(actions && actions.scrollWidth <= actionContentWidth + 1),
        };
      });
      if (state.scrollWidth > state.clientWidth + 1 || state.stickyCount !== 0 || !state.actionsFit) {
        throw new Error(`任务配置桌面布局仍存在横向滚动、固定列或操作拥挤：${JSON.stringify(state)}`);
      }
      if (state.bodyFontSize < 14 || state.typeFontSize < 13 || state.frequencyFontSize < 13) {
        throw new Error(`任务类型、频率或表格正文字号层级不足：${JSON.stringify(state)}`);
      }
      if (!state.frequencyUsesRepeatIcon || state.frequencyUsesCalendarIcon) {
        throw new Error(`任务频率应使用循环语义图标：${JSON.stringify(state)}`);
      }
    },
  });
}

async function smokeTasks() {
  await runSmoke({
    route: '/ai/tasks',
    viewport: { width: 1115, height: 820 },
    screenshot: screenshotPath('ai-tasks-normal-1115.png'),
    async test(page) {
      const taskSummary = page.getByRole('region', { name: 'AI 定时任务数据汇总' });
      await taskSummary.waitFor();
      if (await taskSummary.locator('.summary-item').count() !== 4 || await taskSummary.locator('dt').count() !== 4 || await taskSummary.locator('dd').count() !== 4) {
        throw new Error('AI 定时任务页未使用共享汇总组件或语义结构不完整');
      }
      await page.locator('.ai-result-archive').waitFor();
      const taskTable = page.locator('.ai-task-table-wrap [data-slot="table-container"]');
      await taskTable.waitFor();
      const scrollStructure = await page.locator('.ai-task-table-wrap').evaluate(wrapper => {
        const container = wrapper.querySelector('[data-slot="table-container"]');
        return {
          wrapperOverflow: getComputedStyle(wrapper).overflow,
          containerOverflowX: container ? getComputedStyle(container).overflowX : '',
          horizontalOverflow: container ? container.scrollWidth > container.clientWidth + 1 : false,
        };
      });
      if (scrollStructure.wrapperOverflow !== 'visible' || scrollStructure.containerOverflowX !== 'auto' || !scrollStructure.horizontalOverflow) {
        throw new Error(`任务表格滚动层级异常：${JSON.stringify(scrollStructure)}`);
      }

      const scrollBaseline = await page.locator('.ai-task-table-wrap').evaluate(wrapper => {
        const name = wrapper.querySelector('[data-slot="table-head"]:first-child');
        const actions = wrapper.querySelector('[data-slot="table-head"]:last-child');
        return {
          nameLeft: name?.getBoundingClientRect().left ?? 0,
          actionsLeft: actions?.getBoundingClientRect().left ?? 0,
        };
      });
      await taskTable.evaluate(element => { element.scrollLeft = element.scrollWidth; });
      await page.waitForTimeout(80);
      const naturalScrollState = await page.locator('.ai-task-table-wrap').evaluate(wrapper => {
        const name = wrapper.querySelector('[data-slot="table-head"]:first-child');
        const actions = wrapper.querySelector('[data-slot="table-head"]:last-child');
        return {
          stickyCount: wrapper.querySelectorAll('[data-task-sticky]').length,
          namePosition: name ? getComputedStyle(name).position : '',
          actionsPosition: actions ? getComputedStyle(actions).position : '',
          nameLeft: name?.getBoundingClientRect().left ?? 0,
          actionsLeft: actions?.getBoundingClientRect().left ?? 0,
        };
      });
      if (naturalScrollState.stickyCount !== 0
        || naturalScrollState.namePosition === 'sticky'
        || naturalScrollState.actionsPosition === 'sticky'
        || naturalScrollState.nameLeft >= scrollBaseline.nameLeft - 40
        || naturalScrollState.actionsLeft >= scrollBaseline.actionsLeft - 40) {
        throw new Error(`任务表格首尾列未随内容自然滚动：${JSON.stringify({ scrollBaseline, naturalScrollState })}`);
      }

      await observeNextTransition(page, '.ai-fade-switch-leave-active, .ai-fade-switch-enter-active');
      await page.getByRole('button', { name: '全部日期', exact: true }).click();
      const archiveTransitionDuration = await readObservedTransition(page, '任务归档日期切换');
      if (archiveTransitionDuration < 140 || archiveTransitionDuration > 300) {
        throw new Error(`任务归档日期切换动效时长异常：${archiveTransitionDuration}ms`);
      }
      await page.waitForTimeout(240);

      const alternateExecution = page.locator('.ai-archive-list button:not(.is-active)').first();
      await alternateExecution.waitFor();
      await observeNextTransition(page, '.ai-result-switch-leave-active, .ai-result-switch-enter-active');
      await alternateExecution.click();
      const resultTransitionDuration = await readObservedTransition(page, '任务结果切换');
      if (resultTransitionDuration < 140 || resultTransitionDuration > 300) {
        throw new Error(`任务结果切换动效时长异常：${resultTransitionDuration}ms`);
      }
      await page.waitForTimeout(240);

      let enabledSwitch = page.getByRole('switch', { name: /停用$/ }).first();
      await enabledSwitch.waitFor();
      const toggleRow = enabledSwitch.locator('xpath=ancestor::tr');
      const toggleTaskName = (await toggleRow.locator('.ai-task-title').innerText()).trim();
      const stableToggleRow = page.getByRole('row').filter({ hasText: toggleTaskName }).first();
      await enabledSwitch.click();
      await page.waitForFunction(() => document.querySelector('.ai-task-table-wrap [data-slot="table-container"]')?.getAttribute('aria-busy') === 'true');
      const actionLock = await page.evaluate(() => ({
        switchesLocked: [...document.querySelectorAll('.ai-task-table-wrap [role="switch"]')].every(element => element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true'),
        executeButtonsLocked: [...document.querySelectorAll('.ai-task-actions button')]
          .filter(element => element.textContent?.trim() === '执行')
          .every(element => element.hasAttribute('disabled')),
      }));
      if (!actionLock.switchesLocked || !actionLock.executeButtonsLocked) {
        throw new Error(`任务开关操作期间未全表锁定：${JSON.stringify(actionLock)}`);
      }
      await stableToggleRow.getByRole('switch', { name: `${toggleTaskName}启用`, exact: true }).waitFor({ timeout: 5000 });
      if (await taskTable.getAttribute('aria-busy') !== 'false') throw new Error('任务停用完成后表格忙碌状态未恢复');
      enabledSwitch = stableToggleRow.getByRole('switch', { name: `${toggleTaskName}启用`, exact: true });
      await enabledSwitch.click();
      await stableToggleRow.getByRole('switch', { name: `${toggleTaskName}停用`, exact: true }).waitFor({ timeout: 5000 });
      if (await taskTable.getAttribute('aria-busy') !== 'false') throw new Error('任务重新启用后表格忙碌状态未恢复');

      enabledSwitch = page.getByRole('switch', { name: /停用$/ }).first();
      const enabledRow = enabledSwitch.locator('xpath=ancestor::tr');
      const executeButton = enabledRow.getByRole('button', { name: '执行', exact: true });
      await executeButton.click();
      const executingButton = enabledRow.getByRole('button', { name: '执行中', exact: true });
      await executingButton.waitFor();
      if (!(await executingButton.isDisabled())) throw new Error('任务执行期间按钮未锁定');
      if (await taskTable.getAttribute('aria-busy') !== 'true') throw new Error('任务执行期间表格未标记忙碌状态');
      const otherActionsLocked = await page.getByRole('button', { name: '执行', exact: true }).evaluateAll(buttons => buttons.every(button => button.hasAttribute('disabled')));
      const switchesLocked = await page.getByRole('switch').evaluateAll(switches => switches.every(element => element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true'));
      if (!otherActionsLocked || !switchesLocked) throw new Error('任务执行期间未锁定其他行操作');
      await executingButton.waitFor({ state: 'hidden', timeout: 5000 });
      if (await taskTable.getAttribute('aria-busy') !== 'false') throw new Error('任务执行完成后表格忙碌状态未恢复');
      await page.getByText(/已执行$/).last().waitFor();

      await page.locator('.ai-date-filter__trigger').waitFor();
      await page.locator('.ai-date-filter__trigger').click();
      await page.locator('.ai-calendar-grid button').first().waitFor();
      const archiveDateButtons = await page.locator('.ai-calendar-grid button').count();
      if (archiveDateButtons > 1) {
        await page.locator('.ai-calendar-grid button').nth(1).click();
        await page.locator('.ai-result-archive [data-ai-fade-switch-content]').waitFor();
      }
      await page.keyboard.press('Escape');

      await page.locator('.ai-execution-conversation').waitFor();
      await page.locator('.ai-execution-charts .ai-chart-card').first().waitFor();
      const barRects = await page.locator('.ai-execution-charts .ai-chart-card__bar-layer rect').count();
      if (barRects < 3) throw new Error(`Task bar chart should render bars, count=${barRects}`);
      const yLabels = await page.locator('.ai-execution-charts .ai-chart-card__y-axis text').count();
      if (yLabels < 5) throw new Error(`Task charts should render y-axis labels, count=${yLabels}`);
      const xLabels = await page.locator('.ai-execution-charts .ai-chart-card__x-axis text').count();
      if (xLabels < 6) throw new Error(`Task charts should render stable x-axis labels, count=${xLabels}`);
      const chartUnit = await page.locator('.ai-execution-charts .ai-chart-card__unit').first().evaluate(element => element.textContent || '');
      if (!chartUnit.includes('单位')) throw new Error(`Task chart should render y-axis unit, got=${chartUnit}`);
      const baseline = await page.locator('.ai-execution-charts .ai-chart-card__bar-layer rect').first().evaluate(rect => {
        const y = Number(rect.getAttribute('y'));
        const height = Number(rect.getAttribute('height'));
        return Number((y + height).toFixed(1));
      });
      if (Math.abs(baseline - 214) > 0.5) throw new Error(`Task bar chart baseline mismatch: ${baseline}`);

      await page.locator('.ai-chart-card__zoom').first().click();
      await page.locator('[data-ai-chart-expanded]').waitFor();
      await assertExpandedChartCenteredInContent(page, '经营任务中心放大图表');
      await page.locator('[data-slot="dialog-close"]').click();
      await page.locator('[data-ai-chart-expanded]').waitFor({ state: 'hidden' });

      await taskTable.evaluate(element => { element.scrollLeft = 0; });
      await page.getByRole('heading', { name: '经营任务中心' }).evaluate(element => {
        let current = element;
        while (current) {
          if (current instanceof HTMLElement) current.scrollTop = 0;
          current = current.parentElement;
        }
        window.scrollTo(0, 0);
      });
      await page.locator('[data-sonner-toast]').waitFor({ state: 'hidden', timeout: 6000 }).catch(() => undefined);

      console.log('SMOKE_OK: AI scheduled tasks page passed');
    },
  });
}

async function smokeReducedMotion() {
  await runSmoke({
    route: '/ai/assistant',
    reducedMotion: 'reduce',
    viewport: { width: 1280, height: 720 },
    screenshot: screenshotPath('ai-assistant-reduced-motion.png'),
    async test(page) {
      await page.locator('.ai-composer').waitFor();
      const durations = await page.locator('.ai-composer').evaluate(element => ({
        composer: getComputedStyle(element).transitionDuration,
        stream: getComputedStyle(document.querySelector('.ai-chat-stream')).transitionDuration,
      }));
      if (durationToMilliseconds(durations.composer) > 1 || durationToMilliseconds(durations.stream) > 1) {
        throw new Error(`减少动态效果模式仍存在长过渡：${JSON.stringify(durations)}`);
      }
      const input = page.getByLabel('经营问题');
      await input.fill('检查库存风险');
      await input.press('Enter');
      const status = page.getByRole('status').filter({ hasText: '正在分析' });
      await status.waitFor();
      const animationDuration = await status.locator('span').first().evaluate(element => getComputedStyle(element).animationDuration);
      if (durationToMilliseconds(animationDuration) > 1) {
        throw new Error(`减少动态效果模式下思考动画仍过长：${animationDuration}`);
      }
      await status.waitFor({ state: 'hidden', timeout: 5000 });
    },
  });
}

async function smokeTasksReducedMotion() {
  await runSmoke({
    route: '/ai/tasks',
    reducedMotion: 'reduce',
    viewport: { width: 1280, height: 720 },
    screenshot: screenshotPath('ai-tasks-reduced-motion.png'),
    async test(page) {
      await page.locator('.ai-result-card__inner').waitFor();
      await page.locator('.ai-date-filter__trigger').click();
      const calendarButton = page.locator('.ai-calendar-grid button').first();
      await calendarButton.waitFor();
      const calendarDuration = await calendarButton.evaluate(element => getComputedStyle(element).transitionDuration);
      if (durationToMilliseconds(calendarDuration) > 1) {
        throw new Error(`减少动态效果模式下日历按钮过渡仍过长：${calendarDuration}`);
      }
      await page.keyboard.press('Escape');

      await observeNextTransition(page, '.ai-fade-switch-leave-active, .ai-fade-switch-enter-active');
      await page.getByRole('button', { name: '全部日期', exact: true }).click();
      const archiveDuration = await readObservedTransition(page, '减少动态效果任务归档切换');
      if (archiveDuration > 1) throw new Error(`减少动态效果模式下归档切换仍过长：${archiveDuration}ms`);
      await page.waitForTimeout(30);

      const alternateExecution = page.locator('.ai-archive-list button:not(.is-active)').first();
      await alternateExecution.waitFor();
      await observeNextTransition(page, '.ai-result-switch-leave-active, .ai-result-switch-enter-active');
      await alternateExecution.click();
      const resultDuration = await readObservedTransition(page, '减少动态效果任务结果切换');
      if (resultDuration > 1) throw new Error(`减少动态效果模式下结果切换仍过长：${resultDuration}ms`);
    },
  });
}

smokeAssistant()
  .then(smokeTasksDesktopLayout)
  .then(smokeTasks)
  .then(smokeReducedMotion)
  .then(smokeTasksReducedMotion)
  .catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
