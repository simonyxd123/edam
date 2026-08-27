<script setup lang="ts">
/**
 * 角色权限管理页（v3.2 V-1 RBAC — Phase 4 完整版）
 *
 * Tab 1：用户管理（assignRoles 弹窗）
 * Tab 2：角色管理（CRUD + 分配权限弹窗）
 * Tab 3：权限目录（只读）
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { rbacApi, type Permission, type Role, type UserRole, type User as UserDoc } from '@/api/rbac';
import { useUserStore } from '@/stores/user';

const userStore = useUserStore();

const activeTab = ref('users');

const users = ref<UserDoc[]>([]);
const roles = ref<Role[]>([]);
const permissions = ref<Permission[]>([]);
const myRoles = ref<UserRole[]>([]);
const loading = ref(false);

// ============== 用户列表 + 分配角色弹窗 ==============
const userAssignDialog = ref(false);
const userAssignForm = ref<{ user: UserDoc | null; roleIds: number[] }>({
  user: null,
  roleIds: [],
});

async function loadUsers() {
  try {
    const resp = await rbacApi.listUsers({ page: 1, page_size: 100 });
    users.value = resp.items;
  } catch (e: any) {
    ElMessage.error('加载用户列表失败：' + (e?.message || '未知'));
  }
}

async function openAssignRoles(user: UserDoc) {
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
    loadUsers();
  } catch (e: any) {
    ElMessage.error('分配失败：' + (e?.message || '未知'));
  }
}

// ============== 角色 CRUD ==============
const roleDialog = ref(false);
const roleFormRef = ref<FormInstance>();
const roleForm = ref<{
  id?: number;
  code: string;
  name: string;
  description: string;
  permissionIds: number[];
}>({
  code: '',
  name: '',
  description: '',
  permissionIds: [],
});

function openCreateRole() {
  if (!userStore.hasPermission('role:manage')) {
    ElMessage.warning('没有 role:manage 权限');
    return;
  }
  roleForm.value = { code: '', name: '', description: '', permissionIds: [] };
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
    permissionIds: [],  // 暂时不能从 Role.permissions 拿 id，需要后端 listRoles 时返回 permission_ids
  };
  // 简化：从 role.permissions 的 code 反查 permission_id
  roleForm.value.permissionIds = permissions.value
    .filter(p => role.permissions.includes(p.code))
    .map(p => p.id);
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
    if (roleForm.value.id) {
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

// ============== 权限目录 ==============
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

// 按资源类型分组（弹窗里分组显示）
const permissionsByResource = computed(() => {
  const map = new Map<string, Permission[]>();
  for (const p of permissions.value) {
    if (!map.has(p.resource_type)) map.set(p.resource_type, []);
    map.get(p.resource_type)!.push(p);
  }
  return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
});

// ============== 我的角色 ==============
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
        <el-button
          v-if="userStore.hasPermission('user:read')"
          type="primary"
          :icon="'Plus'"
          @click="loadUsers"
          style="margin-bottom:12px"
        >
          刷新
        </el-button>
        <el-table :data="users" v-loading="loading" stripe border>
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="username" label="用户名" width="140" />
          <el-table-column prop="employee_no" label="工号" width="120" />
          <el-table-column prop="real_name" label="姓名" width="140" />
          <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                {{ row.status === 1 ? 'active' : 'disabled' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button
                v-permission="'user:manage'"
                link
                type="primary"
                @click="openAssignRoles(row)"
              >
                分配角色
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Tab 2: 角色管理 -->
      <el-tab-pane label="角色管理" name="roles">
        <el-button
          v-permission="'role:manage'"
          type="primary"
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
              <span class="code-list">
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
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
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
      :title="roleForm.id ? '编辑角色' : '新建角色'"
      width="780px"
    >
      <el-form ref="roleFormRef" :model="roleForm" :rules="roleFormRules" label-width="100px">
        <el-form-item label="角色代码" prop="code">
          <el-input v-model="roleForm.code" :disabled="!!roleForm.id" placeholder="如 dept_manager" />
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
