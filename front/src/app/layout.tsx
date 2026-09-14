import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { CommunityProvider } from "@/components/community/community-provider";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "鸣潮 · 漂泊者社区",
  description: "鸣潮玩家的攻略、资讯与创作交流社区",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN" className={`${geistSans.variable} ${geistMono.variable}`}><body><CommunityProvider>{children}</CommunityProvider></body></html>;
}
