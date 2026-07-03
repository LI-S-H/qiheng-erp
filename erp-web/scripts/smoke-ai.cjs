const { runSmoke } = require('./smoke-helpers.cjs');

async function smokeAssistant() {
  await runSmoke({
    route: '/ai/assistant',
    screenshot: 'smoke-ai-assistant.png',
    async test(page) {
      await page.locator('.ai-chat-stage').waitFor();
      await page.locator('.ai-composer').waitFor();

      const composer = await page.locator('.ai-composer').evaluate(element => {
        const rect = element.getBoundingClientRect();
        const parentRect = element.parentElement?.getBoundingClientRect();
        const style = window.getComputedStyle(element);
        return {
          height: rect.height,
          width: rect.width,
          bottomGap: parentRect ? Number((parentRect.bottom - rect.bottom).toFixed(1)) : -1,
          parentClass: element.parentElement?.className || '',
          position: style.position,
        };
      });
      if (composer.height > 76 || composer.width < 360 || composer.bottomGap < 8 || composer.bottomGap > 32 || composer.position !== 'absolute' || !composer.parentClass.includes('ai-chat-stage')) {
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
      await page.waitForTimeout(220);
      const switchingStageStillVisible = await page.locator('.ai-chat-stage.is-switching').count();
      if (switchingStageStillVisible !== 1) throw new Error(`Conversation switch transition should stay visible briefly, count=${switchingStageStillVisible}`);
      await page.locator('.ai-message-charts .ai-chart-card').first().waitFor();
      const visibleSources = await page.locator('.ai-source-strip:visible').count();
      if (visibleSources !== 0) throw new Error(`Source strip should be hidden, visible count=${visibleSources}`);

      await page.locator('.ai-chart-card__zoom').first().click();
      await page.locator('[data-ai-chart-expanded]').waitFor();
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

      console.log('SMOKE_OK: AI assistant page passed');
    },
  });
}

async function smokeTasks() {
  await runSmoke({
    route: '/ai/tasks',
    screenshot: 'smoke-ai-tasks.png',
    async test(page) {
      await page.locator('.ai-result-archive').waitFor();
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
      await page.locator('[data-slot="dialog-close"]').click();
      await page.locator('[data-ai-chart-expanded]').waitFor({ state: 'hidden' });

      console.log('SMOKE_OK: AI scheduled tasks page passed');
    },
  });
}

smokeAssistant()
  .then(smokeTasks)
  .catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
