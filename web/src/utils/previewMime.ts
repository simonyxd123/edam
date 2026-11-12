/**
 * 预览 MIME 白名单（2026-11-11 代码审计第四轮 §3，high —— 存储型 XSS）。
 *
 * <p><b>为什么用精确枚举，不用前缀匹配</b>：
 *
 * <p>原来的判定是：
 * ```ts
 * mime.startsWith('application/pdf') || mime.startsWith('image/') || mime.startsWith('text/')
 * ```
 *
 * <p>两个洞：
 * <ul>
 *   <li>`image/` 前缀把 `image/svg+xml` 带进来了 ——
 *       SVG 是**可执行 XML**，浏览器会内联解析并执行其中的 `<script>`。</li>
 *   <li>`text/` 更宽 —— `text/html` 是最经典的 XSS 载体。</li>
 * </ul>
 *
 * <p><b>前端这一层尤其关键</b>：预览走
 * `fetch → blob → URL.createObjectURL → iframe`，
 * **完全绕过了服务端的 `Content-Disposition`** ——
 * 后端就算回了 `attachment`，前端照样把字节塞进 iframe 里渲染。
 *
 * <p>而且 Blob URL 的 origin **继承自当前页面** ⇒
 * SVG 里的脚本就在**本站 origin** 下执行 ⇒
 * 能直接读 localStorage 里的 `access_token` / `refresh_token`。
 *
 * <p>后端也做了同样的收紧（`DocumentPreviewService.isInlineSafe`），
 * 两边保持一致。
 */

/**
 * 可以内联渲染（塞进 iframe）的 MIME。
 *
 * <p>只有**确定不含脚本**的类型才进来。
 */
export const INLINE_SAFE_MIME = new Set([
  'application/pdf',
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'image/bmp',
]);

/**
 * 去掉 MIME 的参数部分（`; charset=utf-8`）并统一小写。
 */
function normalize(mime: string | undefined): string {
  if (!mime) return '';
  return mime.toLowerCase().split(';')[0].trim();
}

/**
 * 这个 MIME 能不能内联渲染。
 *
 * @param mime 文档的 MIME
 * @returns true = 可以放进 iframe；false = 只能下载
 */
export function canPreviewInline(mime: string | undefined): boolean {
  return INLINE_SAFE_MIME.has(normalize(mime));
}

/**
 * 给 Blob 用的 MIME —— **只用白名单里的值**，其余一律降级成 octet-stream。
 *
 * <p><b>为什么不能直接用文档的 mime_type</b>：
 *
 * <p>Blob URL 的 origin 继承创建它的页面 ⇒
 * `window.open(blobUrl)` 打开的 SVG，它的脚本就跑在**本站 origin** 下
 * ⇒ 能直接读 localStorage 里的 JWT。
 *
 * <p>而 `canPreviewInline` 只管住了 iframe 那条路，
 * 管不住 `openInNewTab`（新标签页直接打开 blob URL）。
 *
 * <p>所以这里把 Blob 的类型也钉死：不在白名单 ⇒ octet-stream
 * ⇒ 浏览器只下载不渲染。
 */
export function safePreviewMime(mime: string | undefined): string {
  const base = normalize(mime);
  return INLINE_SAFE_MIME.has(base) ? base : 'application/octet-stream';
}
