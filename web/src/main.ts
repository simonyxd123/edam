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
import { handleSessionInvalid, isPublicEndpoint } from './utils/session';

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
//
// 注意：会话失效的统一处理在 utils/session.ts（清 token + 提示 + 跳登录页），
// 这里只负责"检测到 401 就转交"，不再各写一份。

const ORIG_FETCH = window.fetch.bind(window);

function isApiPath(url: string | undefined): boolean {
  return !!url && (url.startsWith('/api/') || url.includes('/api/'));
}

function extractUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

window.fetch = async function (
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const url = extractUrl(input);
  if (!isApiPath(url) || isPublicEndpoint(url)) {
    return ORIG_FETCH(input, init);
  }
  const resp = await ORIG_FETCH(input, init);
  if (resp.status === 401) {
    handleSessionInvalid('fetch:401');
  }
  return resp;
};

console.log('[global] fetch wrapper installed');

// XHR 拦截：axios 默认用 XMLHttpRequest，fetch wrapper 拦不到，必须同时改 XHR
(function () {
  const OrigXHR = window.XMLHttpRequest;

  function newXHR(): XMLHttpRequest {
    const xhr = new OrigXHR();
    let url: string | null = null;
    const origOpen = xhr.open.bind(xhr);
    // 用 apply 转发全部参数 —— 原来的 rest 展开在 TS 下与 XMLHttpRequest.open
    // 的重载签名不兼容
    xhr.open = function (this: XMLHttpRequest, method: string, u: string | URL, ...rest: unknown[]) {
      url = typeof u === 'string' ? u : u.toString();
      return (origOpen as (...args: unknown[]) => void).apply(xhr, [method, u, ...rest]);
    };
    xhr.addEventListener('readystatechange', function () {
      if (xhr.readyState === 4) {
        if (isApiPath(url ?? undefined) && !isPublicEndpoint(url ?? undefined) && xhr.status === 401) {
          handleSessionInvalid('xhr:401');
        }
      }
    });
    return xhr;
  }

  window.XMLHttpRequest = newXHR as unknown as typeof XMLHttpRequest;
  console.log('[global-xhr] XMLHttpRequest wrapper installed');

  // 兜底：unhandledrejection 监听
  window.addEventListener('unhandledrejection', function (e: PromiseRejectionEvent) {
    const reason = e?.reason as { response?: { status?: number }; config?: { url?: string } } | undefined;
    if (reason?.response?.status === 401) {
      const u = reason.config?.url || '';
      if (isApiPath(u) && !isPublicEndpoint(u)) {
        handleSessionInvalid('unhandledrejection:401');
      }
    }
  });
})();

// 页面加载时主动检查 token 是否过期（不依赖 401 响应拦截）
// 解析 JWT payload 拿 exp 字段对比当前时间
(function () {
  function isJwtExpired(token: string): boolean {
    try {
      const parts = token.split('.');
      // 解析失败**不等于**过期 —— 后端哪天换成非 JWT token，
      // 原实现一律判过期会导致"每次刷新都登出"
      if (parts.length !== 3) return false;
      // 兼容 base64url 和 base64
      const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      const pad = payload.length % 4;
      const padded = payload + (pad ? '='.repeat(4 - pad) : '');
      const json = atob(padded);
      const data = JSON.parse(json);
      const exp = data.exp;
      if (typeof exp !== 'number') return false;
      return Date.now() / 1000 >= exp;
    } catch {
      // 解析失败 → 不做判断，交给 401 拦截器处理
      return false;
    }
  }
  const token = localStorage.getItem('access_token');
  if (token && isJwtExpired(token)) {
    console.warn('[global] JWT expired on page load, clearing and redirecting');
    handleSessionInvalid('startup:jwt-expired');
  }
})();

app.mount('#app');


