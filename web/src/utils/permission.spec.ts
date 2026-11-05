/**
 * 权限指令 v-permission 单测
 *
 * <p>这次改动的核心：普通员工（employee 角色）收敛为只读 ——
 * 上传 / 删除按钮必须**看不见**，而不是"看得见、点了报 403"。
 *
 * <p>另外盯住一个容易被改坏的点：指令原来是「把元素从 DOM 里删掉」，
 * 那样会和 Vue 的虚拟 DOM 打架（vnode 还指着那个节点，后续 patch
 * 抛 removeChild 异常）。现在改成「隐藏」（display:none），
 * 这个用例把"元素仍在 DOM 里"钉死。
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useUserStore } from '@/stores/user';
import { permission } from './permission';
import type { CurrentUser } from '@/api/auth';

function loginAs(permissions: string[], roles: string[] = ['employee']): void {
  const store = useUserStore();
  store.user = {
    user_id: 1,
    employee_no: 'E000002',
    real_name: '测试员工',
    dept_id: 1,
    status: 1,
    roles,
    permissions,
  } as CurrentUser;
}

/** 挂一次指令，返回被作用的元素 */
function apply(value: string | string[]): HTMLElement {
  const el = document.createElement('button');
  document.body.appendChild(el);
  // mounted(el, binding, vnode, prevVNode) —— 后两个参数本指令用不到
  permission.mounted?.(el, { value } as any, undefined as any, undefined as any);
  return el;
}

describe('v-permission', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    document.body.innerHTML = '';
  });

  it('没有权限 → 隐藏，但元素仍在 DOM 里', () => {
    loginAs(['video:read', 'document:read']);
    const el = apply('video:upload');
    expect(el.style.display).toBe('none');
    expect(el.getAttribute('data-permission-denied')).toBe('true');
    // 关键：不能把节点从 DOM 里摘掉 —— Vue 的 vnode 还指着它
    expect(el.parentNode).not.toBeNull();
  });

  it('有权限 → 正常显示', () => {
    loginAs(['video:read', 'video:upload']);
    const el = apply('video:upload');
    expect(el.style.display).toBe('');
    expect(el.getAttribute('data-permission-denied')).toBe('false');
  });

  it('数组语义 = 任一命中即可', () => {
    loginAs(['video:delete']);
    expect(apply(['video:share', 'video:delete']).style.display).toBe('');
    expect(apply(['video:share', 'video:edit']).style.display).toBe('none');
  });

  it('支持通配权限码（*:* 与 *:action）', () => {
    loginAs(['*:*']);
    expect(apply('video:delete').style.display).toBe('');

    loginAs(['*:delete']);
    expect(apply('video:delete').style.display).toBe('');
    expect(apply('video:upload').style.display).toBe('none');
  });

  it('普通员工（只读白名单）看不到上传与删除按钮', () => {
    // 与后端 EmployeePermissions.READ_ONLY 对齐
    loginAs([
      'dashboard:read', 'video:read', 'document:read', 'watermark:read',
      'notification:read', 'tag:read', 'search:read', 'playback:read',
      'preview:read', 'classification:read',
    ]);
    expect(apply('video:upload').style.display).toBe('none');
    expect(apply('video:delete').style.display).toBe('none');
    expect(apply('document:upload').style.display).toBe('none');
    expect(apply('document:delete').style.display).toBe('none');
    // 播放 / 预览照常
    expect(apply('video:read').style.display).toBe('');
    expect(apply('document:read').style.display).toBe('');
  });
});
