import { describe, it, expect } from 'vitest';
import { canPreviewInline, safePreviewMime, INLINE_SAFE_MIME } from './previewMime';

/**
 * 预览 MIME 白名单的回归测试
 * （2026-11-11 代码审计第四轮 §3，high —— 存储型 XSS）。
 *
 * <p><b>这个测试为什么必须存在</b>：
 *
 * <p>原来的判定是前缀匹配：
 * ```ts
 * mime.startsWith('application/pdf') || mime.startsWith('image/') || mime.startsWith('text/')
 * ```
 * `image/svg+xml` 命中 `image/` ⇒ SVG 被塞进 iframe ⇒
 * 浏览器在**本站 origin** 下执行它的 `<script>` ⇒
 * 直接偷走 localStorage 里的 access_token / refresh_token。
 *
 * <p>前端这一层尤其关键：预览走 `fetch → blob → createObjectURL → iframe`，
 * **完全绕过服务端的 Content-Disposition** ——
 * 后端回了 `attachment` 也没用，前端照样把字节塞进 iframe 渲染。
 */
describe('previewMime —— 预览 MIME 白名单', () => {
  describe('canPreviewInline', () => {
    it('SVG 绝不能内联（它是可执行 XML，浏览器会跑它的脚本）', () => {
      expect(canPreviewInline('image/svg+xml')).toBe(false);
    });

    it('text/html 绝不能内联（最经典的 XSS 载体）', () => {
      expect(canPreviewInline('text/html')).toBe(false);
    });

    it('text/* 一律不内联（前缀匹配会把 text/html 带进来）', () => {
      expect(canPreviewInline('text/plain')).toBe(false);
      expect(canPreviewInline('text/xml')).toBe(false);
      expect(canPreviewInline('text/javascript')).toBe(false);
    });

    it('未知的二进制类型不内联', () => {
      expect(canPreviewInline('application/octet-stream')).toBe(false);
      expect(canPreviewInline('application/zip')).toBe(false);
      expect(canPreviewInline(undefined)).toBe(false);
      expect(canPreviewInline('')).toBe(false);
    });

    it('PDF 与常见位图仍然内联（别把功能改坏了）', () => {
      expect(canPreviewInline('application/pdf')).toBe(true);
      expect(canPreviewInline('image/png')).toBe(true);
      expect(canPreviewInline('image/jpeg')).toBe(true);
      expect(canPreviewInline('image/gif')).toBe(true);
      expect(canPreviewInline('image/webp')).toBe(true);
      expect(canPreviewInline('image/bmp')).toBe(true);
    });

    it('大小写与参数不影响判定', () => {
      expect(canPreviewInline('IMAGE/PNG')).toBe(true);
      expect(canPreviewInline('application/pdf; charset=utf-8')).toBe(true);
      expect(canPreviewInline('  image/png  ')).toBe(true);
      expect(canPreviewInline('IMAGE/SVG+XML')).toBe(false);
    });

    it('白名单里没有任何可执行类型', () => {
      // 防御：往白名单里加东西时，这一条会拦住
      for (const mime of INLINE_SAFE_MIME) {
        expect(mime).not.toContain('svg');
        expect(mime).not.toContain('html');
        expect(mime).not.toContain('xml');
        expect(mime).not.toMatch(/^(text|application)\/(?!pdf$)/);
      }
    });
  });

  describe('safePreviewMime', () => {
    it('白名单内的类型原样返回', () => {
      expect(safePreviewMime('application/pdf')).toBe('application/pdf');
      expect(safePreviewMime('image/png')).toBe('image/png');
    });

    it('SVG 降级成 octet-stream（Blob URL 继承本站 origin，绝不能让它被渲染）', () => {
      // 这是最关键的一条：
      // Blob URL 的 origin 继承自当前页面 ⇒
      // window.open(blobUrl) 打开的 SVG，脚本就跑在本站 origin 下
      // ⇒ 能直接读 localStorage 里的 JWT。
      expect(safePreviewMime('image/svg+xml')).toBe('application/octet-stream');
    });

    it('HTML 与未知类型一律降级', () => {
      expect(safePreviewMime('text/html')).toBe('application/octet-stream');
      expect(safePreviewMime('application/x-msdownload')).toBe('application/octet-stream');
      expect(safePreviewMime(undefined)).toBe('application/octet-stream');
    });

    it('带参数的 MIME 也能正确归一化', () => {
      expect(safePreviewMime('image/png; charset=binary')).toBe('image/png');
      expect(safePreviewMime('image/svg+xml; charset=utf-8')).toBe('application/octet-stream');
    });

    it('**任何**输入都不会产出可执行的 MIME', () => {
      const hostile = [
        'image/svg+xml',
        'text/html',
        'application/xhtml+xml',
        'text/xml',
        'application/xml',
        'application/javascript',
        'text/javascript',
      ];
      for (const mime of hostile) {
        expect(safePreviewMime(mime)).toBe('application/octet-stream');
      }
    });
  });
});
