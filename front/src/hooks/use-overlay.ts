"use client";

import { useEffect, type RefObject } from "react";

/**
 * 浮层（对话框/抽屉）的统一行为：Esc 关闭、Tab 焦点陷阱、打开时初始聚焦、
 * 关闭后焦点归还触发元素、可选"提交中禁止关闭"守卫。
 *
 * 为什么需要一个共享钩子：全站浮层行为此前有三份近似实现（登录框、抽屉、
 * 举报框各写各的），举报框缺 Esc/焦点陷阱/提交守卫——浮层行为不一致就是
 * 这样来的。遮罩点击是否关闭由调用方在 JSX 里决定（登录框：否，避免误触
 * 丢输入；抽屉：是），本钩子只负责键盘与焦点行为。
 */
export function useOverlay(options: {
  /** 浮层是否打开；组件常挂载时传状态，条件渲染的弹窗传 true */
  open: boolean;
  onClose: () => void;
  /** 焦点陷阱与 Tab 循环的作用范围选择器（浮层根元素，如 ".login-dialog"） */
  surfaceSelector: string;
  /** 打开时初始聚焦的元素（通常是关闭按钮） */
  initialFocusRef?: RefObject<HTMLElement | null>;
  /** true 期间 Esc 不可关闭（提交中/发送中，防止丢表单状态） */
  blocked?: boolean;
}) {
  const { open, onClose, surfaceSelector, initialFocusRef, blocked = false } = options;

  // 焦点进/出：仅在 open 变化（或挂载/卸载）时执行一次，
  // 与键盘订阅分开成两个 effect，避免 blocked 翻转时把焦点错误地归还
  useEffect(() => {
    if (!open) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const frame = window.requestAnimationFrame(() => initialFocusRef?.current?.focus());
    return () => {
      window.cancelAnimationFrame(frame);
      if (previousFocus) window.requestAnimationFrame(() => previousFocus.focus());
    };
    // initialFocusRef 是稳定的 ref 对象，不需要进依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open) return;
    function getFocusableElements() {
      return Array.from(document.querySelectorAll<HTMLElement>(
        `${surfaceSelector} button:not([disabled]), ${surfaceSelector} input:not([disabled]), ${surfaceSelector} a[href], ${surfaceSelector} select, ${surfaceSelector} textarea`
      ));
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") { event.preventDefault(); if (!blocked) onClose(); return; }
      if (event.key !== "Tab") return;
      const focusable = getFocusableElements();
      const first = focusable[0]; const last = focusable.at(-1);
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [blocked, onClose, open, surfaceSelector]);
}
