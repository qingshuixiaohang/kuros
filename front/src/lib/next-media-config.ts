export type MediaRemotePattern = {
  protocol: "http" | "https";
  hostname: string;
  port: string;
  pathname: string;
  search: "";
};

export function createMediaRemotePatterns(mediaBaseUrl: string, includeLocalDevelopment = false): MediaRemotePattern[] {
  const mediaBase = new URL(mediaBaseUrl);
  const protocol = mediaBase.protocol.slice(0, -1);
  if ((protocol !== "http" && protocol !== "https") || mediaBase.username || mediaBase.password || mediaBase.search || mediaBase.hash) {
    throw new Error("media base URL must be HTTP(S) without credentials, query or fragment");
  }

  const configured: MediaRemotePattern = {
    protocol,
    hostname: mediaBase.hostname,
    port: mediaBase.port,
    pathname: mediaBase.pathname.replace(/\/$/, "") + "/media/**",
    search: "",
  };
  if (!includeLocalDevelopment) return [configured];

  return [
    configured,
    { protocol: "http", hostname: "localhost", port: "8080", pathname: "/media/**", search: "" },
    { protocol: "http", hostname: "127.0.0.1", port: "8080", pathname: "/media/**", search: "" },
  ];
}
