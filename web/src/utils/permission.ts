/**
 * 权限指令 v3.2 V-1 RBAC
 *
 * 用法：
 *   <el-button v-permission="'video:upload'">上传视频</el-button>
 *   <el-button v-permission="'*:*'">超级按钮</el-button>
 *
 * 没权限 → 元素被移除（不影响布局）
 */
import type { Directive } from 'vue';
import { useUserStore } from '@/stores/user';

export const permission: Directive<HTMLElement, string> = {
  mounted(el, binding) {
    const userStore = useUserStore();
    const code = binding.value;
    if (!code) return;
    if (!userStore.hasPermission(code)) {
      el.parentNode?.removeChild(el);
    }
  },
  updated(el, binding) {
    // 角色变化后重检查
    const userStore = useUserStore();
    const code = binding.value;
    if (!code) return;
    if (!userStore.hasPermission(code)) {
      el.parentNode?.removeChild(el);
    }
  },
};
