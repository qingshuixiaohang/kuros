import { describe, expect, it } from "vitest";
import { createMediaRemotePatterns } from "./next-media-config";

describe("Next 媒体域名白名单", () => {
  it("生产环境只允许显式配置的媒体主机和路径", () => {
    expect(createMediaRemotePatterns("https://media.example.com/assets")).toEqual([
      {
        protocol: "https",
        hostname: "media.example.com",
        port: "",
        pathname: "/assets/media/**",
        search: "",
      },
    ]);
  });

  it("配置主机之外的图片域名不会被放行", () => {
    const patterns = createMediaRemotePatterns("https://media.example.com");
    expect(patterns.some((pattern) => pattern.hostname === "evil.example")).toBe(false);
  });

  it("拒绝带凭证、查询参数或锚点的媒体基地址", () => {
    expect(() => createMediaRemotePatterns("https://user:secret@media.example.com")).toThrow();
    expect(() => createMediaRemotePatterns("https://media.example.com?next=evil.example")).toThrow();
    expect(() => createMediaRemotePatterns("https://media.example.com#fragment")).toThrow();
  });
});
