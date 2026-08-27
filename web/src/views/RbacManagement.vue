<script setup lang="ts">
/**
 * 角色权限管理页（v3.2 V-1 RBAC）— Phase 4 待补完整 CRUD
 *
 * 当前占位：
 * - 列出所有角色 + 权限映射（只读）
 * - 列出所有权限（只读）
 * - 列出当前用户角色
 *
 * 完整 CRUD（创建/编辑角色 + 分配权限 + 用户角色分配）将在 Phase 4 实现
 */
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { rbacApi, type Permission, type Role, type UserRole } from '@/api/rbac';
import { useUserStore } from '@/stores/user';

const userStore = useUserStore();

const activeTab = ref('roles');
const permissions = ref<Permission[]>([]);
const roles = ref<Role[]>([]);
const myRoles = ref<UserRole[]>([]);
const loading = ref(false);

async function loadAll() {
  loading.value = true;
  try {
    permissions.value = await rbacApi.listPermissions();
    roles.value = await rbacApi.listRoles();
    if (userStore.user?.user_id) {
      myRoles.value = await rbacApi.getUserRoles(userStore.user.user_id);
    }
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || '未知错误'));
  } finally {
    loading.value = false;
  }
}

onMounted(loadAll);
</script>

<template>
  <div class="rbac-management">
    <h2>角色权限管理</h2>
    <el-alert
      v-if="userStore.isAdmin"
      type="success"
      :closable="false"
      show-icon
      title="Admin 用户"
      description="你可以创建/编辑角色、为用户分配角色"
    />
    <el-alert
      v-else
      type="warning"
      :closable="false"
      show-icon
      title="无完整管理权限"
      description="创建/编辑角色需要 role:manage 权限（当前只读）"
    />

    <el-tabs v-model="activeTab" style="margin-top:16px">
      <el-tab-pane label="角色列表" name="roles">
        <el-table :data="roles" v-loading="loading" stripe border>
          <el-table-column prop="code" label="角色代码" width="160" />
          <el-table-column prop="name" label="角色名" width="200" />
          <el-table-column label="权限数" width="100">
            <template #default="{ row }">
              <el-tag>{{ row.permissions?.length ?? 0 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限代码">
            <template #default="{ row }">
              <span class="code-list">
                <el-tag
                  v-for="p in row.permissions"
                  :key="p"
                  type="info"
                  size="small"
                  effect="plain"
                  class="code-tag"
                >
                  {{ p }}
                </el-tag>
              </span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="权限目录" name="permissions">
        <el-table :data="permissions" v-loading="loading" stripe border>
          <el-table-column prop="code" label="权限代码" width="220" />
          <el-table-column prop="name" label="权限名" width="180" />
          <el-table-column prop="resource_type" label="资源" width="120" />
          <el-table-column prop="action" label="操作" width="120" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="我的角色" name="my-roles">
        <el-table :data="myRoles" v-loading="loading" stripe border>
          <el-table-column prop="code" label="角色代码" width="160" />
          <el-table-column prop="name" label="角色名" width="200" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-card shadow="never" style="margin-top:16px">
      <template #header>
        <span>Phase 4 计划（即将实现）</span>
      </template>
      <ul>
        <li>角色 CRUD：创建 / 编辑 / 删除角色，绑定权限</li>
        <li>权限分配：弹窗里按 resource_type 分组的 el-checkbox-group</li>
        <li>用户角色管理：选择用户 → 分配多个角色</li>
        <li>审计日志：每次 RBAC 写操作自动记录</li>
      </ul>
    </el-card>
  </div>
</template>

<style scoped>
.rbac-management { padding: 16px; }
.code-list { display: inline-flex; flex-wrap: wrap; gap: 4px; }
.code-tag { margin: 0; }
</style>
