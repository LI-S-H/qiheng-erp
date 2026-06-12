<script setup lang="ts">
import { useRouter } from 'vue-router';
import { Button } from '@/components/ui/button';
import { FileQuestion } from 'lucide-vue-next';

const router = useRouter();
</script>

<template>
  <section class="flex min-h-screen items-center justify-center bg-muted/30">
    <div class="text-center space-y-4">
      <FileQuestion class="h-16 w-16 mx-auto text-muted-foreground" />
      <div>
        <h2 class="text-xl font-semibold">页面不存在或暂未生成</h2>
        <p class="text-muted-foreground mt-1">请检查 URL 是否正确</p>
      </div>
      <Button @click="router.push('/dashboard')">返回工作台</Button>
    </div>
  </section>
</template>
