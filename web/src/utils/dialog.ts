/**
 * 弹窗交互的公共判定。
 *
 * <p><b>为什么需要</b>：Element Plus 的 `ElMessageBox.confirm` 在用户
 * 点右上角 × 时 reject 的是 <b>'close'</b>，只有点「取消」才是 'cancel'。
 * 项目里有 7 处写成 `if (e !== 'cancel')` —— 点右上角 × 会走进错误分支，
 * 弹出一条 "删除失败：undefined" 之类的假错误。
 */

/** 用户主动取消（点「取消」按钮） */
export const DIALOG_CANCEL = 'cancel';
/** 用户点了右上角 × 关闭 */
export const DIALOG_CLOSE = 'close';

/**
 * 是否是"用户主动取消/关闭"（而不是真正的业务错误）。
 *
 * @param e catch 到的异常
 */
export function isDialogDismissed(e: unknown): boolean {
  return e === DIALOG_CANCEL || e === DIALOG_CLOSE
    // Element Plus 也会 reject 一个带 action 字段的 Error 对象
    || (typeof e === 'object' && e !== null
      && 'action' in (e as Record<string, unknown>)
      && ((e as Record<string, unknown>).action === DIALOG_CANCEL
        || (e as Record<string, unknown>).action === DIALOG_CLOSE));
}
