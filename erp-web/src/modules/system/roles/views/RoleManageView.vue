<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  CircleCheck,
  CircleClose,
  CirclePlus,
  Delete,
  EditPen,
  RefreshRight,
  Search,
  Setting,
} from '@element-plus/icons-vue';
import type { RoleStatus, SystemRoleFormPayload, SystemRoleListItem, SystemRoleQuery } from '../types';

interface PermissionGroup {
  group: string;
  codes: Array<{
    code: string;
    label: string;
  }>;
}

const permissionGroups: PermissionGroup[] = [
  {
    group: '系统权限',
    codes: [
      { code: 'system:user:query', label: '用户查询' },
      { code: 'system:user:manage', label: '用户维护' },
      { code: 'system:role:query', label: '角色查询' },
      { code: 'system:role:manage', label: '角色维护' },
    ],
  },
  {
    group: '商品与库存',
    codes: [
      { code: 'product:query', label: '产品查询' },
      { code: 'product:manage', label: '产品维护' },
      { code: 'warehouse:query', label: '库存查询' },
      { code: 'warehouse:manage', label: '库存维护' },
    ],
  },
  {
    group: '采购销售',
    codes: [
      { code: 'supplier:query', label: '供应商查询' },
      { code: 'purchase:query', label: '采购查询' },
      { code: 'purchase:create', label: '采购创建' },
      { code: 'customer:query', label: '客户查询' },
      { code: 'sales:query', label: '销售查询' },
      { code: 'sales:create', label: '销售创建' },
    ],
  },
  {
    group: '智能助手',
    codes: [
      { code: 'ai:query:stock', label: '库存问答' },
      { code: 'ai:query:sales', label: '销售问答' },
      { code: 'ai:query:purchase', label: '采购问答' },
      { code: 'ai:ops:suggest', label: '运维建议' },
      { code: 'ai:decision:suggest', label: '决策建议' },
    ],
  },
];

const initialRoles: SystemRoleListItem[] = [
  {
    roleId: '1900000000000001001',
    roleCode: 'SUPER_ADMIN',
    roleName: '超级管理员',
    permissionCodes: ['*'],
    status: 1,
    remark: '拥有系统全部访问和维护权限',
    userCount: 1,
    createdAt: '2026-06-05 20:30:00',
    updatedAt: '2026-06-08 09:12:30',
  },
  {
    roleId: '1900000000000001002',
    roleCode: 'SYSTEM_ADMIN',
    roleName: '系统管理员',
    permissionCodes: ['system:user:query', 'system:user:manage', 'system:role:query', 'system:role:manage'],
    status: 1,
    remark: '维护账号、角色和基础权限配置',
    userCount: 0,
    createdAt: '2026-06-05 21:00:00',
    updatedAt: '2026-06-07 16:20:00',
  },
  {
    roleId: '1900000000000001003',
    roleCode: 'BUSINESS_MANAGER',
    roleName: '业务主管',
    permissionCodes: ['product:query', 'supplier:query', 'purchase:query', 'customer:query', 'sales:query'],
    status: 1,
    remark: '查看产品、采购和销售主线数据',
    userCount: 2,
    createdAt: '2026-06-06 10:18:22',
    updatedAt: '2026-06-07 13:00:00',
  },
  {
    roleId: '1900000000000001004',
    roleCode: 'WAREHOUSE_OPERATOR',
    roleName: '仓库操作员',
    permissionCodes: ['product:query', 'warehouse:query', 'warehouse:manage', 'ai:query:stock'],
    status: 1,
    remark: '处理仓储库存查询和出入库相关操作',
    userCount: 1,
    createdAt: '2026-06-06 11:05:19',
    updatedAt: '2026-06-06 11:05:19',
  },
  {
    roleId: '1900000000000001005',
    roleCode: 'AI_ANALYST',
    roleName: '智能分析员',
    permissionCodes: ['ai:query:stock', 'ai:query:sales', 'ai:query:purchase', 'ai:decision:suggest'],
    status: 0,
    remark: '用于后续智能经营分析试点',
    userCount: 0,
    createdAt: '2026-06-07 09:40:00',
    updatedAt: '2026-06-07 09:40:00',
  },
];

const roles = ref<SystemRoleListItem[]>([...initialRoles]);
const loading = ref(false);
const selectedRows = ref<SystemRoleListItem[]>([]);
const roleDialogVisible = ref(false);
const permissionPreviewVisible = ref(false);
const permissionDialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingRoleId = ref('');
const roleFormRef = ref<FormInstance>();
const permissionFormRef = ref<FormInstance>();
const permissionPreviewRole = ref<SystemRoleListItem | null>(null);
const permissionEditingRole = ref<SystemRoleListItem | null>(null);

const query = reactive<SystemRoleQuery>({
  roleCode: '',
  roleName: '',
  status: '',
  pageNum: 1,
  pageSize: 10,
});

const roleForm = reactive<SystemRoleFormPayload>({
  roleCode: '',
  roleName: '',
  permissionCodes: [],
  status: 1,
  remark: '',
});

const permissionForm = reactive({
  permissionCodes: [] as string[],
});

const roleRules: FormRules<SystemRoleFormPayload> = {
  roleCode: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_]{2,63}$/, message: '角色编码需为大写字母、数字或下划线', trigger: 'blur' },
  ],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  permissionCodes: [{ required: true, message: '请选择权限码', trigger: 'change' }],
  status: [{ required: true, message: '请选择启用状态', trigger: 'change' }],
};

const permissionRules: FormRules<{ permissionCodes: string[] }> = {
  permissionCodes: [{ required: true, message: '请选择权限码', trigger: 'change' }],
};

const filteredRoles = computed(() => {
  const roleCode = query.roleCode?.trim().toLowerCase();
  const roleName = query.roleName?.trim().toLowerCase();

  return roles.value.filter(role => {
    const matchRoleCode = !roleCode || role.roleCode.toLowerCase().includes(roleCode);
    const matchRoleName = !roleName || role.roleName.toLowerCase().includes(roleName);
    const matchStatus = query.status === '' || role.status === Number(query.status);

    return matchRoleCode && matchRoleName && matchStatus;
  });
});

const pagedRoles = computed(() => {
  const start = (query.pageNum - 1) * query.pageSize;
  return filteredRoles.value.slice(start, start + query.pageSize);
});

const enabledCount = computed(() => roles.value.filter(role => role.status === 1).length);
const permissionTotal = computed(() => new Set(roles.value.flatMap(role => role.permissionCodes)).size);
const boundUserTotal = computed(() => roles.value.reduce((total, role) => total + role.userCount, 0));

function formatTableTime(value: string) {
  return value.slice(5, 16);
}

function getPermissionLabel(code: string) {
  if (code === '*') {
    return '全部权限';
  }

  return permissionGroups.flatMap(group => group.codes).find(item => item.code === code)?.label || code;
}

function hasAllPermissions(row: SystemRoleListItem | null) {
  return Boolean(row?.permissionCodes.includes('*'));
}

function getPermissionSummary(row: SystemRoleListItem) {
  if (hasAllPermissions(row)) {
    return '全部权限';
  }

  return `${row.permissionCodes.length} 项权限码`;
}

function getPermissionGroups(row: SystemRoleListItem | null) {
  if (!row) {
    return [];
  }

  if (hasAllPermissions(row)) {
    return permissionGroups;
  }

  const selectedCodes = new Set(row.permissionCodes);
  return permissionGroups
    .map(group => ({
      group: group.group,
      codes: group.codes.filter(item => selectedCodes.has(item.code)),
    }))
    .filter(group => group.codes.length > 0);
}

function resetRoleForm() {
  editingRoleId.value = '';
  roleForm.roleCode = '';
  roleForm.roleName = '';
  roleForm.permissionCodes = [];
  roleForm.status = 1;
  roleForm.remark = '';
}

function handleSearch() {
  query.pageNum = 1;
}

function handleReset() {
  query.roleCode = '';
  query.roleName = '';
  query.status = '';
  query.pageNum = 1;
}

function refreshList() {
  loading.value = true;
  window.setTimeout(() => {
    loading.value = false;
    ElMessage.success('列表已刷新');
  }, 260);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetRoleForm();
  roleDialogVisible.value = true;
}

function openEditDialog(row: SystemRoleListItem) {
  dialogMode.value = 'edit';
  editingRoleId.value = row.roleId;
  roleForm.roleCode = row.roleCode;
  roleForm.roleName = row.roleName;
  roleForm.permissionCodes = [...row.permissionCodes];
  roleForm.status = row.status;
  roleForm.remark = row.remark;
  roleDialogVisible.value = true;
}

async function submitRoleForm() {
  await roleFormRef.value?.validate();

  const now = '2026-06-08 10:00:00';

  if (dialogMode.value === 'create') {
    roles.value = [
      {
        roleId: String(1900000000000001100 + roles.value.length),
        roleCode: roleForm.roleCode,
        roleName: roleForm.roleName,
        permissionCodes: [...roleForm.permissionCodes],
        status: roleForm.status,
        remark: roleForm.remark,
        userCount: 0,
        createdAt: now,
        updatedAt: now,
      },
      ...roles.value,
    ];
    ElMessage.success('角色已创建');
  } else {
    roles.value = roles.value.map(role =>
      role.roleId === editingRoleId.value
        ? {
            ...role,
            roleCode: roleForm.roleCode,
            roleName: roleForm.roleName,
            permissionCodes: [...roleForm.permissionCodes],
            status: roleForm.status,
            remark: roleForm.remark,
            updatedAt: now,
          }
        : role,
    );
    ElMessage.success('角色已更新');
  }

  roleDialogVisible.value = false;
}

function openPermissionDialog(row: SystemRoleListItem) {
  permissionEditingRole.value = row;
  permissionForm.permissionCodes = [...row.permissionCodes];
  permissionDialogVisible.value = true;
}

function openPermissionPreview(row: SystemRoleListItem) {
  permissionPreviewRole.value = row;
  permissionPreviewVisible.value = true;
}

async function submitPermissionForm() {
  await permissionFormRef.value?.validate();

  const current = permissionEditingRole.value;
  if (!current) {
    return;
  }

  roles.value = roles.value.map(role =>
    role.roleId === current.roleId
      ? {
          ...role,
          permissionCodes: [...permissionForm.permissionCodes],
          updatedAt: '2026-06-08 10:00:00',
        }
      : role,
  );
  permissionDialogVisible.value = false;
  ElMessage.success('权限码已更新');
}

function handleSelectionChange(rows: SystemRoleListItem[]) {
  selectedRows.value = rows;
}

function ensureSelectedRows(actionName: string) {
  if (selectedRows.value.length > 0) {
    return true;
  }

  ElMessage.warning(`请先选择需要${actionName}的角色`);
  return false;
}

async function handleBatchStatus(status: RoleStatus) {
  const action = status === 1 ? '启用' : '停用';

  if (!ensureSelectedRows(action)) {
    return;
  }

  try {
    await ElMessageBox.confirm(`确认${action}已选择的 ${selectedRows.value.length} 个角色吗`, `批量${action}`, {
      confirmButtonText: action,
      cancelButtonText: '取消',
      type: status === 1 ? 'success' : 'warning',
    });
  } catch {
    return;
  }

  const selectedIds = new Set(selectedRows.value.map(role => role.roleId));
  roles.value = roles.value.map(role =>
    selectedIds.has(role.roleId) ? { ...role, status, updatedAt: '2026-06-08 10:00:00' } : role,
  );
  selectedRows.value = [];
  ElMessage.success(`已批量${action}`);
}

async function handleBatchDelete() {
  if (!ensureSelectedRows('删除')) {
    return;
  }

  const inUseRoles = selectedRows.value.filter(role => role.userCount > 0);
  if (inUseRoles.length > 0) {
    ElMessage.warning('存在已绑定用户的角色，请先解绑后再删除');
    return;
  }

  try {
    await ElMessageBox.confirm(`确认删除已选择的 ${selectedRows.value.length} 个角色吗`, '批量删除角色', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }

  const selectedIds = new Set(selectedRows.value.map(role => role.roleId));
  roles.value = roles.value.filter(role => !selectedIds.has(role.roleId));
  selectedRows.value = [];
  ElMessage.success('已批量删除角色');
}
</script>

<template>
  <section class="page-shell role-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">角色管理</h1>
        <p class="page-subtitle">维护角色编码、权限码集合、启用状态和使用情况</p>
      </div>
    </div>

    <div class="metric-grid">
      <div class="metric-item">
        <span>角色总数</span>
        <strong>{{ roles.length }}</strong>
      </div>
      <div class="metric-item">
        <span>启用角色</span>
        <strong>{{ enabledCount }}</strong>
      </div>
      <div class="metric-item">
        <span>权限码覆盖</span>
        <strong>{{ permissionTotal }}</strong>
      </div>
      <div class="metric-item">
        <span>绑定用户数</span>
        <strong>{{ boundUserTotal }}</strong>
      </div>
    </div>

    <section class="module-panel filter-panel">
      <el-form class="filter-form" :model="query" label-width="72px">
        <el-form-item label="角色编码">
          <el-input v-model="query.roleCode" clearable placeholder="如 SUPER_ADMIN" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="角色名称">
          <el-input v-model="query.roleName" clearable placeholder="如 超级管理员" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable value-on-clear="" placeholder="全部状态">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <div class="filter-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="RefreshRight" @click="handleReset">重置</el-button>
        </div>
      </el-form>
    </section>

    <section class="module-panel table-panel">
      <div class="table-toolbar">
        <div class="table-toolbar__left">
          <strong>角色列表</strong>
          <span :class="{ 'is-active': selectedRows.length > 0 }">已选 {{ selectedRows.length }} 项</span>
        </div>
        <div class="table-toolbar__actions">
          <el-button class="toolbar-create" type="primary" :icon="CirclePlus" @click="openCreateDialog">
            新增角色
          </el-button>
          <el-button
            class="toolbar-action"
            :disabled="selectedRows.length === 0"
            :icon="CircleCheck"
            @click="handleBatchStatus(1)"
          >
            批量启用
          </el-button>
          <el-button
            class="toolbar-action"
            :disabled="selectedRows.length === 0"
            :icon="CircleClose"
            @click="handleBatchStatus(0)"
          >
            批量停用
          </el-button>
          <el-button
            class="toolbar-danger"
            :disabled="selectedRows.length === 0"
            :icon="Delete"
            @click="handleBatchDelete"
          >
            删除
          </el-button>
          <el-button class="toolbar-action" :icon="RefreshRight" @click="refreshList">刷新</el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="pagedRoles"
        row-key="roleId"
        border
        class="role-table"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column label="角色" min-width="190">
          <template #default="{ row }">
            <div class="role-name-cell">
              <strong>{{ row.roleName }}</strong>
              <span>{{ row.roleCode }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="权限码" min-width="330" align="center">
          <template #default="{ row }">
            <div class="permission-summary">
              <el-tag class="permission-summary__tag" effect="plain">
                {{ getPermissionSummary(row) }}
              </el-tag>
              <el-button class="permission-summary__link" link type="primary" @click="openPermissionPreview(row)">
                查看明细
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="绑定用户数" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              class="user-count-tag"
              :class="{ 'is-empty': row.userCount === 0 }"
              effect="plain"
            >
              {{ row.userCount }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="plain">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="190">
          <template #default="{ row }">
            <span class="remark-text">{{ row.remark || '未填写' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="140">
          <template #default="{ row }">
            <div class="time-cell">
              <span>创建 {{ formatTableTime(row.createdAt) }}</span>
              <span>更新 {{ formatTableTime(row.updatedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="176" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button class="action-button" @click="openEditDialog(row)">编辑</el-button>
              <el-button class="action-button is-role" @click="openPermissionDialog(row)">权限配置</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <div class="pagination-total">共 {{ filteredRoles.length }} 条</div>
        <el-pagination
          class="pagination-sizes"
          v-model:page-size="query.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="filteredRoles.length"
          layout="sizes"
        />
        <el-pagination
          class="pagination-pager"
          v-model:current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="filteredRoles.length"
          layout="prev, pager, next"
        />
        <el-pagination
          class="pagination-jumper"
          v-model:current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="filteredRoles.length"
          layout="jumper"
        />
      </div>
    </section>

    <el-dialog
      v-model="roleDialogVisible"
      :title="dialogMode === 'create' ? '新增角色' : '编辑角色'"
      width="640px"
      destroy-on-close
    >
      <el-form ref="roleFormRef" :model="roleForm" :rules="roleRules" label-width="88px">
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="roleForm.roleCode" :disabled="dialogMode === 'edit'" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="roleForm.roleName" maxlength="100" />
        </el-form-item>
        <el-form-item label="启用状态" prop="status">
          <el-radio-group v-model="roleForm.status">
            <el-radio-button :value="1">启用</el-radio-button>
            <el-radio-button :value="0">停用</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="权限码" prop="permissionCodes">
          <div class="permission-picker">
            <el-checkbox-group v-model="roleForm.permissionCodes" class="permission-checks">
              <el-checkbox value="*">全部权限</el-checkbox>
              <div v-for="group in permissionGroups" :key="group.group" class="permission-group">
                <div class="permission-group__title">{{ group.group }}</div>
                <div class="permission-group__options">
                  <el-checkbox v-for="item in group.codes" :key="item.code" :value="item.code">
                    {{ item.label }}
                  </el-checkbox>
                </div>
              </div>
            </el-checkbox-group>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="roleForm.remark" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRoleForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permissionPreviewVisible" title="权限码明细" width="660px" destroy-on-close>
      <div v-if="permissionPreviewRole" class="permission-preview">
        <div class="permission-preview__head">
          <div>
            <strong>{{ permissionPreviewRole.roleName }}</strong>
            <span>{{ permissionPreviewRole.roleCode }}</span>
          </div>
          <el-tag effect="plain" class="permission-summary__tag">
            {{ getPermissionSummary(permissionPreviewRole) }}
          </el-tag>
        </div>

        <el-alert
          v-if="hasAllPermissions(permissionPreviewRole)"
          class="permission-preview__alert"
          title="该角色使用全部权限通配符，后端会按系统全部权限码处理"
          type="info"
          :closable="false"
          show-icon
        />

        <div class="permission-preview__groups">
          <div v-for="group in getPermissionGroups(permissionPreviewRole)" :key="group.group" class="permission-preview-group">
            <div class="permission-preview-group__title">{{ group.group }}</div>
            <div class="permission-preview-group__codes">
              <el-tag v-for="item in group.codes" :key="item.code" effect="plain" class="permission-code-tag">
                <span>{{ item.label }}</span>
                <small>{{ item.code }}</small>
              </el-tag>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="permissionPreviewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permissionDialogVisible" title="权限配置" width="620px" destroy-on-close>
      <el-form ref="permissionFormRef" :model="permissionForm" :rules="permissionRules" label-width="88px">
        <el-form-item label="当前角色">
          <el-input :model-value="permissionEditingRole?.roleName" disabled />
        </el-form-item>
        <el-form-item label="权限码" prop="permissionCodes">
          <div class="permission-picker">
            <el-checkbox-group v-model="permissionForm.permissionCodes" class="permission-checks">
              <el-checkbox value="*">全部权限</el-checkbox>
              <div v-for="group in permissionGroups" :key="group.group" class="permission-group">
                <div class="permission-group__title">{{ group.group }}</div>
                <div class="permission-group__options">
                  <el-checkbox v-for="item in group.codes" :key="item.code" :value="item.code">
                    {{ item.label }}
                  </el-checkbox>
                </div>
              </div>
            </el-checkbox-group>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="permissionDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPermissionForm">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.role-page {
  min-height: 100%;
}

.page-head {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  margin-bottom: 6px;
}

.page-subtitle {
  margin: 0;
  font-size: 14px;
  color: #6b7280;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.metric-item {
  display: flex;
  min-height: 72px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #dfe5ee;
  border-radius: 8px;
  flex-direction: column;
  justify-content: center;
}

.metric-item span {
  font-size: 13px;
  color: #6b7280;
}

.metric-item strong {
  margin-top: 6px;
  font-size: 24px;
  line-height: 1;
  color: #172033;
}

.module-panel {
  background: #fff;
  border: 1px solid #dfe5ee;
  border-radius: 8px;
}

.filter-panel {
  padding: 16px 16px 0;
  margin-bottom: 14px;
}

.filter-form {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(220px, 1fr) minmax(180px, 240px) auto;
  gap: 0 12px;
  align-items: flex-start;
}

.filter-form :deep(.el-select),
.filter-form :deep(.el-input) {
  width: 100%;
}

.filter-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 18px;
  justify-self: end;
}

.table-panel {
  overflow: hidden;
}

.table-toolbar {
  display: flex;
  min-height: 58px;
  padding: 12px 16px;
  gap: 12px;
  border-bottom: 1px solid #e5eaf2;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
}

.table-toolbar__left {
  display: flex;
  gap: 10px;
  align-items: center;
}

.table-toolbar__left strong {
  color: #172033;
}

.table-toolbar__left span {
  font-size: 13px;
  color: #7b8495;
}

.table-toolbar__left span.is-active {
  color: #2f6fed;
}

.table-toolbar__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  margin-left: auto;
}

.table-toolbar__actions :deep(.el-button + .el-button),
.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.table-toolbar__actions :deep(.el-button) {
  height: 32px;
  border-radius: 6px;
}

.toolbar-create {
  margin-right: 4px;
}

.toolbar-action:not(.is-disabled) {
  color: #4e5b70;
  background: #f8fafc;
  border-color: #d8e0eb;
}

.toolbar-action:not(.is-disabled):hover {
  color: #2f6fed;
  background: #f4f8ff;
  border-color: #b9d2ff;
}

.toolbar-danger:not(.is-disabled) {
  color: #cf3f3f;
  background: #fff7f7;
  border-color: #f0c9c9;
}

.toolbar-danger:not(.is-disabled):hover {
  color: #b62929;
  background: #fff0f0;
  border-color: #e7aaaa;
}

.role-table {
  width: 100%;
}

.role-table :deep(.el-table__body td.el-table__cell) {
  padding: 6px 0;
  vertical-align: middle;
}

.role-table :deep(.el-table__body td.el-table__cell > .cell) {
  display: flex;
  min-height: 46px;
  align-items: center;
}

.role-table :deep(.el-table__cell.is-center > .cell) {
  justify-content: center;
}

.role-table :deep(.el-tag) {
  display: inline-flex;
  width: auto;
  min-width: 34px;
  height: 32px;
  padding: 0 10px;
  font-size: 13px;
  border-radius: 6px;
  align-items: center;
  flex: 0 0 auto;
  justify-content: center;
  line-height: 1;
  white-space: nowrap;
}

.role-name-cell {
  display: flex;
  flex-direction: column;
}

.role-name-cell strong,
.role-name-cell span {
  display: block;
}

.role-name-cell strong {
  font-size: 14px;
  color: #172033;
}

.role-name-cell span {
  margin-top: 2px;
  font-size: 12px;
  color: #6b7280;
}

.permission-summary {
  display: flex;
  gap: 10px;
  width: 100%;
  align-items: center;
  justify-content: center;
}

.permission-summary :deep(.permission-summary__tag.el-tag),
.permission-preview :deep(.permission-summary__tag.el-tag) {
  display: inline-flex;
  min-width: 102px;
  height: 32px;
  padding: 0 12px;
  color: #1458d4;
  background: #e7f0ff;
  border-color: #5b95ff;
  font-size: 13px;
  font-weight: 700;
  align-items: center;
  justify-content: center;
}

.permission-summary__link {
  height: 30px;
  padding: 0 2px;
  font-size: 13px;
  font-weight: 700;
}

.permission-preview__head {
  display: flex;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid #e5eaf2;
  align-items: center;
  justify-content: space-between;
}

.permission-preview__head strong,
.permission-preview__head span {
  display: block;
}

.permission-preview__head strong {
  color: #172033;
  font-size: 15px;
}

.permission-preview__head span {
  margin-top: 3px;
  color: #6b7280;
  font-size: 12px;
}

.permission-preview__alert {
  margin-top: 14px;
}

.permission-preview__groups {
  display: grid;
  gap: 12px;
  margin-top: 14px;
}

.permission-preview-group {
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.permission-preview-group__title {
  margin-bottom: 10px;
  color: #172033;
  font-size: 13px;
  font-weight: 700;
}

.permission-preview-group__codes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.permission-code-tag {
  display: flex;
  width: 100%;
  height: auto;
  min-height: 42px;
  padding: 7px 10px;
  color: #1458d4;
  background: #e7f0ff;
  border-color: #5b95ff;
  align-items: flex-start;
  flex-direction: column;
}

.permission-code-tag span,
.permission-code-tag small {
  display: block;
}

.permission-code-tag span {
  font-size: 13px;
  font-weight: 700;
  line-height: 1.2;
}

.permission-code-tag small {
  margin-top: 4px;
  color: #5875a8;
  font-size: 11px;
  font-family: Consolas, 'Courier New', monospace;
  line-height: 1.2;
}

.user-count-tag {
  min-width: 38px;
  height: 30px;
  color: #2f6fed;
  background: #f4f8ff;
  border-color: #b9d2ff;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  justify-content: center;
}

.user-count-tag.is-empty {
  color: #8b95a5;
  background: #ffffff;
  border-color: #d7dde7;
}

.remark-text {
  color: #4e5b70;
  font-size: 13px;
}

.time-cell {
  display: flex;
  gap: 3px;
  color: #5f6b7c;
  font-size: 12px;
  line-height: 1.45;
  flex-direction: column;
}

.time-cell span:first-child {
  color: #172033;
}

.row-actions {
  display: flex;
  gap: 8px;
  min-width: 148px;
  align-items: center;
  justify-content: center;
}

.action-button {
  min-width: 54px;
  height: 30px;
  padding: 0 11px;
  font-size: 13px;
  font-weight: 600;
  color: #2f6fed;
  background: #f4f8ff;
  border: 1px solid #b9d2ff;
  border-radius: 6px;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    color 0.16s ease;
}

.action-button:hover {
  color: #225fd0;
  background: #edf4ff;
  border-color: #8fbaff;
}

.action-button.is-role {
  color: #d94a4a;
  background: #fff2f2;
  border-color: #f1b8b8;
}

.action-button.is-role:hover {
  color: #bd3030;
  background: #ffe7e7;
  border-color: #e89393;
}

.pagination-bar {
  position: relative;
  display: grid;
  grid-template-columns: auto auto 1fr auto;
  min-height: 58px;
  padding: 0 16px;
  border-top: 1px solid #e5eaf2;
  align-items: center;
  column-gap: 48px;
}

.pagination-total {
  color: #647084;
  font-size: 13px;
  justify-self: start;
}

.pagination-bar :deep(.el-pagination) {
  gap: 8px;
  color: #4b5563;
  font-size: 13px;
}

.pagination-sizes {
  position: absolute;
  right: calc(50% + 68px);
}

.pagination-pager {
  position: absolute;
  left: 50%;
  justify-self: center;
  transform: translateX(-50%);
}

.pagination-jumper {
  grid-column: 4;
  justify-self: end;
}

.pagination-bar :deep(.el-pagination__jump) {
  color: #647084;
}

.pagination-bar :deep(.el-select__wrapper),
.pagination-bar :deep(.el-input__wrapper),
.pagination-bar :deep(.btn-prev),
.pagination-bar :deep(.btn-next),
.pagination-bar :deep(.el-pager li) {
  min-width: 32px;
  height: 32px;
  border-radius: 6px;
}

.pagination-bar :deep(.el-pager li.is-active) {
  color: #2f6fed;
  background: #eef4ff;
}

.permission-picker {
  width: 100%;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #dfe5ee;
  border-radius: 8px;
}

.permission-group {
  padding-top: 10px;
  margin-top: 10px;
  border-top: 1px solid #e5eaf2;
}

.permission-group__title {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 700;
  color: #172033;
}

.permission-group__options {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px 10px;
}

.permission-checks :deep(.el-checkbox) {
  margin-right: 0;
}

@media (max-width: 1180px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filter-form {
    grid-template-columns: repeat(2, minmax(180px, 1fr));
  }
}

@media (max-width: 760px) {
  .page-head {
    flex-direction: column;
  }

  .metric-grid,
  .filter-form {
    grid-template-columns: 1fr;
  }

  .permission-group__options {
    grid-template-columns: 1fr;
  }
}
</style>
