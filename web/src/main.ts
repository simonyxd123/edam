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

// 全局 fetch 拦截：捕获所有 /api/ 401，强制跳登录页
// 比 axios 拦截器更可靠（Vite HMR 漏应用 axios 代码时 fetch wrapper 仍生效）

const ORIG_FETCH = window.fetch.bind(window);

function isApiPath(url) {
  return url.startsWith('/api/') || url.includes('/api/');
}

function isPublicEndpoint(url) {
  return url.includes('/auth/login') || url.includes('/auth/refresh');
}

function handleSessionInvalid() {
  try {
    localStorage.removeItem('access_token');
    localStorage.removeItem('refresh_token');
    localStorage.removeItem('user_id');
  } catch (e) {}
  if (window.location.pathname !== '/login') {
    if (!window.__sessionInvalidRedirecting) {
      window.__sessionInvalidRedirecting = true;
      console.warn('[global] session invalid, redirecting to /login');
      window.location.href = '/login';
    }
  }
}

window.fetch = async function(input, init) {
  const url = typeof input === 'string' ? input : input.url;
  if (!isApiPath(url) || isPublicEndpoint(url)) {
    return ORIG_FETCH(input, init);
  }
  try {
    const resp = await ORIG_FETCH(input, init);
    if (resp.status === 401) {
      handleSessionInvalid();
    }
    return resp;
  } catch (e) {
    throw e;
  }
};

console.log('[global] fetch wrapper installed');

// XHR 拦截：axios 默认用 XMLHttpRequest，fetch wrapper 拦不到，必须同时改 XHR
(function () {
  const OrigXHR = window.XMLHttpRequest;

  function isApiPath(url) {
    return url && (url.startsWith('/api/') || url.includes('/api/'));
  }

  function isPublicEndpoint(url) {
    return url && (url.includes('/auth/login') || url.includes('/auth/refresh'));
  }

  function handleSessionInvalid(reason, url) {
    try {
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      localStorage.removeItem('user_id');
    } catch (e) {}
    if (window.location.pathname !== '/login') {
      if (!window.__sessionInvalidRedirecting) {
        window.__sessionInvalidRedirecting = true;
        console.warn('[global-xhr] session invalid, reason=' + reason + ' url=' + url);
        window.location.href = '/login';
      }
    }
  }

  function newXHR() {
    const xhr = new OrigXHR();
    let url = null;
    const origOpen = xhr.open.bind(xhr);
    xhr.open = function (method, u, ...rest) {
      url = u;
      return origOpen(method, u, ...rest);
    };
    xhr.addEventListener('readystatechange', function () {
      if (xhr.readyState === 4) {
        if (isApiPath(url) && !isPublicEndpoint(url) && xhr.status === 401) {
          handleSessionInvalid('401', url);
        }
      }
    });
    return xhr;
  }

  window.XMLHttpRequest = newXHR;
  console.log('[global-xhr] XMLHttpRequest wrapper installed');

  // 兜底：unhandledrejection 监听
  window.addEventListener('unhandledrejection', function (e) {
    const reason = e && e.reason;
    if (reason && reason.response && reason.response.status === 401) {
      const u = (reason.config && reason.config.url) || '';
      if (isApiPath(u) && !isPublicEndpoint(u)) {
        handleSessionInvalid('unhandledrejection-401', u);
      }
    }
  });
})();

app.mount('#app');

