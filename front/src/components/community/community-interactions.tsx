"use client";

import Image from "next/image";
import { Check, X } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";

import { useOverlay } from "@/hooks/use-overlay";
import { fetchCurrentUser, loginWithPhone, logoutFromApi, requestVerificationCode, type AuthUser } from "@/lib/api";

type DemoState = { loggedIn: boolean; user: AuthUser | null; followed: string[]; liked: string[]; bookmarked: string[] };
type CommunityContextValue = DemoState & { sessionRestoring: boolean; requestLogin: (afterLogin?: () => void) => void; logout: () => void; toggleFollow: (id: string) => void; toggleLike: (id: string) => void; toggleBookmark: (id: string) => void; notify: (message: string) => void };
const initialState: DemoState = { loggedIn: false, user: null, followed: [], liked: [], bookmarked: [] };
const storageKey = "wuthering-demo-community-v1";
const CommunityContext = createContext<CommunityContextValue | null>(null);
function toggle(items: string[], id: string) { return items.includes(id) ? items.filter((item) => item !== id) : [...items, id]; }

export function CommunityDemoProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<DemoState>(initialState); const [hydrated, setHydrated] = useState(false); const [sessionRestoring, setSessionRestoring] = useState(true); const [loginOpen, setLoginOpen] = useState(false); const [pendingAction, setPendingAction] = useState<(() => void) | null>(null); const [toast, setToast] = useState("");
  const [phone, setPhone] = useState(""); const [verification, setVerification] = useState(""); const [agreement, setAgreement] = useState(false); const [codeSent, setCodeSent] = useState(false); const [formError, setFormError] = useState(""); const [sendingCode, setSendingCode] = useState(false); const [loggingIn, setLoggingIn] = useState(false);
  const loginCloseRef = useRef<HTMLButtonElement>(null);
  const closeLogin = useCallback(() => { if (!loggingIn && !sendingCode) { setLoginOpen(false); setFormError(""); } }, [loggingIn, sendingCode]);

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      try { const saved = window.localStorage.getItem(storageKey); if (saved) { const parsed = JSON.parse(saved) as Partial<DemoState>; setState((current) => ({ ...current, followed: parsed.followed ?? [], liked: parsed.liked ?? [], bookmarked: parsed.bookmarked ?? [] })); } } catch { /* Keep the community usable when storage is unavailable. */ }
      setHydrated(true);
      void (async () => {
        // 恢复登录态：401 = 确实未登录，安静返回；其余失败（user 服务重启、网关抖动等
        // 瞬时故障）有限重试——否则顶栏会整个会话停留在"登录"按钮，而页面内容经 Cookie
        // 仍能拿到本人数据，造成"页面显示已登录、顶栏显示登录按钮"的状态分裂。
        // sessionRestoring 让"登录态未定"成为可观察状态：依赖登录的页面（如编辑跳转）
        // 在恢复完成前先渲染加载态，而不是闪现"请先登录"
        setSessionRestoring(true);
        try {
          for (let attempt = 1; active && attempt <= 3; attempt++) {
            try { const user = await fetchCurrentUser(); if (active && user) setState((current) => ({ ...current, loggedIn: true, user })); return; }
            catch { if (attempt === 3 || !active) return; await new Promise((resolve) => setTimeout(resolve, 1500)); }
          }
        } finally { if (active) setSessionRestoring(false); }
      })();
    }, 0);
    return () => { active = false; window.clearTimeout(timer); };
  }, []);
  useEffect(() => { if (hydrated) window.localStorage.setItem(storageKey, JSON.stringify({ followed: state.followed, liked: state.liked, bookmarked: state.bookmarked })); }, [hydrated, state.followed, state.liked, state.bookmarked]);
  useEffect(() => { if (!toast) return; const timer = window.setTimeout(() => setToast(""), 2600); return () => window.clearTimeout(timer); }, [toast]);
  // 登录浮层的键盘与焦点行为统一交给 useOverlay（Esc 关闭带提交守卫、Tab 陷阱、
  // 初始聚焦关闭按钮、关闭后焦点归还）；遮罩不绑定点击关闭，防误触丢表单
  useOverlay({ open: loginOpen, onClose: closeLogin, surfaceSelector: ".login-dialog", initialFocusRef: loginCloseRef, blocked: loggingIn || sendingCode });

  const notify = useCallback((message: string) => setToast(message), []);
  const requireLogin = useCallback((action?: () => void) => { if (state.loggedIn) { action?.(); return; } setPendingAction(() => action ?? null); setFormError(""); setLoginOpen(true); }, [state.loggedIn]);
  const handleLogout = useCallback(async () => { try { await logoutFromApi(); setState(() => ({ ...initialState })); notify("已退出鸣潮社区"); } catch { notify("退出失败，请稍后重试"); } }, [notify]);
  const value = useMemo<CommunityContextValue>(() => ({ ...state, sessionRestoring, requestLogin: requireLogin, logout: () => { void handleLogout(); }, toggleFollow: (id) => requireLogin(() => setState((current) => ({ ...current, followed: toggle(current.followed, id) }))), toggleLike: (id) => requireLogin(() => setState((current) => ({ ...current, liked: toggle(current.liked, id) }))), toggleBookmark: (id) => requireLogin(() => setState((current) => ({ ...current, bookmarked: toggle(current.bookmarked, id) }))), notify }), [handleLogout, notify, requireLogin, sessionRestoring, state]);

  async function sendCode() { if (!/^1\d{10}$/.test(phone)) { setFormError("请输入正确的 11 位手机号"); return; } setSendingCode(true); setFormError(""); try { const result = await requestVerificationCode(phone); setCodeSent(true); if (result.devCode) setVerification(result.devCode); notify(result.devCode ? "开发验证码已填入：" + result.devCode : "验证码已发送至 " + phone.slice(0, 3) + "****" + phone.slice(-4)); } catch (error) { setFormError(error instanceof Error ? error.message : "验证码发送失败，请稍后重试"); } finally { setSendingCode(false); } }
  async function login(event: FormEvent<HTMLFormElement>) { event.preventDefault(); if (!/^1\d{10}$/.test(phone)) { setFormError("请输入正确的 11 位手机号"); return; } if (!/^\d{6}$/.test(verification)) { setFormError("请输入 6 位验证码"); return; } if (!agreement) { setFormError("请先阅读并同意用户协议与隐私政策"); return; } setLoggingIn(true); setFormError(""); try { const user = await loginWithPhone(phone, verification); setState((current) => ({ ...current, loggedIn: true, user })); setLoginOpen(false); notify("已登录鸣潮社区"); pendingAction?.(); setPendingAction(null); } catch (error) { setFormError(error instanceof Error ? error.message : "登录失败，请稍后重试"); } finally { setLoggingIn(false); } }

  return <CommunityContext.Provider value={value}>{children}{toast && <div className="community-toast" role="status"><Check size={15} />{toast}</div>}{loginOpen && <div className="dialog-backdrop login-backdrop" role="presentation"><section className="login-dialog login-dialog--phone" aria-modal="true" aria-label="登录鸣潮社区" role="dialog"><button className="dialog-close" onClick={closeLogin} ref={loginCloseRef} type="button" aria-label="关闭登录"><X size={28} strokeWidth={2.1} /></button><div className="login-hero"><div className="login-title"><span>欢迎登录</span><strong>鸣潮社区</strong><small>WUTHERING WAVES COMMUNITY</small></div><Image alt="库街区吉祥物" className="login-mascot" height={76} priority src="/brand/kurobbs-icon.png" width={76} /></div><form className="phone-login-form" onSubmit={login}><label className="phone-field"><span>+86</span><input autoComplete="tel" inputMode="numeric" maxLength={11} onChange={(event) => setPhone(event.target.value.replace(/\D/g, ""))} placeholder="请输入手机号码" type="tel" value={phone} /></label><label className="code-field"><input inputMode="numeric" maxLength={6} onChange={(event) => setVerification(event.target.value.replace(/\D/g, ""))} placeholder="请输入 6 位验证码" value={verification} /><button disabled={sendingCode} onClick={sendCode} type="button">{sendingCode ? "发送中" : codeSent ? "重新获取" : "获取验证码"}</button></label>{formError && <p className="login-form-error" role="alert">{formError}</p>}<button className="phone-login-submit" disabled={loggingIn} type="submit">{loggingIn ? "登录中…" : "登录"}</button><label className="agreement-field"><input checked={agreement} onChange={(event) => setAgreement(event.target.checked)} type="checkbox" /><span>我已阅读并同意 <a href="#agreement">《用户协议》</a>、<a href="#privacy">《隐私政策》</a>，未注册手机号验证成功后将自动注册。</span></label></form></section></div>}</CommunityContext.Provider>;
}
export function useCommunityDemo() { const context = useContext(CommunityContext); if (!context) throw new Error("useCommunityDemo must be used inside CommunityDemoProvider"); return context; }
