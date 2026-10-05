import type { Metadata } from "next";
import { Geist, Geist_Mono, Noto_Serif_SC } from "next/font/google";
import { CommunityProvider } from "@/components/community/community-provider";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });
// 鸣潮字标展示字体：Noto Serif SC Black（免费衬线，近似官方 logo 的笔触质感）
const logoDisplay = Noto_Serif_SC({ variable: "--font-logo-display", weight: "900", display: "swap", preload: false });

export const metadata: Metadata = {
  title: "鸣潮 · 漂泊者社区",
  description: "鸣潮玩家的攻略、资讯与创作交流社区",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // suppressHydrationWarning：浏览器扩展（深色模式/主题类）会在 React 水合前
  // 给 <html> 注入 data-theme/color-scheme 等属性，触发属性级 hydration 警告。
  // 该标记只豁免本标签的属性比对，不影响子元素的真实水合校验。
  return <html lang="zh-CN" suppressHydrationWarning className={`${geistSans.variable} ${geistMono.variable} ${logoDisplay.variable}`}><body><CommunityProvider>{children}</CommunityProvider></body></html>;
}
