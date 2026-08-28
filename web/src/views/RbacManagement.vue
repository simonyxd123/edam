<script setup lang="ts">
/**
 * 角色权限管理页（v3.2 V-1 RBAC — 完整版）
 *
 * Tab 1：用户管理（CRUD + 分配角色）
 * Tab 2：角色管理（CRUD + 分配权限）
 * Tab 3：权限目录（只读）
 * Tab 4：我的角色（只读）
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import {
  rbacApi,
  type Permission,
  type Role,
  type UserRole,
  type SysUserView,
} from '@/api/rbac';
import { useUserStore } from '@/stores/user';

const userStore = useUserStore();

const activeTab = ref('users');

const users = ref<SysUserView[]>([]);
const roles = ref<Role[]>([]);
const permissions = ref<Permission[]>([]);
const myRoles = ref<UserRole[]>([]);
const loading = ref(false);

// ============== 用户列表 + 增删改弹窗 ==============
const userDialog = ref(false);
const userDialogMode = ref<'create' | 'edit' | 'password'>('create');
const userFormRef = ref<FormInstance>();
const userForm = ref<{
  id?: number;
  username: string;
  password: string;
  employeeNo: string;
  realName: string;
  email: string;
  mfaEnabled: number;
}>({
  username: '', password: '', employeeNo: '', realName: '', email: '', mfaEnabled: 0,
});

const userFormRules = {
  username: [{ required: true, message: '用户名必填', trigger: 'blur' }],
  employeeNo: [
    { required: true, message: '工号必填', trigger: 'blur' },
    { pattern: /^[A-Z]\d+$/, message: '格式如 E000001', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '密码至少 6 位', trigger: 'blur' },
    { min: 6, message: '至少 6 位', trigger: 'blur' },
  ],
};

async function loadUsers() {
  try {
    const resp = await rbacApi.listUsers({ page: 1, page_size: 100 });
    users.value = resp.items;
  } catch (e: any) {
    ElMessage.error('加载用户列表失败：' + (e?.message || '未知'));
  }
}

function openCreateUser() {
  if (!userStore.hasPermission('user:manage')) {
    ElMessage.warning('没有 user:manage 权限');
    return;
  }
  userForm.value = {
    username: '', password: 'changeme123', employeeNo: '', realName: '', email: '', mfaEnabled: 0,
  };
  userDialogMode.value = 'create';
  userDialog.value = true;
}

function openEditUser(u: SysUserView) {
  if (!userStore.hasPermission('user:manage')) {
    ElMessage.warning('没有 user:manage 权限');
    return;
  }
  userForm.value = {
    id: u.id,
    username: u.username,
    password: '',
    employeeNo: u.employee_no,
    realName: u.real_name ?? '',
    email: u.email ?? '',
    mfaEnabled: u.mfa_enabled,
  };
  userDialogMode.value = 'edit';
  userDialog.value = true;
}

function openResetPassword(u: SysUserView) {
  if (!userStore.hasPermission('user:manage')) {
    ElMessage.warning('没有 user:manage 权限');
    return;
  }
  userForm.value = {
    id: u.id,
    username: u.username,
    password: 'changeme123',
    employeeNo: u.employee_no,
    realName: u.real_name ?? '',
    email: '',
    mfaEnabled: 0,
  };
  userDialogMode.value = 'password';
  userDialog.value = true;
}

async function saveUser() {
  if (!userFormRef.value) return;
  const valid = await userFormRef.value.validate().catch(() => false);
  if (!valid) return;

  if (!userStore.hasPermission('user:manage')) {
    ElMessage.warning('没有 user:manage 权限');
    return;
  }

  try {
    if (userDialogMode.value === 'create') {
      await rbacApi.createUser({
        username: userForm.value.username,
        password: userForm.value.password,
        employee_no: userForm.value.employeeNo,
        real_name: userForm.value.realName,
        email: userForm.value.email || undefined,
        mfa_enabled: userForm.value.mfaEnabled,
      });
      ElMessage.success(`用户 ${userForm.value.username} 已创建`);
    } else if (userDialogMode.value === 'edit' && userForm.value.id) {
      await rbacApi.updateUser(userForm.value.id, {
        real_name: userForm.value.realName,
        email: userForm.value.email || undefined,
      });
      ElMessage.success(`用户 ${userForm.value.username} 已更新`);
    } else if (userDialogMode.value === 'password' && userForm.value.id) {
      await rbacApi.resetPassword(userForm.value.id, userForm.value.password);
      ElMessage.success(`用户 ${userForm.value.username} 密码已重置`);
    }
    userDialog.value = false;
    await loadUsers();
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || '未知'));
  }
}

async function deleteUser(u: SysUserView) {
  if (!userStore.hasPermission('user:manage')) {
    ElMessage.warning('没有 user:manage 权限');
    return;
  }
  if (u.id === 1) {
    ElMessage.warning('默认 admin 账号不可删除');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认删除（禁用）用户 "${u.real_name ?? u.username}" (${u.employee_no})？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消' }
    );
    await rbacApi.deleteUser(u.id);
    ElMessage.success('用户已禁用（status=2）');
    await loadUsers();
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败：' + (e?.message || '未知'));
    }
  }
}

// ============== 用户分配角色弹窗 ==============
const userAssignDialog = ref(false);
const userAssignForm = ref<{ user: SysUserView | null; roleIds: number[] }>({
  user: null,
  roleIds: [],
});

async function openAssignRoles(user: SysUserView) {
  if (!userStore.hasPermission('user:manage')) {
    ElMessage.warning('没有 user:manage 权限');
    return;
  }
  userAssignForm.value.user = user;
  try {
    const userRoles = await rbacApi.getUserRoles(user.id);
    userAssignForm.value.roleIds = userRoles.map(r => r.id);
  } catch (e: any) {
    userAssignForm.value.roleIds = [];
    ElMessage.warning('获取用户角色失败：' + (e?.message || '未知'));
  }
  userAssignDialog.value = true;
}

async function saveAssignRoles() {
  if (!userAssignForm.value.user) return;
  try {
    const resp = await rbacApi.assignRolesToUser(
      userAssignForm.value.user.id,
      userAssignForm.value.roleIds
    );
    ElMessage.success(`已分配 ${resp.assigned_count} 个角色给 ${userAssignForm.value.user.employee_no}`);
    userAssignDialog.value = false;
  } catch (e: any) {
    ElMessage.error('分配失败：' + (e?.message || '未知'));
  }
}

// ============== 角色 CRUD ==============
const roleDialog = ref(false);
const roleDialogMode = ref<'create' | 'edit'>('create');
const roleFormRef = ref<FormInstance>();
const roleForm = ref<{
  id?: number;
  code: string;
  name: string;
  description: string;
  permissionIds: number[];
}>({
  code: '', name: '', description: '', permissionIds: [],
});

const roleFormRules = {
  code: [{ required: true, message: '角色代码必填', trigger: 'blur' }],
  name: [{ required: true, message: '角色名必填', trigger: 'blur' }],
};

function openCreateRole() {
  if (!userStore.hasPermission('role:manage')) {
    ElMessage.warning('没有 role:manage 权限');
    return;
  }
  roleForm.value = { code: '', name: '', description: '', permissionIds: [] };
  roleDialogMode.value = 'create';
  roleDialog.value = true;
}

async function openEditRole(role: Role) {
  if (!userStore.hasPermission('role:manage')) {
    ElMessage.warning('没有 role:manage 权限');
    return;
  }
  roleForm.value = {
    id: role.id,
    code: role.code,
    name: role.name,
    description: role.description ?? '',
    permissionIds: [],
  };
  roleForm.value.permissionIds = permissions.value
    .filter(p => role.permissions.includes(p.code))
    .map(p => p.id);
  roleDialogMode.value = 'edit';
  roleDialog.value = true;
}

async function saveRole() {
  if (!roleFormRef.value) return;
  const valid = await roleFormRef.value.validate().catch(() => false);
  if (!valid) return;
  if (!userStore.hasPermission('role:manage')) {
    ElMessage.warning('没有 role:manage 权限');
    return;
  }
  try {
    if (roleDialogMode.value === 'edit' && roleForm.value.id) {
      await rbacApi.updateRole(roleForm.value.id, {
        name: roleForm.value.name,
        description: roleForm.value.description,
        permission_ids: roleForm.value.permissionIds,
      });
      ElMessage.success('角色已更新');
    } else {
      await rbacApi.createRole({
        code: roleForm.value.code,
        name: roleForm.value.name,
        description: roleForm.value.description,
        permission_ids: roleForm.value.permissionIds,
      });
      ElMessage.success('角色已创建');
    }
    roleDialog.value = false;
    await loadRoles();
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || '未知'));
  }
}

async function deleteRole(role: Role) {
  if (!userStore.hasPermission('role:manage')) {
    ElMessage.warning('没有 role:manage 权限');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认删除角色 "${role.name}" (${role.code})？此操作不可撤销。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
    await rbacApi.deleteRole(role.id);
    ElMessage.success('角色已删除');
    await loadRoles();
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败：' + (e?.message || '未知'));
    }
  }
}

async function loadRoles() {
  loading.value = true;
  try {
    roles.value = await rbacApi.listRoles();
  } catch (e: any) {
    ElMessage.error('加载角色失败：' + (e?.message || '未知'));
  } finally {
    loading.value = false;
  }
}

async function loadPermissions() {
  loading.value = true;
  try {
    permissions.value = await rbacApi.listPermissions();
  } catch (e: any) {
    ElMessage.error('加载权限失败：' + (e?.message || '未知'));
  } finally {
    loading.value = false;
  }
}

const permissionsByResource = computed(() => {
  const map = new Map<string, Permission[]>();
  for (const p of permissions.value) {
    if (!map.has(p.resource_type)) map.set(p.resource_type, []);
    map.get(p.resource_type)!.push(p);
  }
  return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
});

async function loadMyRoles() {
  if (!userStore.user?.user_id) return;
  try {
    myRoles.value = await rbacApi.getUserRoles(userStore.user.user_id);
  } catch (e: any) {
    ElMessage.warning('加载我的角色失败：' + (e?.message || '未知'));
  }
}

onMounted(async () => {
  await Promise.all([loadUsers(), loadRoles(), loadPermissions(), loadMyRoles()]);
});
</script>

<template>
  <div class="rbac-management">
    <h2>角色权限管理</h2>

    <el-tabs v-model="activeTab" type="border-card" style="margin-top:16px">

      <!-- Tab 1: 用户管理 -->
      <el-tab-pane label="用户管理" name="users">
        <el-space style="margin-bottom:12px">
          <el-button
            v-permission="'user:read'"
            type="primary"
            :icon="'Refresh'"
            @click="loadUsers"
          >
            刷新
          </el-button>
          <el-button
            v-permission="'user:manage'"
            type="success"
            :icon="'Plus'"
            @click="openCreateUser"
          >
            新增用户
          </el-button>
        </el-space>

        <el-table :data="users" v-loading="loading" stripe border>
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="username" label="用户名" width="140" />
          <el-table-column prop="employee_no" label="工号" width="140" />
          <el-table-column prop="real_name" label="姓名" width="140" />
          <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag
                :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'warning'"
                size="small"
              >
                {{ row.status === 1 ? 'active' : row.status === 2 ? 'disabled' : 'locked' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="320" fixed="right">
            <template #default="{ row }">
              <el-button
                v-permission="'user:manage'"
                link
                type="primary"
                @click="openAssignRoles(row)"
              >
                分配角色
              </el-button>
              <el-button
                v-permission="'user:manage'"
                link
                type="primary"
                @click="openEditUser(row)"
              >
                编辑
              </el-button>
              <el-button
                v-permission="'user:manage'"
                link
                type="warning"
                @click="openResetPassword(row)"
              >
                重置密码
              </el-button>
              <el-button
                v-permission="'user:manage'"
                link
                type="danger"
                @click="deleteUser(row)"
                :disabled="row.id === 1"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 2: 角色管理 -->
      <el-tab-pane label="角色管理" name="roles">
        <el-button
          v-permission="'role:manage'"
          type="success"
          :icon="'Plus'"
          @click="openCreateRole"
          style="margin-bottom:12px"
        >
          新建角色
        </el-button>
        <el-table :data="roles" v-loading="loading" stripe border>
          <el-table-column prop="code" label="角色代码" width="160" />
          <el-table-column prop="name" label="角色名" width="180" />
          <el-table-column label="系统预置" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.is_system" type="info" size="small">系统</el-tag>
              <el-tag v-else type="success" size="small">自定义</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限数" width="100">
            <template #default="{ row }">
              <el-tag>{{ row.permissions?.length ?? 0 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限代码">
            <template #default="{ row }">
              <div class="code-list">
                <el-tag
                  v-for="p in row.permissions?.slice(0, 5)"
                  :key="p"
                  type="info"
                  size="small"
                  effect="plain"
                  class="code-tag"
                >
                  {{ p }}
                </el-tag>
                <el-tag v-if="(row.permissions?.length ?? 0) > 5" size="small">
                  +{{ (row.permissions?.length ?? 0) - 5 }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button
                v-permission="'role:manage'"
                link
                type="primary"
                @click="openEditRole(row)"
                :disabled="row.is_system"
              >
                编辑
              </el-button>
              <el-button
                v-permission="'role:manage'"
                link
                type="danger"
                @click="deleteRole(row)"
                :disabled="row.is_system"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 3: 权限目录 -->
      <el-tab-pane label="权限目录" name="permissions">
        <el-table :data="permissions" v-loading="loading" stripe border>
          <el-table-column prop="code" label="权限代码" width="220" />
          <el-table-column prop="name" label="权限名" width="180" />
          <el-table-column prop="resource_type" label="资源" width="120" />
          <el-table-column prop="action" label="操作" width="120" />
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <!-- Tab 4: 我的角色 -->
      <el-tab-pane label="我的角色" name="my-roles">
        <el-table :data="myRoles" v-loading="loading" stripe border>
          <el-table-column prop="code" label="角色代码" width="160" />
          <el-table-column prop="name" label="角色名" width="200" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 用户增/改/重置密码弹窗 -->
    <el-dialog
      v-model="userDialog"
      :title="userDialogMode === 'create' ? '新增用户'
              : userDialogMode === 'edit' ? '编辑用户'
              : '重置密码'"
      width="520px"
    >
      <el-form ref="userFormRef" :model="userForm" :rules="userFormRules" label-width="100px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="userForm.username" :disabled="userDialogMode !== 'create'" />
        </el-form-item>
        <el-form-item label="工号" prop="employeeNo">
          <el-input v-model="userForm.employeeNo" :disabled="userDialogMode !== 'create'" placeholder="如 E000002" />
        </el-form-item>
        <el-form-item
          v-if="userDialogMode !== 'edit'"
          label="密码"
          prop="password"
        >
          <el-input v-model="userForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item
          v-if="userDialogMode === 'edit'"
          label="新密码"
        >
          <el-input
            v-model="userForm.password"
            type="password"
            show-password
            placeholder="不修改则留空"
          />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="userForm.realName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="userForm.email" />
        </el-form-item>
        <el-form-item label="启用 MFA">
          <el-switch v-model="userForm.mfaEnabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 用户分配角色弹窗 -->
    <el-dialog
      v-model="userAssignDialog"
      :title="`分配角色 - ${userAssignForm.user?.employee_no ?? ''}`"
      width="520px"
    >
      <el-form label-width="80px">
        <el-form-item label="工号">
          <span>{{ userAssignForm.user?.employee_no }}</span>
        </el-form-item>
        <el-form-item label="姓名">
          <span>{{ userAssignForm.user?.real_name }}</span>
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="userAssignForm.roleIds"
            multiple
            placeholder="选择角色"
            style="width:100%"
          >
            <el-option
              v-for="r in roles"
              :key="r.id"
              :label="`${r.name} (${r.code})`"
              :value="r.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userAssignDialog = false">取消</el-button>
        <el-button type="primary" @click="saveAssignRoles">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色编辑弹窗 -->
    <el-dialog
      v-model="roleDialog"
      :title="roleDialogMode === 'create' ? '新建角色' : '编辑角色'"
      width="780px"
    >
      <el-form ref="roleFormRef" :model="roleForm" :rules="roleFormRules" label-width="100px">
        <el-form-item label="角色代码" prop="code">
          <el-input v-model="roleForm.code" :disabled="roleDialogMode === 'edit'" placeholder="如 dept_manager" />
        </el-form-item>
        <el-form-item label="角色名" prop="name">
          <el-input v-model="roleForm.name" placeholder="如 部门管理员" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="roleForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="权限">
          <div class="perm-group-list">
            <div
              v-for="[resource, perms] in permissionsByResource"
              :key="resource"
              class="perm-group"
            >
              <h4>{{ resource }} <span class="perm-count">({{ perms.length }})</span></h4>
              <el-checkbox-group v-model="roleForm.permissionIds">
                <el-checkbox
                  v-for="p in perms"
                  :key="p.id"
                  :value="p.id"
                  class="perm-checkbox"
                >
                  <span class="code">{{ p.code }}</span>
                  <span class="name">{{ p.name }}</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rbac-management { padding: 16px; }
.code-list { display: inline-flex; flex-wrap: wrap; gap: 4px; }
.code-tag { margin: 0; }

.perm-group-list {
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 12px;
}
.perm-group { margin-bottom: 16px; }
.perm-group:last-child { margin-bottom: 0; }
.perm-group h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.perm-count { font-weight: normal; color: var(--el-text-color-secondary); font-size: 12px; }
.perm-checkbox { width: 100%; margin: 0 !important; }
.perm-checkbox .code { font-family: monospace; font-weight: 600; margin-right: 8px; }
.perm-checkbox .name { color: var(--el-text-color-regular); font-size: 12px; }
</style>
