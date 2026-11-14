import { describe, it, expect } from 'vitest';
import { safeError, redactSecrets } from './safeLog';

/**
 * 控制台日志脱敏的回归测试（2026-11-13，第三轮代码审计 §6/§7 的延伸）。
 *
 * <p><b>为什么必须有这个测试</b>：
 *
 * <p>前端有 11 处 {@code console.error('...', e)} 直接打 axios 错误对象，
 * 而 axios 错误对象的 {@code config.headers.Authorization} 里就是**登录 JWT**
 * （实测确认，见 {@code web/probe-axios-leak.mjs}）。
 *
 * <p>⇒ 每一个出错的前端请求都会把凭据打进浏览器控制台。
 * 控制台里的东西会被录屏、截图、浏览器扩展、远程协助看到。
 *
 * <p>项目硬性规则：**日志中不得打印 token 本身（连哈希都不打）**。
 */
describe('safeLog —— 控制台日志脱敏', () => {
  describe('safeError', () => {
    it('把 Authorization 头整个丢掉（这是登录 JWT 本身）', () => {
      const e = {
        message: 'Request failed',
        config: {
          url: '/videos',
          method: 'get',
          headers: { Authorization: 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig' },
        },
      };
      const out = JSON.stringify(safeError(e));
      expect(out).not.toContain('eyJhbGciOiJIUzI1NiJ9');
      expect(out).not.toContain('Bearer');
      // 整个 headers 都不进摘要 —— 只挑排障需要的字段（url / method）
      expect(out).not.toContain('Authorization');
      // 但排障需要的字段还在
      expect(out).toContain('/videos');
      expect(out).toContain('get');
    });

    it('把 Cookie 头也打码', () => {
      const e = {
        message: 'x',
        config: { headers: { Cookie: 'access_token=abc123; other=1' } },
      };
      expect(JSON.stringify(safeError(e))).not.toContain('abc123');
    });

    it('打码 URL 里的 token 参数', () => {
      const e = {
        message: 'x',
        config: { url: '/api/v1/playback/12/playlist.m3u8?token=SECRETTOKENVALUE' },
      };
      const out = JSON.stringify(safeError(e));
      expect(out).not.toContain('SECRETTOKENVALUE');
      expect(out).toContain('token=***');
    });

    it('打码错误消息里的裸 JWT', () => {
      const e = {
        message: 'upstream rejected eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SIGNATURE123',
      };
      expect(JSON.stringify(safeError(e))).not.toContain('SIGNATURE123');
    });

    it('丢掉体积大且带 URL 的 request / response 对象', () => {
      const e = {
        message: 'x',
        request: { responseURL: 'https://api/playback/12/key?token=SECRET', readyState: 4 },
        response: { status: 401, data: { detail: 'token=SECRET' } },
      };
      const out = JSON.stringify(safeError(e));
      // 凭据一个字都不能留
      expect(out).not.toContain('SECRET');
      // request / response 原对象不进摘要（只留 status 与 detail 这几个字段）
      expect(out).not.toContain('responseURL');
      expect(out).not.toContain('readyState');
      // 状态码仍要留着（排障需要）
      expect(out).toContain('401');
    });

    it('保留排障需要的字段：状态码 / 方法 / URL / detail', () => {
      const e = {
        message: 'Request failed with status code 403',
        config: { url: '/documents/batch-permission', method: 'post', headers: {} },
        response: { status: 403, data: { detail: '不能授出自己没有的权限：下载' } },
      };
      const out = safeError(e) as Record<string, unknown>;
      expect(out.status).toBe(403);
      expect(out.method).toBe('post');
      expect(out.url).toBe('/documents/batch-permission');
      expect(out.detail).toContain('不能授出自己没有的权限');
    });

    it('不是对象的错误也能安全处理', () => {
      expect(safeError('boom').message).toBe('boom');
      expect(safeError(null).message).toBe('null');
      expect(safeError(undefined).message).toBe('undefined');
    });

    it('不会因为循环引用而栈溢出', () => {
      // axios 的 error.request ↔ error.config 互相引用
      const e: any = { message: 'x' };
      e.config = { url: '/x', headers: {} };
      e.request = { responseURL: 'http://x' };
      e.config.self = e;
      expect(() => safeError(e)).not.toThrow();
    });
  });

  describe('redactSecrets', () => {
    it('URL 里的 token / access_token / refresh_token 都打码', () => {
      expect(redactSecrets('/a?token=AAA')).toBe('/a?token=***');
      expect(redactSecrets('/a?access_token=AAA')).toBe('/a?access_token=***');
      expect(redactSecrets('/a?refresh_token=AAA')).toBe('/a?refresh_token=***');
      expect(redactSecrets('/a?x=1&token=AAA&y=2')).toBe('/a?x=1&token=***&y=2');
    });

    it('嵌套对象里的敏感头也打码', () => {
      const out = JSON.stringify(
        redactSecrets({ a: { headers: { authorization: 'Bearer XYZ' } } }),
      );
      expect(out).not.toContain('XYZ');
    });

    it('数组里的元素也打码', () => {
      const out = JSON.stringify(redactSecrets(['/a?token=AAA', 'plain']));
      expect(out).not.toContain('AAA');
      expect(out).toContain('plain');
    });

    it('太深的结构直接截断（避免循环引用）', () => {
      let deep: any = 'bottom';
      for (let i = 0; i < 10; i++) deep = { next: deep };
      expect(() => redactSecrets(deep)).not.toThrow();
    });
  });
});
