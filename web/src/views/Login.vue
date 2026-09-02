<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useUserStore } from '@/stores/user';

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const formRef = ref();
const loading = ref(false);

const REMEMBER_KEY = 'edam_remember_employee_no';

// 自动填充上次记住的工号
const remembered = (() => {
  try { return localStorage.getItem(REMEMBER_KEY) || ''; } catch (e) { return ''; }
})();

const form = reactive({
  employee_no: remembered,
  password: '',
  mfa_code: '',
  remember: !!remembered,
});

const rules = {
  employee_no: [{ required: true, message: '请输入工号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

async function handleLogin() {
  await formRef.value?.validate();
  loading.value = true;
  try {
    await userStore.login(form.employee_no, form.password);
    // 记住账号（勾选了 remember 才存）
    try {
      if (form.remember) localStorage.setItem(REMEMBER_KEY, form.employee_no);
      else localStorage.removeItem(REMEMBER_KEY);
    } catch (e) {}
    ElMessage.success('登录成功');
    const redirect = (route.query.redirect as string) || '/';
    router.push(redirect);
  } catch (e: any) {
    // 错误已由 axios 拦截器统一处理
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <template #header>
        <div class="login-header">
          <h1>EDAM</h1>
          <p>企业全格式数字资产防泄密系统</p>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" @submit.prevent>
        <el-form-item label="工号" prop="employee_no">
          <el-input v-model="form.employee_no" placeholder="请输入工号" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-form-item v-if="form.mfa_code !== undefined" label="MFA">
          <el-input v-model="form.mfa_code" placeholder="动态令牌（可选）" />
        </el-form-item>
        <el-form-item>
          <div class="login-actions">
            <el-checkbox v-model="form.remember" size="small">记住账号</el-checkbox>
            <el-button type="primary" native-type="submit" :loading="loading" @click="handleLogin" class="login-btn">
              登录
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div class="footer">
        <small>v3.1.0 · 2026-08-12</small>
      </div>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 400px;
}
.login-header {
  text-align: center;
  h1 { color: #1890ff; margin: 0; font-size: 32px; }
  p { color: #666; font-size: 14px; margin: 8px 0 0; }
}
.login-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.login-btn {
  margin: 0;
}
.footer {
  text-align: center;
  margin-top: 16px;
  color: #999;
}
</style>