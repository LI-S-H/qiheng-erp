<script setup lang="ts">
import { ArrowRight, ClipboardCheck } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';

defineProps<{
  itemCount: number;
  returnType: 'SALES_RETURN' | 'PURCHASE_RETURN';
  action?: 'CREATE' | 'UPDATE';
  disabled?: boolean;
}>();

const emit = defineEmits<{ open: [] }>();
</script>

<template>
  <div class="draft-card" data-ai-return-draft>
    <span class="draft-card-icon" aria-hidden="true"><ClipboardCheck class="size-4" /></span>
    <div class="draft-card-body">
      <p class="draft-card-title">
        {{ returnType === 'SALES_RETURN' ? '销售退货单' : '采购退货单' }}{{ action === 'UPDATE' ? '修改待确认' : '草稿待确认' }}
      </p>
      <p class="draft-card-hint">
        共 {{ itemCount }} 行明细，{{ action === 'UPDATE' ? '修改尚未保存' : '尚未写入系统' }}。点开查看完整信息后才能确认或驳回。
      </p>
    </div>
    <Button size="sm" :disabled="disabled" data-testid="return-draft-open" @click="emit('open')">
      查看详情
      <ArrowRight class="ml-1 size-3.5" aria-hidden="true" />
    </Button>
  </div>
</template>

<style scoped>
.draft-card { display: flex; align-items: flex-start; gap: 12px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); box-shadow: 0 1px 2px rgb(15 23 42 / 4%); }
.draft-card-icon { display: inline-flex; flex-shrink: 0; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 8px; background: color-mix(in srgb, var(--primary) 10%, transparent); color: var(--primary); }
.draft-card-body { min-width: 0; flex: 1; }
.draft-card-title { margin: 0; font-size: 13px; font-weight: 600; line-height: 1.5; color: var(--foreground); }
.draft-card-hint { margin: 2px 0 0; font-size: 12px; line-height: 1.6; color: var(--muted-foreground); }
</style>