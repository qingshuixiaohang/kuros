export type AvatarTone = "blue" | "lavender" | "dark" | "gold";

export interface Guide {
  id: string;
  category: string;
  title: string;
  excerpt: string;
  author: string;
  authorMark: string;
  avatarTone: AvatarTone;
  publishedAt: string;
  views: string;
  replies: number;
  likes: string;
  tags: string[];
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
