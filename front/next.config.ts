import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  serverExternalPackages: [],
  images: {
    // next/image 默认拒绝加载任意外域图片（防滥用），媒体走网关 8080 绝对地址
    // （后端 app.storage.public-base-url），必须在白名单声明；本机开发还可能经
    // 127.0.0.1 访问，一并放行
    remotePatterns: [
      { protocol: "http", hostname: "localhost", port: "8080", pathname: "/media/**" },
      { protocol: "http", hostname: "127.0.0.1", port: "8080", pathname: "/media/**" },
    ],
  },
};

export default nextConfig;
