export type AvatarTone = "blue" | "lavender" | "dark" | "gold";

export interface Guide {
  id: string;
  /** 演示数据与后端种子数据的稳定关联，仅用于本地回退展示。 */
  apiId?: string;
  category: string;
  title: string;
  excerpt: string;
  content?: string;
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
}

export interface Character {
  id: string;
  name: string;
  role: "输出" | "协同" | "辅助";
  title: string;
  image: string;
  description: string;
  guideId: string;
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
