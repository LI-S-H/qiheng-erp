<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Boxes,
  ChartNoAxesCombined,
  Eye,
  EyeOff,
  Loader2,
  Lock,
  ShieldCheck,
  User,
} from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { getApiErrorMessage } from '@/api/http';
import logoUrl from '@/assets/brand/qiheng-logo.svg';
import visualUrl from '@/assets/brand/login-operations.svg';
import { useAuthStore } from '../stores/authStore';
import type { LoginRequest } from '../types';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const isDevelopment = import.meta.env.DEV;

const submitting = ref(false);
const showPassword = ref(false);
const capsLockOn = ref(false);
const submitError = ref('');
const errors = reactive({ username: '', password: '' });

const form = reactive<LoginRequest>({
  username: isDevelopment ? 'admin' : '',
  password: isDevelopment ? '123456' : '',
});

function validateUsername() {
  errors.username = form.username.trim() ? '' : '请输入登录账号';
  return !errors.username;
}

function validatePassword() {
  errors.password = form.password ? '' : '请输入登录密码';
  return !errors.password;
}

function validate() {
  const usernameValid = validateUsername();
  const passwordValid = validatePassword();
  return usernameValid && passwordValid;
}

function handleUsernameInput() {
  submitError.value = '';
  if (errors.username && form.username.trim()) errors.username = '';
}

function handlePasswordInput() {
  submitError.value = '';
  if (errors.password && form.password) errors.password = '';
}

function handlePasswordKeyState(event: KeyboardEvent) {
  capsLockOn.value = event.getModifierState('CapsLock');
}

function handlePasswordBlur() {
  capsLockOn.value = false;
  validatePassword();
}

function resolveRedirect() {
  const redirect = route.query.redirect;
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return '/dashboard';
  }
  return redirect;
}

async function handleSubmit() {
  if (submitting.value || !validate()) return;

  submitting.value = true;
  submitError.value = '';

  try {
    await authStore.login({
      username: form.username.trim(),
      password: form.password,
    });
    await router.replace(resolveRedirect());
  } catch (error) {
    submitError.value = getApiErrorMessage(error) || '登录失败，请检查账号、密码或网络状态';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-page min-h-screen bg-slate-100 px-4 py-4 sm:px-6 sm:py-6 lg:px-10 lg:py-8">
    <div
      class="login-shell mx-auto grid min-h-[calc(100vh-2rem)] w-full max-w-[1180px] grid-cols-1 overflow-hidden rounded-2xl bg-white shadow-[0_22px_70px_-38px_rgb(15_23_42/0.45)] ring-1 ring-slate-200 sm:min-h-[calc(100vh-3rem)] lg:min-h-[calc(100vh-4rem)] lg:grid-cols-[minmax(0,680px)_420px]"
    >
      <section class="login-visual relative flex items-center overflow-hidden bg-slate-50 p-6 sm:p-8 lg:p-12">
        <div class="relative z-10 mx-auto w-full max-w-[640px]">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div class="flex items-center gap-3">
              <img class="h-12 w-12 rounded-xl ring-1 ring-slate-200 sm:h-14 sm:w-14" :src="logoUrl" alt="启衡 ERP" />
              <div>
                <p class="text-xl font-extrabold tracking-tight text-slate-900 sm:text-2xl">启衡 ERP</p>
                <p class="mt-0.5 text-xs text-slate-500">进销存智能管理平台</p>
              </div>
            </div>
            <span
              v-if="isDevelopment"
              data-development-badge
              class="rounded-full bg-blue-50 px-3 py-1 text-xs font-medium text-blue-700 ring-1 ring-inset ring-blue-100"
            >
              开发环境
            </span>
          </div>

          <h1 class="mt-8 max-w-[590px] text-3xl font-bold leading-tight tracking-tight text-slate-950 sm:text-4xl lg:text-[42px]">
            让采购、销售与仓储协同更清晰
          </h1>
          <p class="mt-4 max-w-[590px] text-sm leading-7 text-slate-600 sm:text-base">
            统一业务数据与权限边界，让每一次查询、审批和库存变动都有可靠依据。
          </p>

          <div class="mt-6 grid gap-2 text-sm text-slate-700 sm:grid-cols-3">
            <div class="flex items-center gap-2 rounded-lg bg-white/80 px-3 py-2 ring-1 ring-slate-200/80">
              <Boxes class="size-4 text-blue-600" aria-hidden="true" />
              <span>进销存统一</span>
            </div>
            <div class="flex items-center gap-2 rounded-lg bg-white/80 px-3 py-2 ring-1 ring-slate-200/80">
              <ShieldCheck class="size-4 text-blue-600" aria-hidden="true" />
              <span>权限边界清晰</span>
            </div>
            <div class="flex items-center gap-2 rounded-lg bg-white/80 px-3 py-2 ring-1 ring-slate-200/80">
              <ChartNoAxesCombined class="size-4 text-blue-600" aria-hidden="true" />
              <span>经营数据可追溯</span>
            </div>
          </div>

          <img
            class="mt-7 hidden w-full max-w-[600px] rounded-xl border border-slate-200 bg-white shadow-[0_18px_40px_-24px_rgb(15_23_42/0.35)] lg:block"
            :src="visualUrl"
            alt="启衡 ERP 经营数据概览"
          />
        </div>
      </section>

      <section class="flex items-center justify-center border-t border-slate-200 bg-white p-6 sm:p-10 lg:border-l lg:border-t-0 lg:p-12">
        <Card class="w-full max-w-[380px] gap-0 border-0 py-0 shadow-none ring-0">
          <CardHeader class="space-y-2 px-0 pb-6">
            <CardTitle class="text-3xl tracking-tight"><h2>登录启衡 ERP</h2></CardTitle>
            <CardDescription>
              {{ isDevelopment ? '使用演示账号体验完整业务流程' : '使用企业账号进入管理平台' }}
            </CardDescription>
            <div
              v-if="isDevelopment"
              data-development-credentials
              class="mt-3 flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600 ring-1 ring-slate-200"
            >
              <span>演示账号</span>
              <code class="font-medium text-slate-800">admin / 123456</code>
            </div>
          </CardHeader>
          <CardContent class="px-0">
            <form class="space-y-4" novalidate @submit.prevent="handleSubmit">
              <div class="space-y-2">
                <Label for="username">登录账号 <span class="text-destructive" aria-hidden="true">*</span></Label>
                <div class="relative">
                  <User class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                  <Input
                    id="username"
                    v-model="form.username"
                    autofocus
                    placeholder="请输入登录账号"
                    autocomplete="username"
                    spellcheck="false"
                    class="h-12 pl-10 text-base"
                    :aria-invalid="Boolean(errors.username)"
                    :aria-describedby="errors.username ? 'username-error' : undefined"
                    @input="handleUsernameInput"
                    @blur="validateUsername"
                  />
                </div>
                <p v-if="errors.username" id="username-error" class="text-sm text-destructive" role="alert">
                  {{ errors.username }}
                </p>
              </div>

              <div class="space-y-2">
                <Label for="password">登录密码 <span class="text-destructive" aria-hidden="true">*</span></Label>
                <div class="relative">
                  <Lock class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                  <Input
                    id="password"
                    v-model="form.password"
                    :type="showPassword ? 'text' : 'password'"
                    placeholder="请输入登录密码"
                    autocomplete="current-password"
                    class="h-12 px-10 text-base"
                    :aria-invalid="Boolean(errors.password)"
                    :aria-describedby="errors.password ? 'password-error' : (capsLockOn ? 'caps-lock-hint' : undefined)"
                    @input="handlePasswordInput"
                    @blur="handlePasswordBlur"
                    @keydown="handlePasswordKeyState"
                    @keyup="handlePasswordKeyState"
                  />
                  <button
                    type="button"
                    class="absolute right-2 top-1/2 z-20 flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/20"
                    :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                    :title="showPassword ? '隐藏密码' : '显示密码'"
                    @click="showPassword = !showPassword"
                  >
                    <EyeOff v-if="showPassword" class="size-4" aria-hidden="true" />
                    <Eye v-else class="size-4" aria-hidden="true" />
                  </button>
                </div>
                <p v-if="errors.password" id="password-error" class="text-sm text-destructive" role="alert">
                  {{ errors.password }}
                </p>
                <p v-else-if="capsLockOn" id="caps-lock-hint" data-caps-lock-hint class="text-sm text-amber-700" role="status">
                  Caps Lock 已开启，请注意密码大小写
                </p>
              </div>

              <p v-if="submitError" data-login-error class="rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-700 ring-1 ring-inset ring-red-100" role="alert">
                {{ submitError }}
              </p>

              <Button
                type="submit"
                class="mt-2 h-12 w-full text-base font-semibold"
                :disabled="submitting"
                :aria-busy="submitting"
              >
                <Loader2 v-if="submitting" class="mr-2 size-4 animate-spin" aria-hidden="true" />
                {{ submitting ? '正在登录...' : '登录' }}
              </Button>
              <p class="text-center text-xs leading-5 text-muted-foreground">
                登录后仅展示当前账号有权限访问的业务范围
              </p>
            </form>
          </CardContent>
        </Card>
      </section>
    </div>
  </main>
</template>

<style scoped>
.login-shell {
  animation: login-shell-enter var(--motion-duration-slow) var(--motion-ease-standard) both;
}

@keyframes login-shell-enter {
  from {
    opacity: 0;
    transform: translateY(8px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1023px) {
  .login-shell {
    min-height: auto;
  }
}
</style>
