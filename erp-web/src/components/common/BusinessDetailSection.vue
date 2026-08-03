<script setup lang="ts">
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<template>
  <section class="business-detail-section">
    <header class="business-detail-section__header">
      <div class="min-w-0">
        <h3>{{ title }}</h3>
        <p v-if="description">{{ description }}</p>
      </div>
      <slot name="action" />
    </header>
    <div class="business-detail-section__content">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.business-detail-section { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); background: var(--card); }
.business-detail-section__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 14px 16px 0; }
.business-detail-section__header h3 { margin: 0; color: var(--foreground); font-size: 14px; font-weight: 650; line-height: 20px; }
.business-detail-section__header p { margin: 4px 0 0; color: var(--muted-foreground); font-size: 12px; line-height: 18px; }
.business-detail-section__content { padding: 14px 16px 16px; }
</style>
