<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();

const title = computed(() => String(route.meta.title || '模块页面'));
const description = computed(() => String(route.meta.description || '当前页面是模块骨架占位，后续会按审核顺序生成真实页面'));
</script>

<template>
  <section class="page-shell placeholder-page">
    <div class="placeholder-head">
      <div>
        <h1 class="page-title">{{ title }}</h1>
        <p class="muted-text">{{ description }}</p>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>页面生成状态</template>
      <p class="muted-text">当前只搭建导航和路由结构</p>
      <p class="muted-text">该页面会在对应模块开发时补充列表筛选 表格 表单弹窗 详情抽屉和操作确认</p>
    </el-card>
  </section>
</template>

<style scoped>
.placeholder-page {
  min-height: 100%;
}

.placeholder-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}

</style>
