<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage } from 'element-plus';
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import logoUrl from '@/assets/brand/qiheng-logo.svg';
import visualUrl from '@/assets/brand/login-operations.svg';
import { useAuthStore } from '../stores/authStore';
import type { LoginRequest } from '../types';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const formRef = ref<FormInstance>();
const submitting = ref(false);

const form = reactive<LoginRequest>({
  username: 'admin',
  password: '123456',
});

const rules: FormRules<LoginRequest> = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }],
};

async function handleSubmit() {
  await formRef.value?.validate();

  submitting.value = true;

  try {
    await authStore.login(form);
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard';
    router.replace(redirect);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-visual">
      <div class="login-visual__content">
        <div class="login-visual__brand">
          <img class="login-visual__logo" :src="logoUrl" alt="启衡 ERP" />
          <span>启衡 ERP</span>
        </div>
        <h1>清晰可靠的进销存智能管理台</h1>
        <p class="login-visual__text">围绕产品 采购 销售 仓储构建稳定业务闭环 让数据查询和智能分析遵循统一权限与业务规则</p>
        <img class="login-visual__image" :src="visualUrl" alt="业务数据看板" />
      </div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <div class="login-card__header">
          <h2>登录启衡 ERP</h2>
          <p>使用管理员账号进入开发环境</p>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleSubmit">
          <el-form-item prop="username">
            <el-input v-model.trim="form.username" placeholder="登录账号" autocomplete="username">
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="登录密码"
              autocomplete="current-password"
              show-password
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-button class="login-card__submit" type="primary" size="large" :loading="submitting" @click="handleSubmit">
            登录
          </el-button>
        </el-form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(620px, 1fr) 500px;
  min-height: 100vh;
  background: #f6f8fb;
}

.login-visual {
  position: relative;
  display: flex;
  align-items: center;
  padding: 54px 72px;
  overflow: hidden;
  color: #172033;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(238, 244, 255, 0.88)),
    radial-gradient(circle at 16% 18%, rgba(79, 140, 255, 0.18), transparent 34%),
    radial-gradient(circle at 76% 66%, rgba(20, 27, 45, 0.12), transparent 30%);
}

.login-visual::after {
  position: absolute;
  right: 54px;
  bottom: 52px;
  width: 240px;
  height: 240px;
  content: "";
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid rgba(79, 140, 255, 0.16);
  border-radius: 50%;
}

.login-visual__content {
  position: relative;
  z-index: 1;
  width: min(760px, 100%);
}

.login-visual__brand {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-bottom: 34px;
  font-size: 23px;
  font-weight: 800;
  color: #172033;
}

.login-visual__logo {
  width: 56px;
  height: 56px;
  border-radius: 14px;
}

.login-visual h1 {
  max-width: 760px;
  margin: 0;
  font-size: 44px;
  line-height: 1.2;
  letter-spacing: 0;
}

.login-visual__text {
  max-width: 640px;
  margin: 22px 0 30px;
  font-size: 18px;
  line-height: 1.85;
  color: #516074;
}

.login-visual__image {
  display: block;
  width: min(680px, 96%);
  margin-top: 10px;
  border-radius: 24px;
  box-shadow: 0 28px 80px rgba(32, 48, 72, 0.14);
}

.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  background: #ffffff;
}

.login-card {
  width: 100%;
  max-width: 380px;
}

.login-card__header {
  margin-bottom: 30px;
}

.login-card h2 {
  margin: 0;
  font-size: 32px;
  color: #172033;
}

.login-card p {
  margin: 10px 0 0;
  font-size: 16px;
  color: #6b7280;
}

.login-card :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-card :deep(.el-input__wrapper) {
  min-height: 48px;
  border-radius: 8px;
}

.login-card :deep(.el-input__inner) {
  font-size: 16px;
}

.login-card__submit {
  width: 100%;
  height: 48px;
  margin-top: 6px;
  font-size: 16px;
  font-weight: 700;
  border-radius: 8px;
}

@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-visual {
    min-height: 34vh;
    padding: 36px 28px;
  }

  .login-visual h1 {
    font-size: 32px;
  }

  .login-visual__image {
    display: none;
  }

  .login-panel {
    align-items: flex-start;
    padding: 32px 24px;
  }
}
</style>
