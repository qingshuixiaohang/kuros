export type Artwork = { name: string; src: string; href?: string };

export const characterShowcase: Artwork[] = [
  { name: "锁暝", src: "/art/official/characters/lockming-showcase-01.png", href: "/search?q=锁暝" },
  { name: "心", src: "/art/official/characters/xin-showcase-01.jpg", href: "/search?q=心" },
  { name: "景燃", src: "/art/official/characters/jingran-showcase-01.png", href: "/search?q=景燃" },
  { name: "清宵", src: "/art/official/characters/qingxiao-showcase-01.jpg", href: "/search?q=清宵" },
  { name: "穗穗", src: "/art/official/characters/suisui-showcase-01.png", href: "/search?q=穗穗" },
  { name: "秧秧·玄翎", src: "/art/official/characters/yangyang-xuanling-showcase-01.png", href: "/search?q=秧秧" },
  { name: "洛瑟菈", src: "/art/official/characters/lucilla-showcase-01.png", href: "/search?q=洛瑟菈" },
  { name: "露西", src: "/art/official/characters/lucy-showcase-01.jpg", href: "/search?q=露西" },
  { name: "绯雪", src: "/art/official/characters/hiyuki-showcase-01.jpg", href: "/search?q=绯雪" },
  { name: "西格莉卡", src: "/art/official/characters/sigilica-showcase-01.jpg", href: "/search?q=西格莉卡" },
];

export const echoShowcase: Artwork[] = [
  { name: "天演溯心", src: "/art/official/echoes/tianyan-soul.jpg" },
  { name: "玄方地界声骸", src: "/art/official/echoes/dreamzhou-echoes.png" },
  { name: "天傀劫煞", src: "/art/official/echoes/heavenly-tribulation.jpg" },
  { name: "万囮牢·朽躯", src: "/art/official/echoes/myriad-cage-decayed.jpg" },
  { name: "千傀重楼", src: "/art/official/echoes/thousand-puppet-tower.jpg" },
  { name: "罗伊冰原声骸", src: "/art/official/echoes/roy-icefield-echoes.jpg" },
  { name: "海维夏", src: "/art/official/echoes/hivevisia.jpg" },
  { name: "拉海洛声骸", src: "/art/official/echoes/lahairoi-echoes.jpg" },
  { name: "伪作的神王", src: "/art/official/echoes/false-god-king.jpg" },
  { name: "梦魇·凯尔匹", src: "/art/official/echoes/nightmare-kelpie.jpg" },
];

const echoSetArtworkBySlug: Record<string, string> = {
  "floating-stars": "/art/official/echoes/false-god-king.jpg",
  thunder: "/art/official/echoes/tianyan-soul.jpg",
  "light-cloud": "/art/official/echoes/dreamzhou-echoes.png",
  "hidden-light": "/art/official/echoes/hivevisia.jpg",
};

const characterArtByName: Record<string, string> = {
  守岸人: "/art/修-守岸人 唤取动画.webp",
  奥古斯都: "/art/修-奥古斯都  唤取动画.webp",
  爱弥斯: "/art/修-爱弥斯 唤取动画.webp",
  长离: "/art/official/characters/changli-profile.jpg",
  椿: "/art/official/characters/camellya-profile.jpg",
  锁暝: "/art/official/characters/lockming-showcase-01.png",
  心: "/art/official/characters/xin-showcase-01.jpg",
  景燃: "/art/official/characters/jingran-showcase-01.png",
  清宵: "/art/official/characters/qingxiao-showcase-01.jpg",
  穗穗: "/art/official/characters/suisui-showcase-01.png",
  "秧秧·玄翎": "/art/official/characters/yangyang-xuanling-showcase-01.png",
  洛瑟菈: "/art/official/characters/lucilla-showcase-01.png",
  露西: "/art/official/characters/lucy-showcase-01.jpg",
  绯雪: "/art/official/characters/hiyuki-showcase-01.jpg",
  西格莉卡: "/art/official/characters/sigilica-showcase-01.jpg",
};

const avatarByIdentity: Record<string, string> = {
  "潮声档案员": "/art/official/characters/changli-profile.jpg",
  "无音区夜行者": "/art/official/characters/yangyang-xuanling-profile.png",
  "今汐的留声机": "/art/official/characters/lockming-profile.jpg",
  "漂泊者手册": "/art/official/characters/xin-showcase-01.jpg",
  "漂泊者0011": "/art/official/characters/jingran-showcase-01.png",
  "漂泊者0012": "/art/official/characters/qingxiao-showcase-01.jpg",
  "漂泊者0013": "/art/official/characters/suisui-showcase-01.png",
  "漂泊者0014": "/art/official/characters/yangyang-xuanling-showcase-01.png",
  "漂泊者0015": "/art/official/characters/lucilla-showcase-01.png",
  "漂泊者0016": "/art/official/characters/lucy-showcase-01.jpg",
  "漂泊者0017": "/art/official/characters/sigilica-showcase-01.jpg",
  踏潮记录者: "/art/official/characters/jingran-showcase-01.png",
  回响备忘录: "/art/official/characters/qingxiao-showcase-01.jpg",
  星炬观测站: "/art/official/characters/suisui-showcase-01.png",
  远岸来信: "/art/official/characters/yangyang-xuanling-showcase-01.png",
  今州夜行: "/art/official/characters/lucilla-showcase-01.png",
  瑝珑拾光: "/art/official/characters/lucy-showcase-01.jpg",
  云外拾音: "/art/official/characters/sigilica-showcase-01.jpg",
};

const defaultAvatar = "/art/official/characters/lockming-profile.jpg";

export function characterArtwork(name: string, fallback: string) {
  return characterArtByName[name] ?? characterShowcase.find((art) => art.name === name)?.src ?? fallback;
}

export function echoSetArtwork(slug: string) {
  return echoSetArtworkBySlug[slug] ?? echoShowcase[0].src;
}

export function communityAvatar(avatarUrl: string | null | undefined, identity: string) {
  return avatarUrl?.startsWith("/") ? avatarUrl : avatarByIdentity[identity] ?? defaultAvatar;
}

export function newsArtwork(slug: string, title: string) {
  if (slug === "version-preview" || title.includes("2.4")) return "/art/official/version-2.4/update-topic.jpg";
  if (slug === "maintenance-notice" || title.includes("维护")) return "/art/official/version-3.7/update-key-art.jpg";
  return "/art/official/version-3.7/update-key-art.jpg";
}

export function topicArtwork(tag: string) {
  if (tag.includes("声骸") || tag.includes("强敌")) return echoShowcase.find((art) => art.name.includes("天演"))?.src ?? echoShowcase[0].src;
  if (tag.includes("角色") || tag.includes("配队")) return characterShowcase.find((art) => art.name === "锁暝")?.src ?? characterShowcase[0].src;
  if (tag.includes("武器") || tag.includes("唤取")) return "/art/official/weapons/ver37-phase2-convene.jpg";
  if (tag.includes("探索") || tag.includes("地图")) return "/art/official/version-3.7/dreamshu-area-preview-1.jpg";
  if (tag.includes("版本") || tag.includes("活动")) return "/art/official/version-3.7/update-key-art.jpg";
  return "/art/official/version-3.7/update-key-art.jpg";
}

export function toolArtwork(slug: "calculator" | "echo" | "team-builder") {
  if (slug === "echo") return echoShowcase[0].src;
  if (slug === "team-builder") return characterShowcase[0].src;
  return "/art/official/version-3.7/update-key-art.jpg";
}
