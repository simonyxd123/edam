/**
 * 权限指令 v3.2 V-1 RBAC
 *
 * 用法：
 *   <el-button v-permission="'video:upload'">上传视频</el-button>
 *   <el-button v-permission="'video:delete'">删除</el-button>
 *   <el-button v-permission="['video:share', 'video:edit']">编辑</el-button>  <!-- 任一即可 -->
 *
 * 没权限 → 元素被**隐藏**（display:none，不占布局）
 *
 * <p><b>为什么是"隐藏"而不是"从 DOM 里删掉"</b>：
 * 删元素会和 Vue 的虚拟 DOM 打架 —— vnode 还指着那个节点，
 * 后续 patch / unmount 时 Vue 会去操作一个已经不在 DOM 里的节点，
 * 于是列表刷新时冒出
 * "Failed to execute 'removeChild' on 'Node'"。
 * 隐藏没有这个问题：DOM 结构不变，Vue 照常 patch。
 *
 * <p>另外，隐藏还能在元素上留下 {@code data-permission-denied="true"}
 * 标记，方便排障时在浏览器里一眼看出"这个按钮是被权限挡掉的"。
 *
 * <p>注意：<b>隐藏不等于拦截</b>。前端只是不给看，真正的拦截在后端
 * {@code @PreAuthorize} —— 前端绕过（改 DOM / 直接调接口）一律 403。
 */
import type { ObjectDirective } from 'vue';
import { useUserStore } from '@/stores/user';

/** 判定并应用可见性；抽成函数是为了 mounted / updated 共用一套逻辑 */
function applyVisibility(el: HTMLElement, value: string | string[] | undefined) {
  if (!value) return;
  const userStore = useUserStore();
  const codes = Array.isArray(value) ? value : [value];
  // 数组语义 = **任一命中即可**（与 hasPermission 的通配规则保持一致）
  const allowed = codes.some((code) => !!code && userStore.hasPermission(code));
  el.style.display = allowed ? '' : 'none';
  el.setAttribute('data-permission-denied', allowed ? 'false' : 'true');
}

export const permission: ObjectDirective<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    applyVisibility(el, binding.value);
  },
  updated(el, binding) {
    // 角色变化后重检查（登出 / 切换账号）
    applyVisibility(el, binding.value);
  },
};
