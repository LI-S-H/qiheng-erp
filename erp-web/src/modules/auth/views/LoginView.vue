<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { toast } from 'vue-sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { User, Lock, Loader2 } from 'lucide-vue-next';
import logoUrl from '@/assets/brand/qiheng-logo.svg';
import visualUrl from '@/assets/brand/login-operations.svg';
import { useAuthStore } from '../stores/authStore';
import type { LoginRequest } from '../types';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const submitting = ref(false);
const errors = reactive({ username: '', password: '' });

const form = reactive<LoginRequest>({
  username: 'admin',
  password: '123456',
});

function validate(): boolean {
  errors.username = form.username ? '' : '请输入登录账号';
  errors.password = form.password ? '' : '请输入登录密码';
  return !errors.username && !errors.password;
}

async function handleSubmit() {
  if (!validate()) return;

  submitting.value = true;

  try {
    await authStore.login(form);
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard';
    router.replace(redirect);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '登录失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="min-h-screen bg-slate-50 px-5 py-8 lg:px-10">
    <div class="mx-auto grid min-h-[calc(100vh-4rem)] w-full max-w-[1180px] grid-cols-1 overflow-hidden rounded-lg bg-white shadow-sm ring-1 ring-slate-200 lg:grid-cols-[minmax(0,680px)_420px]">
      <!-- Left visual panel -->
      <section class="relative flex items-center justify-center overflow-hidden bg-gradient-to-br from-white via-blue-50 to-slate-100 p-8 sm:p-10 lg:p-12">
        <div class="relative z-10 w-full max-w-[640px]">
          <div class="mb-8 flex items-center gap-3">
            <img class="h-14 w-14 rounded-xl" :src="logoUrl" alt="启衡 ERP" />
            <span class="text-2xl font-extrabold text-slate-900">启衡 ERP</span>
          </div>
          <h1 class="text-4xl font-bold leading-tight text-slate-900 lg:text-[44px]">
            清晰可靠的进销存智能管理台
          </h1>
          <p class="mt-5 max-w-[600px] text-lg leading-relaxed text-slate-600">
            围绕产品 采购 销售 仓储构建稳定业务闭环 让数据查询和智能分析遵循统一权限与业务规则
          </p>
          <img
            class="mt-6 block w-full max-w-[600px] rounded-lg shadow-xl"
            :src="visualUrl"
            alt="业务数据看板"
          />
        </div>
      </section>

      <!-- Right login form -->
      <section class="flex items-center justify-center bg-white p-8 sm:p-10 lg:p-12">
        <Card class="w-full max-w-[380px] border-0 shadow-none">
          <CardHeader class="space-y-1 pb-6">
            <CardTitle class="text-3xl">登录启衡 ERP</CardTitle>
            <CardDescription>使用管理员账号进入开发环境</CardDescription>
          </CardHeader>
          <CardContent>
            <form class="space-y-4" @submit.prevent="handleSubmit" @keydown.enter="handleSubmit">
              <div class="space-y-2">
                <Label for="username">登录账号</Label>
                <div class="relative">
                  <User class="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="username"
                    v-model="form.username"
                    placeholder="登录账号"
                    autocomplete="username"
                    class="h-12 pl-10 text-base"
                  />
                </div>
                <p v-if="errors.username" class="text-sm text-destructive">{{ errors.username }}</p>
              </div>

              <div class="space-y-2">
                <Label for="password">登录密码</Label>
                <div class="relative">
                  <Lock class="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="password"
                    v-model="form.password"
                    type="password"
                    placeholder="登录密码"
                    autocomplete="current-password"
                    class="h-12 pl-10 text-base"
                  />
                </div>
                <p v-if="errors.password" class="text-sm text-destructive">{{ errors.password }}</p>
              </div>

              <Button
                type="submit"
                class="mt-2 h-12 w-full text-base font-bold"
                :disabled="submitting"
              >
                <Loader2 v-if="submitting" class="mr-2 h-4 w-4 animate-spin" />
                登录
              </Button>
            </form>
          </CardContent>
        </Card>
      </section>
    </div>
  </main>
</template>
