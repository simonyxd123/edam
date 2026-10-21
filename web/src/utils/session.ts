/**
 * 会话失效的统一处理（单一事实来源）。
 *
 * <p>此前 401 处理散落在 4 处且互不自洽：
 * <ul>
 *   <li>`api/client.ts` 的响应拦截器 —— 条件写成 `[400,500)` 全段
 *       （且 `isApiCall` 因 axios `config.url` 是相对路径而恒为 false，
 *       实际从不触发；一旦修好，404 删个视频也会把人登出）</li>
 *   <li>`main.ts` 的 fetch wrapper —— 只判 401，直接跳转，**无提示**</li>
 *   <li>`main.ts` 的 XHR wrapper —— 只判 401，直接跳转，**无提示**</li>
 *   <li>`main.ts` 的启动时 JWT 解析 —— `parts.length !== 3` 一律判过期</li>
 * </ul>
 *
 * <p>现在统一到本模块：清 token → 提示 → 跳登录页，带防重入标志。
 */
import { ElMessage } from 'element-plus';

const TOKEN_KEYS = ['access_token', 'refresh_token', 'user_id'] as const;

/** 防重入：避免多个并发 401 弹多条提示、跳多次 */
let redirecting = false;

export function clearSessionStorage(): void {
  for (const k of TOKEN_KEYS) {
    try {
      localStorage.removeItem(k);
    } catch {
      /* localStorage 不可用（隐私模式）时忽略 */
    }
  }
}

/**
 * 会话已失效：清 token + 提示 + 跳登录页。
 *
 * @param reason 触发原因，仅用于日志
 * @param showMessage 是否弹提示（启动时主动检查已过期时也想提示）
 */
export function handleSessionInvalid(reason: string, showMessage = true): void {
  clearSessionStorage();
  if (window.location.pathname === '/login') {
    return;
  }
  if (redirecting) {
    return;
  }
  redirecting = true;
  console.warn(`[session] invalid, reason=${reason}, redirecting to /login`);
  if (showMessage) {
    ElMessage.warning('会话已失效，请重新登录');
  }
  // 留一点时间让用户看清提示
  setTimeout(() => {
    window.location.href = '/login';
  }, 800);
}

/**
 * 判断某个 URL 是否是后端 API 路径。
 *
 * <p>注意：axios 的 `config.url` 是**相对路径**（如 `/videos`），
 * 完整 URL 由 baseURL 在 adapter 层拼接。判断时必须把 baseURL 算进去，
 * 否则 `isApiCall` 恒为 false。
 */
export function isApiCall(url: string | undefined, baseUrl?: string): boolean {
  if (!url) return false;
  const full = baseUrl ? `${baseUrl}${url}` : url;
  return full.includes('/api/') || full.startsWith('api/');
}

/** 登录 / 刷新这类不该触发"会话失效"的端点 */
export function isPublicEndpoint(url: string | undefined): boolean {
  if (!url) return false;
  return url.includes('/auth/login') || url.includes('/auth/refresh');
}
