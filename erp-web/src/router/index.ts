import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import LoginView from '@/modules/auth/views/LoginView.vue';
import MainLayout from '@/layouts/MainLayout.vue';
import DashboardView from '@/modules/dashboard/views/DashboardView.vue';
import NotFoundView from '@/modules/system/views/NotFoundView.vue';
import UserManageView from '@/modules/system/users/views/UserManageView.vue';
import RoleManageView from '@/modules/system/roles/views/RoleManageView.vue';
import DeptManageView from '@/modules/system/depts/views/DeptManageView.vue';
import PermissionManageView from '@/modules/system/permissions/views/PermissionManageView.vue';
import ProductCategoryManageView from '@/modules/product/categories/views/ProductCategoryManageView.vue';
import ProductManageView from '@/modules/product/products/views/ProductManageView.vue';
import WarehouseManageView from '@/modules/warehouse/warehouses/views/WarehouseManageView.vue';
import WarehouseStockManageView from '@/modules/warehouse/stocks/views/WarehouseStockManageView.vue';
import StockBillManageView from '@/modules/warehouse/stock-bills/views/StockBillManageView.vue';
import SupplierManageView from '@/modules/purchase/suppliers/views/SupplierManageView.vue';
import SupplierProductManageView from '@/modules/purchase/supplier-products/views/SupplierProductManageView.vue';
import PurchaseOrderManageView from '@/modules/purchase/orders/views/PurchaseOrderManageView.vue';
import CustomerManageView from '@/modules/sales/customers/views/CustomerManageView.vue';
import SalesOrderManageView from '@/modules/sales/orders/views/SalesOrderManageView.vue';
import ModulePlaceholderView from '@/shared/views/ModulePlaceholderView.vue';

function placeholder(title: string, module: string, description: string) {
  return {
    component: ModulePlaceholderView,
    meta: {
      title,
      module,
      description,
    },
  };
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: {
      public: true,
      title: '登录',
    },
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: DashboardView,
        meta: {
          title: '工作台',
        },
      },
      { path: 'system', redirect: '/system/users' },
      {
        path: 'system/users',
        name: 'system-users',
        component: UserManageView,
        meta: {
          title: '用户管理',
        },
      },
      {
        path: 'system/roles',
        name: 'system-roles',
        component: RoleManageView,
        meta: {
          title: '角色管理',
        },
      },
      {
        path: 'system/depts',
        name: 'system-depts',
        component: DeptManageView,
        meta: {
          title: '部门管理',
        },
      },
      {
        path: 'system/permissions',
        name: 'system-permissions',
        component: PermissionManageView,
        meta: {
          title: '权限码配置',
        },
      },
      { path: 'product', redirect: '/product/categories' },
      {
        path: 'product/categories',
        name: 'product-categories',
        component: ProductCategoryManageView,
        meta: {
          title: '产品分类',
        },
      },
      {
        path: 'product/products',
        name: 'product-products',
        component: ProductManageView,
        meta: {
          title: '产品档案',
        },
      },
      { path: 'warehouse', redirect: '/warehouse/warehouses' },
      {
        path: 'warehouse/warehouses',
        name: 'warehouse-list',
        component: WarehouseManageView,
        meta: {
          title: '仓库管理',
        },
      },
      {
        path: 'warehouse/stocks',
        name: 'warehouse-stocks',
        component: WarehouseStockManageView,
        meta: {
          title: '库存管理',
        },
      },
      { path: 'warehouse/stock-bills', redirect: '/warehouse/inbound-bills' },
      {
        path: 'warehouse/inbound-bills',
        name: 'warehouse-inbound-bills',
        component: StockBillManageView,
        meta: {
          title: '入库单',
          stockDirection: 'INBOUND',
        },
      },
      {
        path: 'warehouse/outbound-bills',
        name: 'warehouse-outbound-bills',
        component: StockBillManageView,
        meta: {
          title: '出库单',
          stockDirection: 'OUTBOUND',
        },
      },
      { path: 'purchase', redirect: '/purchase/suppliers' },
      {
        path: 'purchase/suppliers',
        name: 'purchase-suppliers',
        component: SupplierManageView,
        meta: {
          title: '供应商管理',
        },
      },
      {
        path: 'purchase/supplier-products',
        name: 'purchase-supplier-products',
        component: SupplierProductManageView,
        meta: {
          title: '供货产品',
        },
      },
      {
        path: 'purchase/orders',
        name: 'purchase-orders',
        component: PurchaseOrderManageView,
        meta: {
          title: '采购订单',
        },
      },
      { path: 'sales', redirect: '/sales/customers' },
      {
        path: 'sales/customers',
        name: 'sales-customers',
        component: CustomerManageView,
        meta: {
          title: '客户管理',
        },
      },
      {
        path: 'sales/orders',
        name: 'sales-orders',
        component: SalesOrderManageView,
        meta: {
          title: '销售订单',
        },
      },
      { path: 'ai', redirect: '/ai/rag' },
      {
        path: 'ai/rag',
        name: 'ai-rag',
        ...placeholder('知识库问答', '智能助手', '管理企业文档和 RAG 问答入口'),
      },
      {
        path: 'ai/assistant',
        name: 'ai-assistant',
        ...placeholder('智能经营助手', '智能助手', '承载自然语言业务查询 业务建议 运维建议 决策辅助和后续标准 Workflow 入口'),
      },
      {
        path: 'ai/audit-logs',
        name: 'ai-audit-logs',
        ...placeholder('AI 调用审计', '智能助手', '查看用户问题 Tool 参数 权限结果和返回摘要'),
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFoundView,
    meta: {
      public: true,
      title: '页面不存在',
    },
  },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach(async to => {
  const authStore = useAuthStore();

  if (!to.meta.public && authStore.token && !authStore.initialized) {
    await authStore.loadCurrentUser().catch(() => {
      authStore.clearSession();
    });
  }

  if (!to.meta.public && !authStore.isLoggedIn) {
    return {
      path: '/login',
      query: {
        redirect: to.fullPath,
      },
    };
  }

  if (to.name === 'login' && authStore.isLoggedIn) {
    return '/dashboard';
  }

  document.title = `${String(to.meta.title || '管理系统')} - 启衡 ERP`;

  return true;
});
