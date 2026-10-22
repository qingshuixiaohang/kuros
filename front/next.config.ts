import type { NextConfig } from "next";
import { createMediaRemotePatterns } from "./src/lib/next-media-config";

const configuredMediaBase = process.env.NEXT_SERVER_MEDIA_BASE_URL;
const mediaBase = configuredMediaBase ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  output: "standalone",
  experimental: { cpus: 2 },
  serverExternalPackages: [],
  images: {
    // next/image 默认拒绝加载任意外域图片（防滥用），媒体走网关 8080 绝对地址
    // （后端 app.storage.public-base-url），必须在白名单声明；本机开发还可能经
    // 127.0.0.1 访问，一并放行
    remotePatterns: createMediaRemotePatterns(mediaBase, !configuredMediaBase),
  },
};

export default nextConfig;
