<script setup lang="ts">
import {
  Box,
  ChatDotRound,
  Goods,
  HomeFilled,
  Lock,
  Bell,
  Sell,
  ShoppingCart,
  SwitchButton,
} from '@element-plus/icons-vue';
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import logoUrl from '@/assets/brand/qiheng-logo.svg';
import { useAuthStore } from '@/modules/auth/stores/authStore';

interface MenuItem {
  index: string;
  title: string;
  icon: unknown;
  permission?: string;
  children?: Array<Omit<MenuItem, 'icon' | 'children'> & { icon?: unknown }>;
}

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const activeMenu = computed(() => route.path);
const openedMenus = reactive(new Set<string>());
const pageLoading = ref(false);
let pageLoadingTimer: number | undefined;

const menus: MenuItem[] = [
  { index: '/dashboard', title: '工作台', icon: HomeFilled },
  {
    index: '/system',
    title: '系统权限',
    icon: Lock,
    children: [
      { index: '/system/users', title: '用户管理' },
      { index: '/system/roles', title: '角色管理' },
      { index: '/system/depts', title: '部门管理' },
      { index: '/system/permissions', title: '权限码配置' },
    ],
  },
  {
    index: '/product',
    title: '产品中心',
    icon: Goods,
    permission: 'product:query',
    children: [
      { index: '/product/categories', title: '产品分类' },
      { index: '/product/products', title: '产品档案' },
    ],
  },
  {
    index: '/warehouse',
    title: '仓储库存',
    icon: Box,
    permission: 'warehouse:query',
    children: [
      { index: '/warehouse/warehouses', title: '仓库管理' },
      { index: '/warehouse/stocks', title: '库存管理' },
      { index: '/warehouse/stock-bills', title: '出入库记录' },
      { index: '/warehouse/stock-adjustments', title: '库存调整' },
    ],
  },
  {
    index: '/purchase',
    title: '采购业务',
    icon: ShoppingCart,
    permission: 'purchase:query',
    children: [
      { index: '/purchase/suppliers', title: '供应商管理' },
      { index: '/purchase/supplier-products', title: '供货产品' },
      { index: '/purchase/orders', title: '采购订单' },
    ],
  },
  {
    index: '/sales',
    title: '销售业务',
    icon: Sell,
    permission: 'sales:query',
    children: [
      { index: '/sales/customers', title: '客户管理' },
      { index: '/sales/orders', title: '销售订单' },
    ],
  },
  {
    index: '/ai',
    title: '智能助手',
    icon: ChatDotRound,
    permission: 'ai:query:stock',
    children: [
      { index: '/ai/rag', title: '知识库问答' },
      { index: '/ai/assistant', title: '智能经营助手' },
      { index: '/ai/audit-logs', title: 'AI 调用审计' },
    ],
  },
];

const userLabel = computed(() => {
  const user = authStore.user;
  if (!user) {
    return '未登录';
  }

  return authStore.displayName;
});

function isMenuActive(item: MenuItem) {
  if (item.index === activeMenu.value) {
    return true;
  }

  return Boolean(item.children?.some(child => child.index === activeMenu.value));
}

function isMenuOpen(index: string) {
  return openedMenus.has(index);
}

function toggleMenu(item: MenuItem) {
  if (!item.children?.length) {
    navigateTo(item.index);
    return;
  }

  if (openedMenus.has(item.index)) {
    openedMenus.delete(item.index);
  } else {
    openedMenus.add(item.index);
  }
}

function navigateTo(path: string) {
  if (route.path !== path) {
    router.push(path);
  }
}

function openCurrentParent(path: string) {
  const parent = menus.find(item => item.children?.some(child => child.index === path));

  if (parent) {
    openedMenus.add(parent.index);
  }
}

async function handleLogout() {
  await ElMessageBox.confirm('确认退出当前登录吗？', '退出登录', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
    type: 'warning',
  });

  await authStore.logout();
  router.replace('/login');
}

watch(
  () => route.path,
  async path => {
    openCurrentParent(path);
    pageLoading.value = true;

    if (pageLoadingTimer) {
      window.clearTimeout(pageLoadingTimer);
    }

    await nextTick();
    pageLoadingTimer = window.setTimeout(() => {
      pageLoading.value = false;
      pageLoadingTimer = undefined;
    }, 260);
  },
  { immediate: true },
);
</script>

<template>
  <el-container class="main-layout">
    <el-aside width="232px" class="main-layout__aside">
      <div class="brand">
        <img class="brand__mark" :src="logoUrl" alt="启衡 ERP" />
        <div>
          <div class="brand__title">启衡 ERP</div>
          <div class="brand__subtitle">进销存智能管理台</div>
        </div>
      </div>

      <nav class="side-nav" aria-label="主导航">
        <div v-for="item in menus" :key="item.index" class="side-nav__group">
          <button
            class="side-nav__item"
            :class="{ 'is-active': isMenuActive(item), 'is-open': isMenuOpen(item.index) }"
            type="button"
            @click="toggleMenu(item)"
          >
            <el-icon>
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.title }}</span>
            <span v-if="item.children?.length" class="side-nav__arrow"></span>
          </button>

          <div
            v-if="item.children?.length"
            class="side-nav__children"
            :class="{ 'is-open': isMenuOpen(item.index) }"
            :style="{ '--child-count': item.children.length }"
          >
            <button
              v-for="child in item.children"
              :key="child.index"
              class="side-nav__child"
              :class="{ 'is-active': activeMenu === child.index }"
              type="button"
              @click="navigateTo(child.index)"
            >
              {{ child.title }}
            </button>
          </div>
        </div>
      </nav>
    </el-aside>

    <el-container>
      <el-header class="main-header">
        <div class="main-header__spacer"></div>

        <div class="main-header__actions">
          <button class="icon-trigger" type="button" aria-label="通知">
            <el-icon><Bell /></el-icon>
            <span class="notify-dot"></span>
          </button>
          <el-dropdown trigger="click">
            <button class="login-mark" type="button" :title="userLabel">
              <el-avatar :size="30" class="user-avatar">{{ userLabel.slice(0, 1) }}</el-avatar>
              <span class="login-mark__text">{{ userLabel }}</span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ authStore.user?.username }}</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content">
        <div class="route-progress" :class="{ 'is-loading': pageLoading }"></div>
        <RouterView v-slot="{ Component }">
          <Transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.main-layout {
  width: 100%;
  height: 100vh;
  background: #f6f8fb;
}

.main-layout__aside {
  display: flex;
  flex-direction: column;
  background: #141b2d;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
}

.main-layout__aside :deep(*::-webkit-scrollbar-thumb) {
  background: rgba(255, 255, 255, 0.28);
  background-clip: content-box;
  border: 3px solid transparent;
}

.main-layout__aside :deep(*::-webkit-scrollbar-thumb:hover) {
  background: rgba(255, 255, 255, 0.42);
  background-clip: content-box;
}

.brand {
  display: flex;
  gap: 10px;
  align-items: center;
  height: 60px;
  padding: 0 16px;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.brand__mark {
  width: 34px;
  height: 34px;
  border-radius: 8px;
}

.brand__title {
  font-size: 18px;
  font-weight: 800;
  line-height: 1.18;
}

.brand__subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: #9ba9bd;
}

.side-nav {
  flex: 1;
  padding: 10px 0 16px;
  overflow-y: auto;
}

.side-nav__group {
  margin: 3px 12px;
}

.side-nav__item,
.side-nav__child {
  display: flex;
  width: 100%;
  height: 46px;
  padding: 0 18px;
  font-size: 15px;
  font-weight: 650;
  color: #c9d4e5;
  align-items: center;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 8px;
  outline: none;
  transition:
    background-color 0.16s ease,
    color 0.16s ease,
    transform 0.16s ease;
}

.side-nav__item {
  gap: 12px;
  justify-content: flex-start;
}

.side-nav__item:hover,
.side-nav__child:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.side-nav__item.is-active:not(.is-open),
.side-nav__child.is-active {
  color: #fff;
  background: #2f6fed;
  box-shadow: 0 8px 18px rgba(47, 111, 237, 0.25);
}

.side-nav__item.is-open {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.side-nav__arrow {
  width: 8px;
  height: 8px;
  margin-left: auto;
  border-right: 1.5px solid currentcolor;
  border-bottom: 1.5px solid currentcolor;
  transform: rotate(45deg);
  transition: transform 0.2s ease;
}

.side-nav__item.is-open .side-nav__arrow {
  transform: rotate(225deg);
}

.side-nav__children {
  max-height: 0;
  overflow: hidden;
  opacity: 0;
  transform: translateY(-4px);
  transition:
    max-height 0.26s cubic-bezier(0.16, 1, 0.3, 1),
    opacity 0.18s ease-out,
    transform 0.22s ease-out,
    padding 0.2s ease-out;
}

.side-nav__children.is-open {
  max-height: calc(var(--child-count) * 46px + 12px);
  padding: 3px 0 6px;
  opacity: 1;
  transform: translateY(0);
}

.side-nav__child {
  height: 42px;
  padding-left: 50px;
  font-weight: 650;
}

.main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 22px;
  color: #c9d4e5;
  background: #141b2d;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.main-header__spacer {
  flex: 1;
}

.main-header__actions {
  display: flex;
  gap: 14px;
  align-items: center;
}

.icon-trigger,
.login-mark {
  position: relative;
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 8px;
  transition:
    background 0.18s ease,
    opacity 0.18s ease;
}

.icon-trigger:hover,
.login-mark:hover {
  background: rgba(255, 255, 255, 0.08);
}

.icon-trigger {
  width: 34px;
  height: 34px;
  justify-content: center;
  padding: 0;
  color: #dce6f5;
  font-size: 18px;
}

.notify-dot {
  position: absolute;
  top: 7px;
  right: 8px;
  width: 6px;
  height: 6px;
  background: #4f8cff;
  border: 2px solid #141b2d;
  border-radius: 999px;
}

.login-mark {
  gap: 9px;
  height: 38px;
  padding: 0 6px;
  color: #ffffff;
}

.user-avatar {
  font-weight: 700;
  color: #ffffff;
  background: #2f6fed;
}

.login-mark__text {
  max-width: 96px;
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  color: #ffffff;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-content {
  position: relative;
  height: calc(100vh - 60px);
  padding: 0;
  overflow: auto;
}

.route-progress {
  position: sticky;
  top: 0;
  z-index: 10;
  width: 100%;
  height: 2px;
  pointer-events: none;
  background: transparent;
}

.route-progress::after {
  display: block;
  width: 0;
  height: 100%;
  content: "";
  background: #2f6fed;
  opacity: 0;
  transition:
    width 0.26s ease,
    opacity 0.18s ease;
}

.route-progress.is-loading::after {
  width: 100%;
  opacity: 1;
}

.page-fade-enter-active,
.page-fade-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.2s ease;
}

.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
