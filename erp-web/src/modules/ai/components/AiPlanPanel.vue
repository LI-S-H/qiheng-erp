<script setup lang="ts">
import { computed } from 'vue';
import { ClipboardList, Database, FileCode2, PanelRightClose, PanelRightOpen } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import type { Need, PlanningArtifact } from '../graph-types';
const props = defineProps<{ artifact: PlanningArtifact | null; collapsed?: boolean; loading?: boolean }>();
const plan = computed(() => props.artifact?.type === 'PLAN' ? props.artifact.data.plan : null);
const needs = computed(() => props.artifact?.type === 'NEEDS' ? props.artifact.data.needs : null);
const statuses: Record<Need['status'], string> = { PENDING: '待处理', RUNNING: '处理中', DONE: '已完成', SKIPPED: '已跳过', BLOCKED: '受阻', INFEASIBLE: '不可行' };
defineEmits<{ close: []; expand: [] }>();
</script>
<template>
  <aside class="plan-panel" :class="{ 'is-collapsed': collapsed }" :aria-busy="loading" aria-label="任务计划" data-testid="ai-plan-panel">
    <div v-if="collapsed" class="plan-rail">
      <Button variant="ghost" size="icon-sm" aria-label="展开任务计划" title="展开任务计划" @click="$emit('expand')">
        <PanelRightOpen class="size-4" />
      </Button>
    </div>
    <template v-else>
    <header>
      <span><ClipboardList class="size-4" />{{ needs ? '任务需求' : '任务计划' }}</span
      ><Button variant="ghost" size="icon-sm" :disabled="loading" aria-label="关闭任务计划" @click="$emit('close')"
        ><PanelRightClose class="size-4"
      /></Button>
    </header>
    <div v-if="needs" class="plan-scroll" :inert="loading || undefined" data-testid="ai-needs-detail">
      <h2>本次需求 · {{ needs.length }} 项</h2>
      <p class="plan-objective">以下是本次分析的目标与待获取的信息。</p>
      <ol class="needs-list">
        <li v-for="need in needs" :key="need.id" class="need-item">
          <h3 class="need-heading"><span class="need-id">{{ need.id }}</span><span>{{ need.intent === 'DATA' ? '事实分析' : '建议方案' }}</span><Badge variant="secondary" class="need-status" :data-status="need.status">{{ statuses[need.status] }}</Badge></h3>
          <p class="need-description">{{ need.need }}</p>
          <p v-if="need.depends.length" class="need-meta">前置需求：{{ need.depends.join('、') }}</p>
          <details v-if="need.condition"><summary>触发条件</summary><p class="need-meta">{{ need.condition.left }} {{ need.condition.op }} {{ need.condition.right }}</p></details>
          <details>
            <summary>待取证据 · {{ need.evidence_plan.length }} 项</summary>
            <ul class="evidence-list"><li v-for="item in need.evidence_plan" :key="item.id">
              <strong>{{ item.label }}</strong>
            </li></ul>
          </details>
        </li>
      </ol>
      <p class="plan-note">当前已完成需求拆解，尚未执行取数与分析。证据清单表示待获取的信息。</p>
    </div>
    <div v-else-if="plan" class="plan-scroll" :inert="loading || undefined">
      <p class="plan-state">{{ plan.needs_clarification ? '等待补充信息' : '计划已生成' }}</p>
      <h2>{{ plan.title }}</h2>
      <p class="plan-objective">{{ plan.objective }}</p>
      <template v-if="plan.steps.length"
        ><h3>分析步骤</h3>
        <ol>
          <li v-for="step in plan.steps" :key="step.step">
            <div class="step-heading">
              <span class="step-number">{{ step.step }}</span
              ><Database v-if="step.type === 'SQL_QUERY'" class="size-3.5" /><FileCode2
                v-else
                class="size-3.5"
              /><span>{{ step.type === 'SQL_QUERY' ? '数据查询' : 'Python 分析' }}</span>
            </div>
            <p>{{ step.instruction }}</p>
          </li>
        </ol></template
      >
      <p v-else class="plan-note">补充范围和条件后，将继续生成完整分析步骤。</p>
      <p class="plan-note">当前阶段只生成分析计划，尚未执行数据查询或 Python 代码。</p>
    </div>
    <div v-else class="plan-empty">
      <ClipboardList class="size-6" />
      <h2>任务需求将在这里显示</h2>
      <p>描述目标并补充分析范围后，在这里查看需求与待取证据。</p>
      <p>当前阶段支持需求拆解与信息澄清。</p>
    </div>
    </template>
    <ListLoadingOverlay :visible="Boolean(loading)" :initial-delay="0" label="正在读取计划" />
  </aside>
</template>
<style scoped>
.plan-panel {
  position: relative;
  display: grid;
  grid-template-rows: 56px minmax(0, 1fr);
  min-height: 0;
  height: 100%;
  background: var(--card);
  border-left: 1px solid var(--border);
}
.plan-panel.is-collapsed {
  grid-template-rows: minmax(0, 1fr);
}
.plan-rail {
  display: flex;
  justify-content: center;
  padding-top: 12px;
}
.plan-empty {
  display: grid;
  align-content: start;
  justify-items: start;
  gap: 14px;
  padding: 28px 24px;
  color: var(--muted-foreground);
  font-size: 13px;
  line-height: 1.8;
}
.plan-empty h2 {
  color: var(--foreground);
  font-size: 14px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border);
  padding: 0 16px;
}
header > span {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
}
.plan-scroll {
  padding: 24px;
  overflow-y: auto;
}
.needs-list { margin-top: 22px; }
.need-item + .need-item { border-top: 1px solid var(--border); padding-top: 20px; }
.plan-panel .needs-list .need-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 9px;
  margin: 0 0 10px;
  color: var(--foreground);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.6;
}
.need-id { color: var(--primary); font-variant-numeric: tabular-nums; }
.need-status {
  margin-left: auto;
  height: 26px;
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--muted);
  color: var(--muted-foreground);
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}
.need-status[data-status='PENDING'] { color: #3865ad; background: #edf3ff; border-color: #d5e2fa; }
.need-status[data-status='RUNNING'] { color: #237482; background: #eaf6f8; border-color: #cce7ec; }
.need-status[data-status='DONE'] { color: #3f7a61; background: #eef7f2; border-color: #d7e9df; }
.need-status[data-status='SKIPPED'] { color: #68758a; background: #f2f4f7; border-color: #e1e6ed; }
.need-status[data-status='BLOCKED'] { color: #946b2b; background: #fbf6eb; border-color: #ecdfc5; }
.need-status[data-status='INFEASIBLE'] { color: var(--destructive); background: color-mix(in srgb, var(--destructive) 7%, var(--card)); border-color: color-mix(in srgb, var(--destructive) 18%, var(--border)); }
.plan-panel .needs-list .need-description { margin: 0 0 14px; font-size: 13px; line-height: 1.85; color: var(--foreground); }
.plan-panel .needs-list .evidence-list > li { padding: 0; border: 0; }
.need-item details { margin-top: 12px; font-size: 13px; }
.need-item summary { cursor: pointer; color: var(--muted-foreground); }
.need-item p { margin: 6px 0 0; }
.need-meta { color: var(--muted-foreground); font-size: 12px; overflow-wrap: anywhere; }
.evidence-list { display: grid; gap: 10px; margin-top: 12px; padding-left: 18px; list-style: disc; }
.evidence-list strong, .evidence-list span { display: block; line-height: 1.8; font-weight: 400; }
.evidence-list p { font-size: 13px; }
.plan-state {
  color: var(--primary);
  font-size: 12px;
  margin-bottom: 10px;
}
h2 {
  font-size: 16px;
  line-height: 1.6;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.plan-objective {
  margin-top: 10px;
  color: var(--muted-foreground);
  font-size: 14px;
  line-height: 1.8;
}
h3 {
  margin: 28px 0 16px;
  font-size: 13px;
  font-weight: 500;
}
ol {
  display: grid;
  gap: 22px;
}
.step-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--muted-foreground);
}
.step-number {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border-radius: 6px;
  background: var(--muted);
  font-variant-numeric: tabular-nums;
}
li p {
  margin: 8px 0 0 30px;
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.plan-note {
  margin-top: 28px;
  border-top: 1px solid var(--border);
  padding-top: 16px;
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.8;
}
</style>
