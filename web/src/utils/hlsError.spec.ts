/**
 * hls.js 错误翻译的单测
 *
 * 重点验证两件事：
 * 1. **超时路径**（hls.js 不挂 data.response）不能再显示 "HTTP ??"，
 *    而要说清楚是"无响应（超时）"并指出失败阶段。
 * 2. **token 不能出现在日志/提示里** —— 播放地址里带的是可用的播放 JWT，
 *    原样打出去等于泄露凭据。
 */
import { describe, it, expect } from 'vitest';
import { describePlaybackError, formatPlaybackError, redactTokenFromUrl } from './hlsError';

describe('redactTokenFromUrl', () => {
  it('把 token 参数打码', () => {
    expect(redactTokenFromUrl('https://h/api/v1/playback/7/playlist.m3u8?token=abc.def.ghi'))
      .toBe('https://h/api/v1/playback/7/playlist.m3u8?token=***');
  });

  it('token 在中间（后面还有参数）也能打码', () => {
    expect(redactTokenFromUrl('https://h/p?a=1&token=xyz&b=2'))
      .toBe('https://h/p?a=1&token=***&b=2');
  });

  it('没有 token 就原样返回', () => {
    expect(redactTokenFromUrl('https://h/p?a=1')).toBe('https://h/p?a=1');
  });

  it('url 为空时返回 undefined', () => {
    expect(redactTokenFromUrl(undefined)).toBeUndefined();
    expect(redactTokenFromUrl('')).toBeUndefined();
  });
});

describe('describePlaybackError', () => {
  it('超时路径（无 response）→ 说清楚是"无响应"并指出阶段', () => {
    // 这就是线上报 "HTTP ??" 的那条路径：hls.js 超时时压根不挂 response
    const info = describePlaybackError({ details: 'fragLoadTimeOut' });
    expect(info.status).toBeUndefined();
    expect(info.statusText).toBe('无响应（超时或连接被拒）');
    expect(info.stage).toBe('视频分片');
    expect(info.hint).toContain('origin/端口');
  });

  it('有 HTTP 响应时用真实状态码', () => {
    const info = describePlaybackError({
      details: 'manifestLoadError',
      response: { code: 404 },
    });
    expect(info.status).toBe(404);
    expect(info.statusText).toBe('HTTP 404');
    expect(info.stage).toBe('播放列表');
  });

  it('manifest / frag / key 三个阶段能区分开', () => {
    expect(describePlaybackError({ details: 'manifestLoadError' }).stage).toBe('播放列表');
    expect(describePlaybackError({ details: 'fragLoadError' }).stage).toBe('视频分片');
    expect(describePlaybackError({ details: 'keyLoadError' }).stage).toBe('解密密钥');
  });

  it('未知 details 也有兜底，不抛异常', () => {
    const info = describePlaybackError({ details: 'somethingNew' });
    expect(info.stage).toBe('播放资源');
    expect(info.hint).toBeTruthy();
  });

  it('404 给"还没转码完"的处置建议（最常见的那种）', () => {
    const info = describePlaybackError({ details: 'manifestLoadError', response: { code: 404 } });
    expect(info.hint).toContain('尚未转码完成');
  });

  it('401/403/503 各有对应建议', () => {
    expect(describePlaybackError({ details: 'manifestLoadError', response: { code: 401 } }).hint)
      .toContain('令牌');
    expect(describePlaybackError({ details: 'manifestLoadError', response: { code: 403 } }).hint)
      .toContain('权限');
    expect(describePlaybackError({ details: 'manifestLoadError', response: { code: 503 } }).hint)
      .toContain('对象存储');
  });

  it('无状态码时退回按阶段的排查方向', () => {
    expect(describePlaybackError({ details: 'fragLoadTimeOut' }).hint).toContain('origin/端口');
  });

  it('url 里的 token 被打码', () => {
    const info = describePlaybackError({
      details: 'fragLoadError',
      url: 'https://h/api/v1/playback/7/segment/segment_001.ts?token=SECRET',
    });
    expect(info.url).not.toContain('SECRET');
    expect(info.url).toContain('token=***');
  });
});

describe('formatPlaybackError', () => {
  it('文案里不再出现 "HTTP ??"', () => {
    const msg = formatPlaybackError({ details: 'fragLoadTimeOut' });
    expect(msg).not.toContain('HTTP ??');
    expect(msg).toContain('视频源不可达');
    expect(msg).toContain('视频分片');
  });

  it('404 保留状态码并给排查方向', () => {
    const msg = formatPlaybackError({ details: 'manifestLoadError', response: { code: 404 } });
    expect(msg).toContain('HTTP 404');
    expect(msg).toContain('播放列表');
  });
});
