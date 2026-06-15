<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';

const route = useRoute();

const title = computed(() => String(route.meta.title || '模块页面'));
const description = computed(() => String(route.meta.description || '当前页面是模块骨架占位，后续会按审核顺序生成真实页面'));
</script>

<template>
  <section class="page-shell placeholder-page">
    <div class="flex items-start justify-between mb-5">
      <div>
        <h1 class="page-title">{{ title }}</h1>
        <p class="muted-text">{{ description }}</p>
      </div>
    </div>

    <Card>
      <CardHeader>
        <CardTitle>页面生成状态</CardTitle>
      </CardHeader>
      <CardContent class="space-y-2">
        <p class="muted-text">当前只搭建导航和路由结构</p>
        <p class="muted-text">该页面会在对应模块开发时补充列表筛选 表格 表单弹窗 详情抽屉和操作确认</p>
      </CardContent>
    </Card>
  </section>
</template>
