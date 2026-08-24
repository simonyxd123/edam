<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useUserStore } from '@/stores/user';
import axios from 'axios';

const userStore = useUserStore();

const activeTab = ref('profile');

const profile = ref({
  username: '',
  email: '',
  phone: '',
  real_name: '',
});

const passwordForm = ref({
  old_password: '',
  new_password: '',
  confirm_password: '',
});

const systemConfig = ref({
  watermark_enabled: true,
  watermark_template: '{employee_no}|{timestamp}',
  login_max_attempts: 5,
  password_rotation_days: 90,
  session_timeout_minutes: 60,
});

const msg = ref<{ type: 'success' | 'error'; text: string } | null>(null);

onMounted(async () => {
  try {
    const me = await axios.get('/api/v1/auth/me');
    profile.value = {
      username: me.data.username || me.data.employee_no || '',
      email: me.data.email || '',
      phone: me.data.phone || '',
      real_name: me.data.real_name || '',
    };
  } catch {
    profile.value = {
      username: userStore.user?.employee_no || '',
      email: '',
      phone: '',
      real_name: userStore.user?.real_name || '',
    };
  }
});

function show(type: 'success' | 'error', text: string) {
  msg.value = { type, text };
  setTimeout(() => { msg.value = null; }, 3000);
}

async function saveProfile() {
  try {
    await axios.put('/api/v1/auth/profile', profile.value);
    show('success', '个人信息已更新');
  } catch (e: any) {
    show('error', e?.response?.data?.message || '更新失败');
  }
}

async function changePassword() {
  if (passwordForm.value.new_password !== passwordForm.value.confirm_password) {
    show('error', '两次输入的新密码不一致');
    return;
  }
  if (passwordForm.value.new_password.length < 12) {
    show('error', '新密码至少 12 位');
    return;
  }
  try {
    await axios.post('/api/v1/auth/change-password', {
      old_password: passwordForm.value.old_password,
      new_password: passwordForm.value.new_password,
    });
    show('success', '密码已修改，下次登录生效');
    passwordForm.value = { old_password: '', new_password: '', confirm_password: '' };
  } catch (e: any) {
    show('error', e?.response?.data?.message || '密码修改失败');
  }
}

async function saveSystemConfig() {
  try {
    await axios.put('/api/v1/admin/system-config', systemConfig.value);
    show('success', '系统配置已保存');
  } catch (e: any) {
    show('error', e?.response?.data?.message || '保存失败（需要管理员权限）');
  }
}
</script>

<template>
  <div class="settings">
    <h2>系统设置</h2>

    <el-alert
      v-if="msg"
      :title="msg.text"
      :type="msg.type"
      show-icon
      :closable="false"
      style="margin-bottom:16px"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="个人信息" name="profile">
        <el-form label-width="120px" style="max-width:560px">
          <el-form-item label="工号">
            <el-input v-model="profile.username" disabled />
          </el-form-item>
          <el-form-item label="姓名">
            <el-input v-model="profile.real_name" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="profile.email" type="email" />
          </el-form-item>
          <el-form-item label="手机">
            <el-input v-model="profile.phone" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveProfile">保存</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="修改密码" name="password">
        <el-form label-width="120px" style="max-width:560px">
          <el-form-item label="旧密码">
            <el-input v-model="passwordForm.old_password" type="password" show-password />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="passwordForm.new_password" type="password" show-password />
            <div style="font-size:12px;color:#909399;margin-top:4px">
              至少 12 位，含大小写字母 + 数字 + 特殊字符
            </div>
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="passwordForm.confirm_password" type="password" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="changePassword">修改密码</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="系统配置" name="system">
        <el-form label-width="180px" style="max-width:560px">
          <el-form-item label="启用动态水印">
            <el-switch v-model="systemConfig.watermark_enabled" />
          </el-form-item>
          <el-form-item label="水印模板">
            <el-input v-model="systemConfig.watermark_template" />
            <div style="font-size:12px;color:#909399;margin-top:4px">
              支持占位符：{employee_no} {timestamp} {ip} {device_id}
            </div>
          </el-form-item>
          <el-form-item label="登录失败锁定阈值">
            <el-input-number v-model="systemConfig.login_max_attempts" :min="1" :max="20" />
          </el-form-item>
          <el-form-item label="密码轮转天数">
            <el-input-number v-model="systemConfig.password_rotation_days" :min="30" :max="365" />
          </el-form-item>
          <el-form-item label="会话超时（分钟）">
            <el-input-number v-model="systemConfig.session_timeout_minutes" :min="5" :max="480" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveSystemConfig">保存配置</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.settings { padding: 16px; }
</style>