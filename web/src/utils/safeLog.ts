/**
 * 控制台日志的**脱敏**辅助。
 *
 * <p><b>为什么需要它</b>（2026-11-13 修，第三轮代码审计 §6/§7 的延伸）：
 *
 * <p>前端有 11 处 {@code console.error('...', e)} 直接把 **axios 错误对象**打出去。
 * 而 axios 的错误对象里带着 {@code config} —— 里面有：
 *
 * <pre>{@code
 * error.config.headers.Authorization = 'Bearer <登录 JWT>'
 * }</pre>
 *
 * <p>也就是说，**每一个出错的前端请求都会把登录 JWT 打进浏览器控制台**。
 * 实测确认（{@code web/probe-axios-leak.mjs}）：
 * <pre>
 * JSON.stringify(error) 里出现 SECRET 了吗： true
 * error.config.headers.Authorization = Bearer SUPER_SECRET_LOGIN_JWT
 * }</pre>
 *
 * <p>控制台里的东西会被谁看到？
 * <ul>
 *   <li>录屏 / 截图（用户排查问题时随手一录就带出去了）</li>
 *   <li>浏览器扩展</li>
 *   <li>远程协助 / 客服代操作</li>
 *   <li>CI 里把控制台输出当日志收集</li>
 * </ul>
 *
 * <p>项目硬性规则：**日志中不得打印 token 本身（连哈希都不打）**。
 * 后端那一侧做得很好（{@code PlaybackController} 只打 {@code token_len={}}），
 * 前端这一侧是漏网的。
 *
 * <p><b>本模块的用法</b>：把 {@code console.error('xxx', e)} 改成
 * {@code console.error('xxx', safeError(e))} ——
 * 拿到的是一份**能定位问题、但不含任何凭据**的摘要。
 */

/** 打码用的占位符（与后端 AuditDetail.scrub 的口径一致） */
const REDACTED = '***';

/** 需要打码的请求头（大小写不敏感） */
const SENSITIVE_HEADERS = ['authorization', 'proxy-authorization', 'cookie', 'set-cookie'];

/**
 * 把任意值里的凭据打码。
 *
 * <p>处理三类：
 * <ol>
 *   <li>URL 里的 {@code ?token=...} / {@code &token=...}</li>
 *   <li>请求头里的 {@code Authorization} / {@code Cookie}</li>
 *   <li>裸的 JWT（形如 {@code xxx.yyy.zzz} 的三段式）</li>
 * </ol>
 */
export function redactSecrets(value: unknown, depth = 0): unknown {
  // 太深就停 —— 错误对象里有循环引用（request → config → ...）
  if (depth > 4) return '[...]';
  if (value === null || value === undefined) return value;

  if (typeof value === 'string') {
    return value
      // URL / 文本里的 token 参数。
      //
      // 前缀用 `[\s"'?&=]` 而不是只认 `?&` —— 因为 token 值可能出现在
      // 各种文本里，而不只是 URL：
      //   - "token=SECRET"（键值对文本，键在最前面）
      //   - "Invalid key URI: \"/a/key?token=SECRET\""（嵌在引号里）
      //   - "access_token=SECRET" / "refresh_token=SECRET"
      .replace(/([\s"'?&=]|^)((?:access_|refresh_)?token=)[^&\s"';]*/gi,
        `$1$2${REDACTED}`)
      // 裸 JWT（三段式 base64url）
      .replace(/\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g, REDACTED);
  }

  if (Array.isArray(value)) {
    return value.map((v) => redactSecrets(v, depth + 1));
  }

  if (typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (SENSITIVE_HEADERS.includes(k.toLowerCase())) {
        out[k] = REDACTED;
      } else if (k === 'config') {
        out[k] = redactSecrets(v, depth + 1);
      } else if (k === 'request' || k === 'response') {
        // XMLHttpRequest / 响应对象 —— 体积大且带 URL，不打
        out[k] = '[omitted]';
      } else {
        out[k] = redactSecrets(v, depth + 1);
      }
    }
    return out;
  }

  return value;
}

/**
 * 把 axios 错误（或任意错误）转成**可安全打印**的摘要。
 *
 * <p>保留排障需要的字段：状态码、错误类型、阶段、消息、URL（已打码）。
 * 丢掉体积大且含凭据的部分：请求头、请求/响应对象。
 *
 * @param e 捕获到的错误
 * @returns 一份不含任何凭据的摘要对象
 */
export function safeError(e: unknown): Record<string, unknown> {
  if (!e || typeof e !== 'object') {
    return { message: String(e) };
  }

  const err = e as {
    message?: string;
    name?: string;
    code?: string;
    status?: number;
    response?: { status?: number; data?: unknown };
    config?: { url?: string; method?: string; baseURL?: string };
  };

  const summary: Record<string, unknown> = {
    name: err.name,
    code: err.code,
    message: redactSecrets(err.message),
    status: err.response?.status ?? err.status,
  };

  // URL —— 后端很多错误信息里带着 URL（含 token），已打码
  const url = err.config?.url ?? err.config?.baseURL;
  if (url) {
    summary.url = redactSecrets(url);
  }
  if (err.config?.method) {
    summary.method = err.config.method;
  }

  // 响应体 —— 后端 Problem Details 的 detail 是可操作信息，值得留
  const data = err.response?.data as { detail?: string; title?: string } | undefined;
  if (data?.detail) {
    summary.detail = redactSecrets(data.detail);
  } else if (data?.title) {
    summary.title = redactSecrets(data.title);
  }

  return summary;
}
