import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => {
  const instances: any[] = [];
  class MockHls {
    static Events = {
      MANIFEST_LOADING: 'MANIFEST_LOADING',
      MANIFEST_LOADED: 'MANIFEST_LOADED',
      MANIFEST_PARSED: 'MANIFEST_PARSED',
      LEVEL_LOADING: 'LEVEL_LOADING',
      LEVEL_LOADED: 'LEVEL_LOADED',
      LEVEL_SWITCHED: 'LEVEL_SWITCHED',
      FRAG_LOADING: 'FRAG_LOADING',
      FRAG_LOADED: 'FRAG_LOADED',
      FRAG_PARSED_INIT_SEGMENT: 'FRAG_PARSED_INIT_SEGMENT',
      BUFFER_APPENDING: 'BUFFER_APPENDING',
      BUFFER_EOS: 'BUFFER_EOS',
      VIDEO_ATTACHING: 'VIDEO_ATTACHING',
      VIDEO_ATTACHED: 'VIDEO_ATTACHED',
      MEDIA_ATTACHED: 'MEDIA_ATTACHED',
      ERROR: 'ERROR',
    };
    static ErrorTypes = {
      NETWORK_ERROR: 'networkError',
      MEDIA_ERROR: 'mediaError',
    };
    private handlers = new Map<string, ((...args: any[]) => void)[]>();
    destroyed = false;
    recoverMediaError = vi.fn();
    loadSource = vi.fn();
    attachMedia = vi.fn();
    destroy = vi.fn(() => { this.destroyed = true; });

    constructor(_config: unknown) {
      instances.push(this);
    }

    on(event: string, handler: (...args: any[]) => void) {
      const list = this.handlers.get(event) ?? [];
      list.push(handler);
      this.handlers.set(event, list);
    }

    emit(event: string, ...args: any[]) {
      for (const handler of this.handlers.get(event) ?? []) handler(...args);
    }
  }
  (MockHls as any).isSupported = () => true;
  return { MockHls, instances };
});

vi.mock('hls.js', () => ({ default: h.MockHls }));
vi.mock('@/api/client', () => ({ api: { get: vi.fn(), post: vi.fn() } }));
vi.mock('@/api/video', () => ({ videoApi: { getPlaybackToken: vi.fn(), refreshPlaybackToken: vi.fn() } }));
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ user: { employee_no: 'E-TEST' } }) }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '42' } }),
  useRouter: () => ({ push: vi.fn() }),
}));

import { api } from '@/api/client';
import { videoApi } from '@/api/video';
import VideoDetail from './VideoDetail.vue';

const apiGet = vi.mocked(api.get);
const getPlaybackToken = vi.mocked(videoApi.getPlaybackToken);
const refreshPlaybackToken = vi.mocked(videoApi.refreshPlaybackToken);

const originalToken = {
  m3u8_url: '/api/v1/playback/42/playlist.m3u8?token=old',
  token: 'old-token',
  session_id: 'session-42',
  key_url: '/api/v1/playback/42/key',
  expires_at: new Date(Date.now() + 30_000).toISOString(),
};
const refreshedToken = {
  ...originalToken,
  m3u8_url: '/api/v1/playback/42/playlist.m3u8?token=new',
  token: 'new-token',
  expires_at: new Date(Date.now() + 600_000).toISOString(),
};

async function mountReady() {
  apiGet.mockResolvedValue({ hls_status: 'ready', title: 'test video' } as any);
  getPlaybackToken.mockResolvedValue(originalToken);
  refreshPlaybackToken.mockResolvedValue(refreshedToken);
  const wrapper = mount(VideoDetail, {
    global: {
      stubs: {
        'el-page-header': true,
        'el-descriptions': true,
        'el-descriptions-item': true,
      },
    },
  });
  await vi.waitFor(() => expect(h.instances).toHaveLength(1));
  await nextTick();
  const el = wrapper.get('video').element as HTMLVideoElement;
  el.pause = vi.fn() as typeof el.pause;
  el.load = vi.fn() as typeof el.load;
  return { wrapper, el };
}

function setPlaybackState(el: HTMLVideoElement, time: number, paused: boolean) {
  Object.defineProperty(el, 'currentTime', { configurable: true, value: time, writable: true });
  Object.defineProperty(el, 'paused', { configurable: true, value: paused, writable: true });
  el.play = vi.fn(() => Promise.resolve()) as typeof el.play;
}

async function renew() {
  window.dispatchEvent(new Event('pageshow'));
  await vi.waitFor(() => expect(refreshPlaybackToken).toHaveBeenCalledWith(42, 'old-token'));
  await vi.waitFor(() => expect(h.instances).toHaveLength(2));
  await nextTick();
}

describe('VideoDetail HLS token renewal rebuild', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    h.instances.length = 0;
    apiGet.mockReset();
    getPlaybackToken.mockReset();
    refreshPlaybackToken.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('rebuilds a distinct Hls instance and ignores stale errors while accepting the new instance', async () => {
    const { wrapper, el } = await mountReady();
    setPlaybackState(el, 12, false);
    await renew();

    const original = h.instances[0];
    const rebuilt = h.instances[1];
    expect(rebuilt).not.toBe(original);
    expect(original.destroyed).toBe(true);

    original.emit(h.MockHls.Events.ERROR, h.MockHls.Events.ERROR, {
      type: h.MockHls.ErrorTypes.MEDIA_ERROR,
      fatal: true,
      details: 'stale-error',
    });
    expect(rebuilt.recoverMediaError).not.toHaveBeenCalled();

    rebuilt.emit(h.MockHls.Events.ERROR, h.MockHls.Events.ERROR, {
      type: h.MockHls.ErrorTypes.MEDIA_ERROR,
      fatal: true,
      details: 'current-error',
    });
    expect(rebuilt.recoverMediaError).toHaveBeenCalledTimes(1);
    await wrapper.unmount();
  });

  it.each([
    { label: 'playing', time: 37, paused: false },
    { label: 'paused', time: 91, paused: true },
  ])('restores currentTime and paused state when renewal rebuilds while $label', async ({ time, paused }) => {
    const { wrapper, el } = await mountReady();
    setPlaybackState(el, time, paused);
    await renew();

    const rebuilt = h.instances[1];
    rebuilt.emit(h.MockHls.Events.MEDIA_ATTACHED);
    el.dispatchEvent(new Event('loadedmetadata'));
    await nextTick();

    expect(el.currentTime).toBe(time);
    expect(el.paused).toBe(paused);
    if (!paused) expect(el.play).toHaveBeenCalledTimes(1);
    else expect(el.play).not.toHaveBeenCalled();
    await wrapper.unmount();
  });
});
