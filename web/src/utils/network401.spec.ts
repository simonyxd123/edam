import { describe, expect, it } from 'vitest';
import { isPlaybackAssetEndpoint, shouldHandleDelayed401 } from './network401';

describe('global 401 network policy', () => {
  it.each([
    '/api/v1/playback/7/segment/segment_001.ts?token=playback-token',
    '/api/v1/playback/7/playlist.m3u8?token=playback-token',
    'https://media.example.test/api/v1/playback/7/key?token=playback-token',
  ])('recognizes playback asset URL: %s', (url) => {
    expect(isPlaybackAssetEndpoint(url)).toBe(true);
  });

  it.each([
    '/api/v1/playback/7',
    '/api/v1/playback/7/events',
    '/api/v1/videos/7',
    '/api/v1/playback/7/playlist.m3u8/extra',
  ])('does not exempt non-asset URL: %s', (url) => {
    expect(isPlaybackAssetEndpoint(url)).toBe(false);
  });

  it('never logs out for a playback asset 401', () => {
    expect(shouldHandleDelayed401(
      '/api/v1/playback/7/key?token=expired',
      'access-old',
      'access-old',
    )).toBe(false);
  });

  it('suppresses the stale global 401 after Axios refresh replaces access token', () => {
    expect(shouldHandleDelayed401('/api/v1/videos', 'access-old', 'access-new')).toBe(false);
  });

  it('keeps the non-Axios fallback for an ordinary 401', () => {
    expect(shouldHandleDelayed401('/api/v1/videos', 'access-old', 'access-old')).toBe(true);
    expect(shouldHandleDelayed401('/api/v1/videos', null, null)).toBe(true);
  });
});
