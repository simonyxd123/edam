<script setup lang="ts">
/**
 * 分享授权对话框（视频 / 文档通用）
 *
 * <p>调后端的 batch-permission 落库。授权模型见
 * `doc/database_schema.md` §3.1 / §3.2：
 * - `actions` 是**位掩码**：多个动作位可以同时给
 * - 同一 (资源, 用户) 唯一 ⇒ 再次授权是**覆盖**而不是叠加
 * - 有效期可选：留空 = 永不过期
 *
 * <p>视频与文档的动作位**差一位**：文档多一个「打印」（方案书 5.4
 * 「禁止打印/禁止截屏」）。所以 `kind` 决定可勾选的动作。
 */
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { ACTION, permissionApi, type PermissionGrant } from '@/api/permission';
import { rbacApi, type User } from '@/api/rbac';

const props = defineProps<{
  modelValue: boolean;
  /** 资源类型 */
  kind: 'video' | 'doc';
  /** 资源 id */
  resourceId: number;
  /** 资源标题（对话框标题里显示） */
  resourceTitle?: string;
  /** 是否有「打印」这一位（文档有，视频没有） */
  allowPrint?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'saved'): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
});

/** 可勾选的动作（视频没有「打印」位） */
const ACTION_OPTIONS = computed<{ value: number; label: string }[]>(() => {
  const opts: { value: number; label: string }[] = [
    { value: ACTION.VIEW, label: '查看' },
    { value: ACTION.DOWNLOAD, label: '下载' },
    { value: ACTION.EDIT, label: '编辑' },
    { value: ACTION.DELETE, label: '删除' },
    { value: ACTION.DISTRIBUTE, label: '再分享' },
  ];
  if (props.allowPrint) {
    opts.push({ value: ACTION.PRINT, label: '打印' });
  }
  return opts;
});

// ===== 表单状态 =====
const selectedUserIds = ref<number[]>([]);
const selectedActions = ref<number[]>([ACTION.VIEW]);
const expireMode = ref<'never' | 'days'>('never');
const expireDays = ref<number>((30));
const saving = ref(false);
const loadingUsers = ref(false);
const loadingGrants = ref(false);

// ===== 候选人 =====
const userOptions = ref<User[]>([]);
const userKeyword = ref('');

/** 已授权的人（含已过期未清理的） */
const existingGrants = ref<PermissionGrant[]>([]);

const userIdToLabel = computed(() => {
  const m = new Map<number, string>();
  for (const u of userOptions.value) {
    m.set(u.id, u.real_name ? `${u.real_name}（${u.employee_no}）` : u.employee_no);
  }
  return m;
});

/** 已被授权的用户 id（默认排除，避免把没权限的人误覆盖成有权限） */
const grantedUserIds = computed(() => new Set(existingGrants.value.map((g) => g.user_id)));

function isExpired(g: PermissionGrant): boolean {
  if (!g.expire_at) return false;
  return new Date(g.expire_at).getTime() <= Date.now();
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return;
    // 每次打开重置表单 —— 上次的授权是覆盖式的，留着会误导
    selectedUserIds.value = [];
    selectedActions.value = [ACTION.VIEW];
    expireMode.value = 'never';
    expireDays.value = 30;
    await Promise.all([loadUsers(), loadGrants()]);
  },
);

async function loadUsers() {
  loadingUsers.value = true;
  try {
    const page = await rbacApi.listUsers({ page: 1, page_size: 200 });
    userOptions.value = page.items ?? [];
  } catch (e: any) {
    // 拿不到用户列表不该挡住整个对话框 —— 只是不能选人
    console.error('[ShareDialog] load_users_failed:', e);
    userOptions.value = [];
  } finally {
    loadingUsers.value = false;
  }
}

async function loadGrants() {
  loadingGrants.value = true;
  try {
    if (props.kind === 'video') {
      const res = await permissionApi.listVideoPermissions(props.resourceId);
      existingGrants.value = res.items ?? [];
    } else {
      const res = await permissionApi.listDocPermissions(props.resourceId);
      existingGrants.value = res.items ?? [];
    }
  } catch (e: any) {
    // 没有 list 权限（只有 share）时列不出来 —— 不影响授权本身
    console.error('[ShareDialog] load_grants_failed:', e);
    existingGrants.value = [];
  } finally {
    loadingGrants.value = false;
  }
}

/** 已授权列表（含过期标记） */
const grantedRows = computed(() =>
  existingGrants.value.map((g) => ({
    ...g,
    label: userIdToLabel.value.get(g.user_id) ?? `用户 #${g.user_id}`,
    actionText: describeActions(g.actions),
    expired: isExpired(g),
  })),
);

function describeActions(actions: number): string {
  const names = ACTION_OPTIONS.value
    .filter((o) => (actions & o.value) !== 0)
    .map((o) => o.label);
  return names.length ? names.join('、') : '无';
}

async function submit() {
  if (selectedUserIds.value.length === 0) {
    ElMessage.warning('请先选择要授权的用户');
    return;
  }
  if (selectedActions.value.length === 0) {
    ElMessage.warning('请至少选择一个动作');
    return;
  }
  // 位掩码：多个动作位相加
  const actions = selectedActions.value.reduce((a, b) => a | b, 0);

  let expireAt: string | undefined;
  if (expireMode.value === 'days') {
    const days = Math.max(1, Math.floor(expireDays.value || 1));
    expireAt = new Date(Date.now() + days * 24 * 3600 * 1000).toISOString();
  }

  saving.value = true;
  try {
    const res =
      props.kind === 'video'
        ? await permissionApi.grantVideo(props.resourceId, selectedUserIds.value, actions, expireAt)
        : await permissionApi.grantDoc(props.resourceId, selectedUserIds.value, actions, expireAt);
    ElMessage.success(`已授权 ${res.granted_count} 个用户`);
    visible.value = false;
    emit('saved');
  } catch (e: any) {
    // 403 = 没有再分享的资格（不是上传者/管理员/持 distribute 位）
    //      或是"授出了自己没有的权限位"（转授越权）
    //
    // 拦截器（api/client.ts）已经弹过提示了 —— 它现在会优先显示后端的 detail，
    // 也就是这里想给用户看的那句话。所以这里**不要再弹一次**
    // （否则同一条消息弹两遍）。
    // `__handled` 是拦截器打的标记，见 client.ts 的错误处理分支。
    if (!e?.__handled) {
      const detail = e?.response?.data?.detail;
      ElMessage.error(detail ? String(detail) : '授权失败');
    }
  } finally {
    saving.value = false;
  }
}

async function revoke(userId: number) {
  try {
    const res =
      props.kind === 'video'
        ? await permissionApi.revokeVideo(props.resourceId, [userId])
        : await permissionApi.revokeDoc(props.resourceId, [userId]);
    ElMessage.success(`已撤销 ${res.revoked_count} 条授权`);
    await loadGrants();
  } catch (e: any) {
    // 同上：拦截器已经弹过提示了，别弹两遍
    if (!e?.__handled) {
      const detail = e?.response?.data?.detail;
      ElMessage.error(detail ? String(detail) : '撤销失败');
    }
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`分享授权${resourceTitle ? ' - ' + resourceTitle : ''}`"
    width="640px"
  >
    <!-- 已有的授权 -->
    <div v-loading="loadingGrants" style="margin-bottom: 16px">
      <div style="font-weight: 600; margin-bottom: 8px">当前已授权</div>
      <el-table v-if="grantedRows.length" :data="grantedRows" size="small" border>
        <el-table-column prop="label" label="用户" min-width="180" />
        <el-table-column prop="actionText" label="权限" min-width="160" />
        <el-table-column label="有效期" width="150">
          <template #default="{ row }">
            <span v-if="row.expired" style="color: var(--el-color-danger)">已过期</span>
            <span v-else>{{ row.expire_at ? new Date(row.expire_at).toLocaleDateString() : '永久' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="revoke(row.user_id)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loadingGrants" description="还没有授权给任何人" :image-size="60" />
    </div>

    <el-form label-width="90px">
      <el-form-item label="选择用户">
        <el-select
          v-model="selectedUserIds"
          multiple
          filterable
          remote
          :remote-method="(kw: string) => (userKeyword = kw)"
          :loading="loadingUsers"
          placeholder="按工号/姓名筛选"
          style="width: 100%"
        >
          <el-option
            v-for="u in userOptions.filter((x) => !grantedUserIds.has(x.id))"
            :key="u.id"
            :label="u.real_name ? `${u.real_name}（${u.employee_no}）` : u.employee_no"
            :value="u.id"
          />
        </el-select>
        <div style="font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px">
          已在授权列表里的人不再出现在下拉里 —— 再次授权是覆盖而不是叠加。
        </div>
      </el-form-item>

      <el-form-item label="授予权限">
        <el-checkbox-group v-model="selectedActions">
          <el-checkbox v-for="o in ACTION_OPTIONS" :key="o.value" :value="o.value">
            {{ o.label }}
          </el-checkbox>
        </el-checkbox-group>
      </el-form-item>

      <el-form-item label="有效期">
        <el-radio-group v-model="expireMode">
          <el-radio value="never">永久</el-radio>
          <el-radio value="days">指定天数</el-radio>
        </el-radio-group>
        <el-input-number
          v-if="expireMode === 'days'"
          v-model="expireDays"
          :min="1"
          :max="365"
          style="margin-left: 12px; width: 130px"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">授权</el-button>
    </template>
  </el-dialog>
</template>
