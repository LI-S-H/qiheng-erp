<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <section class="not-found">
    <el-empty description="页面不存在或暂未生成">
      <el-button type="primary" @click="router.push('/dashboard')">返回工作台</el-button>
    </el-empty>
  </section>
</template>

<style scoped>
.not-found {
  display: grid;
  min-height: 100vh;
  place-items: center;
  background: #f4f7fb;
}
</style>
