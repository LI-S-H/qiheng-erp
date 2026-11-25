<script setup lang="ts">
import { ArrowRight, ClipboardCheck } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';

defineProps<{
  action: 'SUBMIT' | 'CANCEL';
  orderNo: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{ open: [] }>();
</script>

<template>
  <div class="draft-card" data-ai-sales-action>
    <span class="draft-card-icon" aria-hidden="true"><ClipboardCheck class="size-4" /></span>
    <div class="draft-card-body">
      <p class="draft-card-title">{{ action === 'SUBMIT' ? '提交销售单' : '取消销售单' }}</p>
      <p class="draft-card-hint">
        {{ orderNo || '销售单' }} 尚未处理，点开确认后才能{{ action === 'SUBMIT' ? '提交审核（提交会锁定可用库存）' : '取消（已锁定库存会释放）' }}。
      </p>
    </div>
    <Button size="sm" :disabled="disabled" data-testid="sales-action-open" @click="emit('open')">
      查看详情
      <ArrowRight class="ml-1 size-3.5" aria-hidden="true" />
    </Button>
  </div>
</template>

<style scoped>
.draft-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--card);
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
}
.draft-card-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  color: var(--primary);
}
.draft-card-body {
  min-width: 0;
  flex: 1;
}
.draft-card-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--foreground);
}
.draft-card-hint {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted-foreground);
}
</style>