<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  CircleCheck,
  CircleClose,
  CirclePlus,
  Delete,
  Key,
  RefreshRight,
  Search,
  UserFilled,
} from '@element-plus/icons-vue';
import type {
  DeptOption,
  RoleOption,
  SystemUserFormPayload,
  SystemUserListItem,
  SystemUserQuery,
  UserStatus,
} from '../types';

const roleOptions: RoleOption[] = [
  { roleId: '1900000000000001001', roleCode: 'SUPER_ADMIN', roleName: '超级管理员', status: 1 },
  { roleId: '1900000000000001002', roleCode: 'SYSTEM_ADMIN', roleName: '系统管理员', status: 1 },
  { roleId: '1900000000000001003', roleCode: 'BUSINESS_MANAGER', roleName: '业务主管', status: 1 },
  { roleId: '1900000000000001004', roleCode: 'WAREHOUSE_OPERATOR', roleName: '仓库操作员', status: 1 },
];

const deptOptions: DeptOption[] = [
  { deptId: '1900000000000000100', deptName: '总部', parentId: '0', status: 1 },
  { deptId: '1900000000000000101', deptName: '财务部', parentId: '1900000000000000100', status: 1 },
  { deptId: '1900000000000000102', deptName: '采购部', parentId: '1900000000000000100', status: 1 },
  { deptId: '1900000000000000103', deptName: '销售部', parentId: '1900000000000000100', status: 1 },
  { deptId: '1900000000000000104', deptName: '仓储部', parentId: '1900000000000000100', status: 1 },
];

const initialUsers: SystemUserListItem[] = [
  {
    userId: '1900000000000000001',
    username: 'admin',
    realName: '系统管理员',
    deptId: '1900000000000000100',
    deptName: '总部',
    isAdmin: true,
    status: 1,
    roleIds: ['1900000000000001001'],
    roleNames: ['超级管理员'],
    lastLoginAt: '2026-06-08 09:12:30',
    createdAt: '2026-06-05 20:30:00',
    updatedAt: '2026-06-08 09:12:30',
  },
  {
    userId: '1900000000000000002',
    username: 'purchase01',
    realName: '采购主管',
    deptId: '1900000000000000102',
    deptName: '采购部',
    isAdmin: false,
    status: 1,
    roleIds: ['1900000000000001003'],
    roleNames: ['业务主管'],
    lastLoginAt: '2026-06-07 17:24:11',
    createdAt: '2026-06-06 10:18:22',
    updatedAt: '2026-06-07 17:24:11',
  },
  {
    userId: '1900000000000000003',
    username: 'warehouse01',
    realName: '仓库操作员',
    deptId: '1900000000000000104',
    deptName: '仓储部',
    isAdmin: false,
    status: 1,
    roleIds: ['1900000000000001004'],
    roleNames: ['仓库操作员'],
    lastLoginAt: null,
    createdAt: '2026-06-06 11:05:19',
    updatedAt: '2026-06-06 11:05:19',
  },
  {
    userId: '1900000000000000004',
    username: 'sales_stop',
    realName: '停用销售账号',
    deptId: '1900000000000000103',
    deptName: '销售部',
    isAdmin: false,
    status: 0,
    roleIds: ['1900000000000001003'],
    roleNames: ['业务主管'],
    lastLoginAt: '2026-06-06 14:42:02',
    createdAt: '2026-06-05 22:10:00',
    updatedAt: '2026-06-07 13:00:00',
  },
];

const loading = ref(false);
const users = ref<SystemUserListItem[]>(initialUsers);
const selectedRows = ref<SystemUserListItem[]>([]);
const userDialogVisible = ref(false);
const roleDialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingUserId = ref('');
const roleEditingUser = ref<SystemUserListItem | null>(null);
const userFormRef = ref<FormInstance>();
const roleFormRef = ref<FormInstance>();

const query = reactive<SystemUserQuery>({
  keyword: '',
  deptId: '',
  roleId: '',
  status: '',
  pageNum: 1,
  pageSize: 10,
});

const userForm = reactive<SystemUserFormPayload>({
  username: '',
  realName: '',
  password: '',
  deptId: null,
  isAdmin: false,
  status: 1,
  roleIds: [],
});

const roleForm = reactive({
  roleIds: [] as string[],
});

const userRules = computed<FormRules<SystemUserFormPayload>>(() => ({
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    { min: 3, max: 64, message: '账号长度为 3-64 个字符', trigger: 'blur' },
  ],
  realName: [
    { required: true, message: '请输入用户姓名', trigger: 'blur' },
    { max: 100, message: '姓名不能超过 100 个字符', trigger: 'blur' },
  ],
  password:
    dialogMode.value === 'create'
      ? [
          { required: true, message: '请输入初始密码', trigger: 'blur' },
          { min: 6, max: 32, message: '密码长度为 6-32 个字符', trigger: 'blur' },
        ]
      : [{ min: 6, max: 32, message: '密码长度为 6-32 个字符', trigger: 'blur' }],
  deptId: [{ required: true, message: '请选择所属部门', trigger: 'change' }],
  roleIds: [{ required: true, message: '请选择用户角色', trigger: 'change' }],
}));

const roleRules: FormRules = {
  roleIds: [{ required: true, message: '请选择用户角色', trigger: 'change' }],
};

const filteredUsers = computed(() => {
  const keyword = query.keyword?.trim().toLowerCase();

  return users.value.filter(user => {
    const matchKeyword =
      !keyword ||
      user.username.toLowerCase().includes(keyword) ||
      user.realName.toLowerCase().includes(keyword) ||
      user.deptName.toLowerCase().includes(keyword);
    const matchDept = isEmptyFilter(query.deptId) || user.deptId === query.deptId;
    const matchRole = isEmptyFilter(query.roleId) || user.roleIds.includes(String(query.roleId));
    const matchStatus = isEmptyFilter(query.status) || user.status === Number(query.status);

    return matchKeyword && matchDept && matchRole && matchStatus;
  });
});

const pagedUsers = computed(() => {
  const start = (query.pageNum - 1) * query.pageSize;
  return filteredUsers.value.slice(start, start + query.pageSize);
});

const enabledCount = computed(() => users.value.filter(user => user.status === 1).length);
const adminCount = computed(() => users.value.filter(user => user.isAdmin).length);
const roleBoundCount = computed(() => users.value.filter(user => user.roleIds.length > 0).length);

function isEmptyFilter(value: unknown) {
  return value === '' || value === null || value === undefined;
}

function getDeptName(deptId: string | null) {
  return deptOptions.find(item => item.deptId === deptId)?.deptName || '';
}

function getRoleNames(roleIds: string[]) {
  return roleIds.map(roleId => roleOptions.find(item => item.roleId === roleId)?.roleName).filter(Boolean) as string[];
}

function formatTableTime(value: string | null) {
  if (!value) {
    return '未登录';
  }

  return value.slice(5, 16);
}

function refreshList() {
  loading.value = true;
  window.setTimeout(() => {
    loading.value = false;
    ElMessage.success('列表已刷新');
  }, 260);
}

function handleSearch() {
  query.pageNum = 1;
}

function handleReset() {
  query.keyword = '';
  query.deptId = '';
  query.roleId = '';
  query.status = '';
  query.pageNum = 1;
}

function ensureSelectedRows(actionName: string) {
  if (selectedRows.value.length > 0) {
    return true;
  }

  ElMessage.warning(`请先选择需要${actionName}的用户`);
  return false;
}

async function handleBatchStatus(status: UserStatus) {
  const action = status === 1 ? '启用' : '停用';

  if (!ensureSelectedRows(action)) {
    return;
  }

  try {
    await ElMessageBox.confirm(`确认${action}已选择的 ${selectedRows.value.length} 个账号吗`, `批量${action}`, {
      confirmButtonText: action,
      cancelButtonText: '取消',
      type: status === 1 ? 'success' : 'warning',
    });
  } catch {
    return;
  }

  const selectedIds = new Set(selectedRows.value.map(user => user.userId));
  users.value = users.value.map(user =>
    selectedIds.has(user.userId) ? { ...user, status, updatedAt: '2026-06-08 10:00:00' } : user,
  );
  selectedRows.value = [];
  ElMessage.success(`已批量${action}`);
}

async function handleBatchResetPassword() {
  if (!ensureSelectedRows('重置密码')) {
    return;
  }

  let value = '';
  try {
    const result = await ElMessageBox.prompt(`为已选择的 ${selectedRows.value.length} 个账号设置新密码`, '批量重置密码', {
      confirmButtonText: '确认重置',
      cancelButtonText: '取消',
      inputType: 'password',
      inputPattern: /^.{6,32}$/,
      inputErrorMessage: '密码长度为 6-32 个字符',
    });
    value = result.value;
  } catch {
    return;
  }

  if (value) {
    ElMessage.success('已批量重置密码');
  }
}

async function handleBatchDelete() {
  if (!ensureSelectedRows('删除')) {
    return;
  }

  try {
    await ElMessageBox.confirm(`确认删除已选择的 ${selectedRows.value.length} 个账号吗`, '批量删除用户', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }

  const selectedIds = new Set(selectedRows.value.map(user => user.userId));
  users.value = users.value.filter(user => !selectedIds.has(user.userId));
  selectedRows.value = [];
  ElMessage.success('已批量删除用户');
}

function resetUserForm() {
  editingUserId.value = '';
  userForm.username = '';
  userForm.realName = '';
  userForm.password = '';
  userForm.deptId = null;
  userForm.isAdmin = false;
  userForm.status = 1;
  userForm.roleIds = [];
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetUserForm();
  userDialogVisible.value = true;
}

function openEditDialog(row: SystemUserListItem) {
  dialogMode.value = 'edit';
  editingUserId.value = row.userId;
  userForm.username = row.username;
  userForm.realName = row.realName;
  userForm.password = '';
  userForm.deptId = row.deptId;
  userForm.isAdmin = row.isAdmin;
  userForm.status = row.status;
  userForm.roleIds = [...row.roleIds];
  userDialogVisible.value = true;
}

async function submitUserForm() {
  await userFormRef.value?.validate();

  const now = '2026-06-08 10:00:00';
  const deptName = getDeptName(userForm.deptId);
  const roleNames = getRoleNames(userForm.roleIds);

  if (dialogMode.value === 'create') {
    const nextUser: SystemUserListItem = {
      userId: String(1900000000000000100 + users.value.length),
      username: userForm.username,
      realName: userForm.realName,
      deptId: userForm.deptId,
      deptName,
      isAdmin: userForm.isAdmin,
      status: userForm.status,
      roleIds: [...userForm.roleIds],
      roleNames,
      lastLoginAt: null,
      createdAt: now,
      updatedAt: now,
    };

    users.value = [nextUser, ...users.value];
    ElMessage.success('用户已创建');
  } else {
    users.value = users.value.map(user =>
      user.userId === editingUserId.value
        ? {
            ...user,
            username: userForm.username,
            realName: userForm.realName,
            deptId: userForm.deptId,
            deptName,
            isAdmin: userForm.isAdmin,
            status: userForm.status,
            roleIds: [...userForm.roleIds],
            roleNames,
            updatedAt: now,
          }
        : user,
    );
    ElMessage.success('用户已更新');
  }

  userDialogVisible.value = false;
}

function openRoleDialog(row: SystemUserListItem) {
  roleEditingUser.value = row;
  roleForm.roleIds = [...row.roleIds];
  roleDialogVisible.value = true;
}

async function submitRoleForm() {
  await roleFormRef.value?.validate();

  const current = roleEditingUser.value;
  if (!current) {
    return;
  }

  users.value = users.value.map(user =>
    user.userId === current.userId
      ? {
          ...user,
          roleIds: [...roleForm.roleIds],
          roleNames: getRoleNames(roleForm.roleIds),
          updatedAt: '2026-06-08 10:00:00',
        }
      : user,
  );
  roleDialogVisible.value = false;
  ElMessage.success('角色绑定已更新');
}

async function handleStatusChange(row: SystemUserListItem, status: UserStatus) {
  const action = status === 1 ? '启用' : '停用';
  try {
    await ElMessageBox.confirm(`确认${action}账号「${row.username}」吗`, `${action}账号`, {
      confirmButtonText: action,
      cancelButtonText: '取消',
      type: status === 1 ? 'success' : 'warning',
    });
  } catch {
    return;
  }

  users.value = users.value.map(user =>
    user.userId === row.userId ? { ...user, status, updatedAt: '2026-06-08 10:00:00' } : user,
  );
  ElMessage.success(`账号已${action}`);
}

async function handleResetPassword(row: SystemUserListItem) {
  let value = '';
  try {
    const result = await ElMessageBox.prompt(`为「${row.realName}」设置新密码`, '重置密码', {
      confirmButtonText: '确认重置',
      cancelButtonText: '取消',
      inputType: 'password',
      inputPattern: /^.{6,32}$/,
      inputErrorMessage: '密码长度为 6-32 个字符',
    });
    value = result.value;
  } catch {
    return;
  }

  if (value) {
    ElMessage.success('密码已重置');
  }
}

async function handleDelete(row: SystemUserListItem) {
  try {
    await ElMessageBox.confirm(`确认删除账号「${row.username}」吗`, '删除用户', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }

  users.value = users.value.filter(user => user.userId !== row.userId);
  ElMessage.success('用户已删除');
}

function handleSelectionChange(rows: SystemUserListItem[]) {
  selectedRows.value = rows;
}
</script>

<template>
  <section class="page-shell user-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">登录账号、部门归属、角色绑定与启用状态</p>
      </div>
    </div>

    <div class="metric-grid">
      <div class="metric-item">
        <span>用户总数</span>
        <strong>{{ users.length }}</strong>
      </div>
      <div class="metric-item">
        <span>启用账号</span>
        <strong>{{ enabledCount }}</strong>
      </div>
      <div class="metric-item">
        <span>超级管理员</span>
        <strong>{{ adminCount }}</strong>
      </div>
      <div class="metric-item">
        <span>已绑定角色</span>
        <strong>{{ roleBoundCount }}</strong>
      </div>
    </div>

    <section class="module-panel filter-panel">
      <el-form class="filter-form" :model="query" label-width="72px">
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" clearable placeholder="账号 姓名 部门" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="query.deptId" clearable value-on-clear="" placeholder="全部部门">
            <el-option v-for="dept in deptOptions" :key="dept.deptId" :label="dept.deptName" :value="dept.deptId" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="query.roleId" clearable value-on-clear="" placeholder="全部角色">
            <el-option v-for="role in roleOptions" :key="role.roleId" :label="role.roleName" :value="role.roleId" />
          </el-select>
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
          <strong>账号列表</strong>
          <span :class="{ 'is-active': selectedRows.length > 0 }">已选 {{ selectedRows.length }} 项</span>
        </div>
        <div class="table-toolbar__actions">
          <el-button class="toolbar-create" type="primary" :icon="CirclePlus" @click="openCreateDialog">
            新增用户
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
            class="toolbar-action"
            :disabled="selectedRows.length === 0"
            :icon="Key"
            @click="handleBatchResetPassword"
          >
            重置密码
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
        :data="pagedUsers"
        row-key="userId"
        border
        class="user-table"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column label="账号" min-width="170">
          <template #default="{ row }">
            <div class="account-cell">
              <el-avatar :size="32" class="account-cell__avatar">
                <el-icon><UserFilled /></el-icon>
              </el-avatar>
              <div>
                <strong>{{ row.username }}</strong>
                <span>{{ row.realName }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="deptName" label="部门" min-width="120" align="center" />
        <el-table-column label="角色" min-width="250" align="center">
          <template #default="{ row }">
            <div class="role-tags">
              <el-tag v-for="role in row.roleNames" :key="role" effect="plain">{{ role }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="超级管理员" width="108" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isAdmin ? 'primary' : 'info'" effect="plain">
              {{ row.isAdmin ? '是' : '否' }}
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
        <el-table-column label="时间" min-width="156">
          <template #default="{ row }">
            <div class="time-cell">
              <span>登录 {{ formatTableTime(row.lastLoginAt) }}</span>
              <span>更新 {{ formatTableTime(row.updatedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="176" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button class="action-button" @click="openEditDialog(row)">编辑</el-button>
              <el-button class="action-button is-role" @click="openRoleDialog(row)">角色绑定</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <div class="pagination-total">共 {{ filteredUsers.length }} 条</div>
        <el-pagination
          class="pagination-sizes"
          v-model:page-size="query.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="filteredUsers.length"
          layout="sizes"
        />
        <el-pagination
          class="pagination-pager"
          v-model:current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="filteredUsers.length"
          layout="prev, pager, next"
        />
        <el-pagination
          class="pagination-jumper"
          v-model:current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="filteredUsers.length"
          layout="jumper"
        />
      </div>
    </section>

    <el-dialog
      v-model="userDialogVisible"
      :title="dialogMode === 'create' ? '新增用户' : '编辑用户'"
      width="560px"
      destroy-on-close
    >
      <el-form ref="userFormRef" :model="userForm" :rules="userRules" label-width="88px">
        <el-form-item label="登录账号" prop="username">
          <el-input v-model="userForm.username" :disabled="dialogMode === 'edit'" maxlength="64" />
        </el-form-item>
        <el-form-item label="用户姓名" prop="realName">
          <el-input v-model="userForm.realName" maxlength="100" />
        </el-form-item>
        <el-form-item :label="dialogMode === 'create' ? '初始密码' : '新密码'" prop="password">
          <el-input
            v-model="userForm.password"
            type="password"
            show-password
            :placeholder="dialogMode === 'create' ? '请输入初始密码' : '不修改请留空'"
          />
        </el-form-item>
        <el-form-item label="所属部门" prop="deptId">
          <el-select v-model="userForm.deptId" placeholder="请选择部门">
            <el-option v-for="dept in deptOptions" :key="dept.deptId" :label="dept.deptName" :value="dept.deptId" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定角色" prop="roleIds">
          <el-select v-model="userForm.roleIds" multiple placeholder="请选择角色">
            <el-option v-for="role in roleOptions" :key="role.roleId" :label="role.roleName" :value="role.roleId" />
          </el-select>
        </el-form-item>
        <el-form-item label="超级管理员">
          <el-switch v-model="userForm.isAdmin" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-radio-group v-model="userForm.status">
            <el-radio-button :value="1">启用</el-radio-button>
            <el-radio-button :value="0">停用</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitUserForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialogVisible" title="角色绑定" width="460px" destroy-on-close>
      <el-form ref="roleFormRef" :model="roleForm" :rules="roleRules" label-width="88px">
        <el-form-item label="当前用户">
          <el-input :model-value="roleEditingUser?.realName" disabled />
        </el-form-item>
        <el-form-item label="绑定角色" prop="roleIds">
          <el-select v-model="roleForm.roleIds" multiple placeholder="请选择角色">
            <el-option v-for="role in roleOptions" :key="role.roleId" :label="role.roleName" :value="role.roleId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRoleForm">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.user-page {
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
  grid-template-columns: repeat(4, minmax(180px, 1fr)) auto;
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
  flex: 0 0 auto;
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

.table-toolbar__actions :deep(.el-button + .el-button) {
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

.user-table {
  width: 100%;
}

.user-table :deep(.el-table__body td.el-table__cell) {
  padding: 6px 0;
  vertical-align: middle;
}

.user-table :deep(.el-table__body td.el-table__cell > .cell) {
  display: flex;
  min-height: 46px;
  align-items: center;
}

.user-table :deep(.el-table__cell.is-center > .cell) {
  justify-content: center;
}

.user-table :deep(.el-tag) {
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

.account-cell {
  display: flex;
  gap: 10px;
  align-items: center;
}

.account-cell__avatar {
  color: #fff;
  background: #2f6fed;
}

.account-cell strong,
.account-cell span {
  display: block;
}

.account-cell strong {
  font-size: 14px;
  color: #172033;
}

.account-cell span {
  margin-top: 2px;
  font-size: 13px;
  color: #6b7280;
}

.role-tags {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  width: 100%;
}

.role-tags :deep(.el-tag) {
  min-width: 102px;
  height: 32px;
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
  align-items: center;
  justify-content: center;
  min-width: 148px;
}

.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
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
  color: #d3cd17;
  background: #ecf1cb;
  border-color: #d9e27a;
}

.action-button.is-role:hover {
  color: #b9b410;
  background: #e5ebb9;
  border-color: #cbd45d;
}

.pagination-bar {
  display: grid;
  position: relative;
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
}
</style>
