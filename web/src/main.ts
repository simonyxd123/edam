/**
 * EDAM 前端入口
 */
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';

import App from './App.vue';
import router from './router';
import './styles/main.scss';
import { formatDateTime, formatDate, formatTime } from './utils/date';
import { permission } from './utils/permission';

const app = createApp(App);

// Pinia 状态管理
app.use(createPinia());

// Vue Router
app.use(router);

// Element Plus UI
app.use(ElementPlus);

// 全局日期格式化函数（模板里直接用：{{ $fmt(row.upload_time) }}）
app.config.globalProperties.$fmt = formatDateTime;
app.config.globalProperties.$fmtDate = formatDate;
app.config.globalProperties.$fmtTime = formatTime;

// 注册所有 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component as any);
}

// v3.2 V-1 RBAC：注册全局权限指令 v-permission="'video:upload'"
app.directive('permission', permission);

app.mount('#app');
