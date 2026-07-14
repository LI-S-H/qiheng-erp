<script setup lang="ts">
import {
  Home,
  Lock,
  Package,
  Warehouse,
  ShoppingCart,
  Tag,
  MessageCircle,
  CalendarClock,
  Bell,
  LogOut,
  AlertCircle,
  Loader2,
  ChevronRight,
} from 'lucide-vue-next';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import logoUrl from '@/assets/brand/qiheng-logo.svg';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import CollapseReveal from '@/components/common/CollapseReveal.vue';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { getDashboardNotifications } from '@/modules/dashboard/api';
import type { DashboardNotificationPopover, DashboardTodoItem } from '@/modules/dashboard/types';

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
const logoutConfirmOpen = ref(false);
const pageLoading = ref(false);
const notificationOpen = ref(false);
const userMenuOpen = ref(false);
const notificationLoading = ref(false);
const notificationError = ref('');
const notificationData = ref<DashboardNotificationPopover | null>(null);
let notificationLoadedAt = 0;
let pageLoadingTimer: number | undefined;
let pageLoadingFrame: number | undefined;

const menus: MenuItem[] = [
  { index: '/dashboard', title: '工作台', icon: Home },
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
    icon: Package,
    permission: 'product:query',
    children: [
      { index: '/product/categories', title: '产品分类' },
      { index: '/product/products', title: '产品档案' },
    ],
  },
  {
    index: '/warehouse',
    title: '仓储库存',
    icon: Warehouse,
    permission: 'warehouse:query',
    children: [
      { index: '/warehouse/warehouses', title: '仓库管理' },
      { index: '/warehouse/stocks', title: '库存管理' },
      { index: '/warehouse/inbound-bills', title: '入库单' },
      { index: '/warehouse/outbound-bills', title: '出库单' },
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
    icon: Tag,
    permission: 'sales:query',
    children: [
      { index: '/sales/customers', title: '客户管理' },
      { index: '/sales/orders', title: '销售订单' },
    ],
  },
  {
    index: '/ai',
    title: '智能助手',
    icon: MessageCircle,
    permission: 'ai:query:stock',
    children: [
      { index: '/ai/assistant', title: '智能经营助手' },
      { index: '/ai/tasks', title: '经营任务中心', icon: CalendarClock },
    ],
  },
];

const visibleMenus = computed(() => {
  return menus.filter(item => {
    if (!item.permission) return true;
    return authStore.hasPermission(item.permission);
  });
});

const userLabel = computed(() => {
  const user = authStore.user;
  if (!user) return '未登录';
  return authStore.displayName;
});

const currentSection = computed(() => {
  return visibleMenus.value.find(item => item.index === route.path || item.children?.some(child => child.index === route.path));
});

const currentPageTitle = computed(() => String(route.meta.title || currentSection.value?.title || '启衡 ERP'));

function isMenuActive(item: MenuItem) {
  if (item.index === activeMenu.value) return true;
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

async function loadNotifications(force = false) {
  if (notificationLoading.value) return;
  if (!force && notificationData.value && Date.now() - notificationLoadedAt < 60_000) return;
  notificationLoading.value = true;
  notificationError.value = '';
  try {
    notificationData.value = await getDashboardNotifications();
    notificationLoadedAt = Date.now();
  } catch (error) {
    notificationError.value = error instanceof Error ? error.message : '通知加载失败，请稍后重试';
  } finally {
    notificationLoading.value = false;
  }
}

function handleNotificationOpen(open: boolean) {
  notificationOpen.value = open;
  if (open) {
    userMenuOpen.value = false;
    void loadNotifications();
  }
}

function handleUserMenuOpen(open: boolean) {
  userMenuOpen.value = open;
  if (open) notificationOpen.value = false;
}

function handleNotificationItem(item: DashboardTodoItem) {
  notificationOpen.value = false;
  void router.push(item.completionMode === 'TRACKED' ? '/dashboard' : item.route || '/dashboard');
}

function openWorkbench() {
  notificationOpen.value = false;
  void router.push('/dashboard');
}

function openCurrentParent(path: string) {
  const parent = visibleMenus.value.find(item => item.children?.some(child => child.index === path));
  if (parent) openedMenus.add(parent.index);
}

function showPageLoading() {
  pageLoading.value = true;
  window.clearTimeout(pageLoadingTimer);
  if (pageLoadingFrame !== undefined) window.cancelAnimationFrame(pageLoadingFrame);

  nextTick(() => {
    pageLoadingFrame = window.requestAnimationFrame(() => {
      pageLoadingTimer = window.setTimeout(() => {
        pageLoading.value = false;
      }, 220);
    });
  });
}

function handleLogout() {
  logoutConfirmOpen.value = true;
}

async function confirmLogout() {
  logoutConfirmOpen.value = false;
  await authStore.logout();
  router.replace('/login');
}

watch(
  () => route.path,
  (path, previousPath) => {
    openCurrentParent(path);
    if (previousPath !== undefined && previousPath !== path) showPageLoading();
  },
  { immediate: true },
);

onMounted(() => {
  void loadNotifications();
});

onBeforeUnmount(() => {
  window.clearTimeout(pageLoadingTimer);
  if (pageLoadingFrame !== undefined) window.cancelAnimationFrame(pageLoadingFrame);
});
</script>

<template>
  <div class="app-shell flex h-screen bg-background text-foreground">
    <!-- Sidebar -->
    <aside class="app-sidebar flex shrink-0 flex-col bg-sidebar border-r border-sidebar-border">
      <!-- Brand -->
      <div class="app-shell__brand flex items-center gap-3 px-4 text-sidebar-foreground border-b border-sidebar-border">
        <img class="h-9 w-9 rounded-lg" :src="logoUrl" alt="启衡 ERP" />
        <div class="min-w-0">
          <div class="text-[18px] font-bold leading-tight tracking-tight">启衡 ERP</div>
          <div class="mt-1 truncate text-[13px] font-medium text-sidebar-foreground/55">进销存智能管理台</div>
        </div>
      </div>

      <!-- Navigation -->
      <nav class="flex-1 overflow-y-auto px-2.5 py-3" aria-label="主导航">
        <div v-for="item in visibleMenus" :key="item.index" class="mb-1">
          <button
            class="relative flex h-11 w-full items-center gap-3 rounded-md px-3 text-[15px] font-semibold text-sidebar-foreground/78 transition-colors hover:bg-sidebar-accent hover:text-sidebar-foreground cursor-pointer"
            :class="{
              'bg-sidebar-accent text-sidebar-foreground': isMenuActive(item) || isMenuOpen(item.index),
            }"
            type="button"
            @click="toggleMenu(item)"
          >
            <span
              v-if="isMenuActive(item) && !item.children?.length"
              class="absolute inset-y-2 left-0 w-0.5 rounded-full bg-sidebar-primary"
            />
            <component :is="item.icon" class="h-4 w-4 shrink-0" />
            <span class="flex-1 text-left">{{ item.title }}</span>
            <span
              v-if="item.children?.length"
              class="ml-auto h-1.5 w-1.5 rotate-45 border-r border-b border-current transition-transform"
              :class="{ 'rotate-[225deg]': isMenuOpen(item.index) }"
            />
          </button>

          <!-- Children -->
          <CollapseReveal
            v-if="item.children?.length"
            :open="isMenuOpen(item.index)"
          >
            <div class="py-1">
              <RouterLink
                v-for="child in item.children"
                :key="child.index"
                :to="child.index"
                :aria-current="activeMenu === child.index ? 'page' : undefined"
                :data-menu-path="child.index"
                class="app-sidebar__submenu-link relative flex h-10 w-full items-center rounded-md pl-10 pr-3 text-[15px] font-medium transition-colors cursor-pointer"
                :class="{
                  'bg-sidebar-accent text-sidebar-foreground font-semibold': activeMenu === child.index,
                }"
              >
                <span
                  v-if="activeMenu === child.index"
                  class="absolute inset-y-2 left-0 w-0.5 rounded-full bg-sidebar-primary"
                />
                {{ child.title }}
              </RouterLink>
            </div>
          </CollapseReveal>
        </div>
      </nav>
    </aside>

    <!-- Main content -->
    <div class="flex flex-col flex-1 min-w-0">
      <!-- Header -->
      <header class="app-shell__header flex items-center justify-between bg-sidebar px-5 text-sidebar-foreground border-b border-sidebar-border">
        <div class="min-w-0">
          <div class="text-[13px] font-medium text-sidebar-foreground/55">{{ currentSection?.title || '工作台' }}</div>
          <div class="mt-0.5 truncate text-[16px] font-semibold">{{ currentPageTitle }}</div>
        </div>
        <div class="flex items-center gap-3">
          <!-- Notification bell -->
          <Popover :open="notificationOpen" @update:open="handleNotificationOpen">
            <PopoverTrigger as-child>
              <button class="relative flex h-9 w-9 items-center justify-center rounded-md text-sidebar-foreground/72 transition-colors hover:bg-sidebar-accent hover:text-sidebar-foreground cursor-pointer" type="button" aria-label="待处理通知" data-notification-trigger>
                <Bell class="h-4 w-4" />
                <span v-if="notificationData?.pendingCount" class="absolute -right-1 -top-1 min-w-4 rounded-full bg-destructive px-1 text-center text-[10px] font-bold leading-4 text-destructive-foreground ring-2 ring-sidebar">
                  {{ notificationData.pendingCount > 99 ? '99+' : notificationData.pendingCount }}
                </span>
              </button>
            </PopoverTrigger>
            <PopoverContent align="end" :side-offset="10" class="w-[380px] overflow-hidden p-0" data-notification-popover>
              <div class="flex items-start justify-between border-b px-4 py-3">
                <div>
                  <h2 class="text-sm font-semibold">待处理事项</h2>
                  <p class="mt-0.5 text-xs text-muted-foreground">业务状态汇总，不代表未读消息</p>
                </div>
                <span v-if="notificationData" class="rounded-full bg-muted px-2 py-0.5 text-xs font-medium">{{ notificationData.pendingCount }} 项</span>
              </div>
              <div v-if="notificationLoading && !notificationData" class="flex h-40 items-center justify-center gap-2 text-sm text-muted-foreground">
                <Loader2 class="h-4 w-4 animate-spin" /> 正在加载
              </div>
              <div v-else-if="notificationError && !notificationData" class="flex h-40 flex-col items-center justify-center gap-3 px-6 text-center">
                <AlertCircle class="h-5 w-5 text-destructive" />
                <p class="text-sm text-muted-foreground">{{ notificationError }}</p>
                <button class="text-sm font-medium text-primary hover:underline" type="button" @click="loadNotifications(true)">重新加载</button>
              </div>
              <div v-else-if="!notificationData?.items.length" class="flex h-40 flex-col items-center justify-center gap-2 text-sm text-muted-foreground">
                <Bell class="h-5 w-5" /> 暂无待处理事项
              </div>
              <div v-else class="max-h-[420px] overflow-y-auto py-1">
                <button v-for="item in notificationData.items" :key="item.todoId" class="flex w-full items-start gap-3 border-b px-4 py-3 text-left transition-colors last:border-b-0 hover:bg-muted/60" type="button" @click="handleNotificationItem(item)">
                  <span class="mt-1.5 h-2 w-2 shrink-0 rounded-full" :class="item.priority === 'HIGH' ? 'bg-destructive' : item.priority === 'MEDIUM' ? 'bg-amber-500' : 'bg-muted-foreground'" />
                  <span class="min-w-0 flex-1">
                    <span class="flex items-center justify-between gap-2">
                      <span class="truncate text-sm font-medium">{{ item.title }}</span>
                      <span class="shrink-0 text-xs font-semibold text-muted-foreground">{{ item.count }} 项</span>
                    </span>
                    <span class="mt-1 line-clamp-2 block text-xs leading-5 text-muted-foreground">{{ item.description }}</span>
                  </span>
                  <ChevronRight class="mt-1 h-4 w-4 shrink-0 text-muted-foreground" />
                </button>
              </div>
              <div class="flex items-center justify-between border-t bg-muted/30 px-4 py-2.5">
                <span class="text-xs text-muted-foreground">{{ notificationData?.hasMore ? '工作台还有更多事项' : '在工作台查看完整依据' }}</span>
                <button class="text-sm font-medium text-primary hover:underline" type="button" @click="openWorkbench">前往工作台</button>
              </div>
            </PopoverContent>
          </Popover>

          <!-- User dropdown -->
          <DropdownMenu :open="userMenuOpen" @update:open="handleUserMenuOpen">
            <DropdownMenuTrigger as-child>
              <button class="flex h-10 items-center gap-2 rounded-md px-2 text-sidebar-foreground transition-colors hover:bg-sidebar-accent cursor-pointer" type="button" :title="userLabel">
                <Avatar class="h-7 w-7 bg-sidebar-primary">
                  <AvatarFallback class="bg-sidebar-primary text-xs font-semibold text-sidebar-primary-foreground">{{ userLabel.slice(0, 1) }}</AvatarFallback>
                </Avatar>
                <span class="max-w-[132px] overflow-hidden text-ellipsis whitespace-nowrap text-[15px] font-semibold">{{ userLabel }}</span>
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" class="w-48">
              <DropdownMenuLabel>{{ authStore.user?.username }}</DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuItem @click="handleLogout" class="cursor-pointer text-destructive focus:text-destructive">
                <LogOut class="mr-2 h-4 w-4" />
                退出登录
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      <!-- Page content -->
      <main class="relative flex-1 overflow-auto bg-background">
        <RouterView v-slot="{ Component, route: viewRoute }">
          <Transition name="page-view" mode="out-in">
            <component :is="Component" :key="viewRoute.fullPath" />
          </Transition>
        </RouterView>
        <Transition name="page-loading">
          <div v-if="pageLoading" class="page-loading-mask" data-page-loading aria-live="polite" aria-label="页面加载中">
            <div class="page-loading-indicator">
              <span class="page-loading-spinner" aria-hidden="true" />
              页面加载中
            </div>
          </div>
        </Transition>
      </main>
    </div>

    <!-- Logout confirm dialog -->
    <ConfirmDialog
      :open="logoutConfirmOpen"
      title="退出登录"
      description="确认退出当前登录吗？"
      confirm-text="退出"
      cancel-text="取消"
      variant="warning"
      @update:open="logoutConfirmOpen = $event"
      @confirm="confirmLogout"
    />
  </div>
</template>

<style scoped>
.app-sidebar {
  width: var(--app-shell-sidebar-width);
}

.app-shell__brand,
.app-shell__header {
  height: var(--app-shell-header-height);
}

.app-sidebar__submenu-link {
  color: color-mix(in srgb, var(--sidebar-foreground) 78%, var(--sidebar));
}

.app-sidebar__submenu-link:hover,
.app-sidebar__submenu-link:focus-visible,
.app-sidebar__submenu-link[aria-current='page'] {
  color: var(--sidebar-foreground);
}

.app-sidebar__submenu-link:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--sidebar-ring) 88%, white);
  outline-offset: -2px;
}

@media (max-width: 760px) {
  .app-sidebar {
    display: none;
  }
}
</style>
