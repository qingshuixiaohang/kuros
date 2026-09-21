export type AvatarTone = "blue" | "lavender" | "dark" | "gold";

export interface Guide {
  id: string;
  /** 演示数据与后端种子数据的稳定关联，仅用于本地回退展示。 */
  apiId?: string;
  category: string;
  title: string;
  excerpt: string;
  content?: string;
  /** 后端详情接口返回的首图；未返回时回退到演示素材。 */
  coverImageUrl?: string | null;
  author: string;
  authorMark: string;
  avatarTone: AvatarTone;
  publishedAt: string;
  views: string;
  replies: number;
  likes: string;
  tags: string[];
  /** 首图列表由帖子正文、列表封面字段或演示内容数据提供。 */
  mediaUrls?: string[];
}

export interface NewsItem {
  id: string;
  rank: number;
  title: string;
  hot?: boolean;
  category: "版本前瞻" | "官方公告" | "活动资讯" | "社区资讯";
  date: string;
  summary: string;
  content: string[];
}

export interface ToolItem {
  slug: "calculator" | "echo" | "team-builder";
  title: string;
  description: string;
  icon: "calculator" | "echo" | "team";
  iconSrc: string;
}

export type CharacterElement = "湮灭" | "导电" | "热熔" | "冷凝" | "气动" | "衍射";
export type CharacterWeapon = "迅刀" | "长刃" | "佩枪" | "臂铠" | "音感仪";

export interface CharacterSkill {
  name: string;
  type: "普通攻击" | "共鸣技能" | "共鸣解放" | "变奏技能" | "延奏技能" | "共鸣回路";
  description: string;
}

export interface CharacterAttributes {
  hp: number;
  atk: number;
  def: number;
  critRate: string;
  critDmg: string;
}

export interface Character {
  id: string;
  name: string;
  role: "输出" | "协同" | "辅助";
  title: string;
  image: string;
  description: string;
  guideId: string;
  element: CharacterElement;
  weapon: CharacterWeapon;
  rarity: 4 | 5;
  version: string;
  attributes: CharacterAttributes;
  skills: CharacterSkill[];
}

export interface EchoSet {
  id: string;
  name: string;
  effect: string;
  location: string;
  role: "输出" | "协同" | "治疗";
  description: string;
  guideId: string;
}
