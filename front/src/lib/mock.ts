import type { Character, EchoSet, Guide, NewsItem, ToolItem } from "@/types/community";

export const guides: Guide[] = [
  {
    id: "changli-team",
    category: "配队攻略",
    title: "长离焚火队：从零到毕业的配队思路",
    excerpt: "围绕共鸣效率、轮切节奏与副 C 选择，整理一套能直接照着练的实战框架。",
    author: "潮声档案员",
    authorMark: "潮",
    avatarTone: "dark",
    publishedAt: "今天 10:24",
    views: "1.8w",
    replies: 46,
    likes: "3.7k",
    tags: ["长离", "配队", "实战思路", "2.4"],
  },
  {
    id: "tower-24",
    category: "深塔攻略",
    title: "2.4 深塔速通：低配阵容与手法拆解",
    excerpt: "按房间记录怪物机制、起手顺序和容错点，附上可替换角色的思路。",
    author: "无音区夜行者",
    authorMark: "夜",
    avatarTone: "lavender",
    publishedAt: "昨天 21:08",
    views: "3.2w",
    replies: 89,
    likes: "5.1k",
    tags: ["深塔", "低配", "手法"],
  },
  {
    id: "camellya-echo",
    category: "角色培养",
    title: "椿的声骸选择与词条优先级",
    excerpt: "从套装、主词条到副词条阈值，说明不同武器与配队下的取舍。",
    author: "今汐的留声机",
    authorMark: "今",
    avatarTone: "blue",
    publishedAt: "昨天 16:42",
    views: "2.5w",
    replies: 63,
    likes: "3.2k",
    tags: ["椿", "声骸", "培养建议"],
  },
  {
    id: "new-player-route",
    category: "新手攻略",
    title: "新手开荒指南：从入坑到稳步成长",
    excerpt: "主线推进、体力规划、角色资源和探索优先级，一次理清前两周要做什么。",
    author: "漂泊者手册",
    authorMark: "漂",
    avatarTone: "gold",
    publishedAt: "周三 19:30",
    views: "5.1w",
    replies: 112,
    likes: "8.6k",
    tags: ["新手", "开荒", "资源规划"],
  },
];

export const newsItems: NewsItem[] = [
  { id: "version-preview", rank: 1, title: "鸣潮 2.4 版本前瞻特别节目总结", hot: true, category: "版本前瞻", date: "2025 / 09 / 13", summary: "新区域、角色体验和版本活动的重点信息已经整理完毕。", content: ["本次前瞻围绕新版本的探索节奏、限时活动和角色试玩展开。", "建议先完成版本主线，再根据自身资源安排角色与武器的养成优先级。"] },
  { id: "character-analysis", rank: 2, title: "新角色技能与定位分析", hot: true, category: "版本前瞻", date: "2025 / 09 / 12", summary: "从技能循环与队伍位置快速理解新角色的使用方向。", content: ["新角色更适合在稳定轮切队中发挥优势，机制重点在于技能衔接。", "具体配队仍需结合实装后的数值与实战环境进行确认。"] },
  { id: "maintenance", rank: 3, title: "2.4 版本更新时间公告", hot: true, category: "官方公告", date: "2025 / 09 / 11", summary: "版本维护时间、补偿说明和更新方式一览。", content: ["维护期间无法登录游戏，请提前完成未结算的挑战内容。", "更新完成后可通过邮件领取对应补偿。"] },
  { id: "official-note", rank: 4, title: "官方：关于近期问题的说明", category: "官方公告", date: "2025 / 09 / 10", summary: "针对已收集的问题给出处理进度与后续安排。", content: ["开发团队正在持续确认反馈情况，并会在后续公告同步处理进度。"] },
  { id: "creator-event", rank: 5, title: "玩家创作征集活动开启", category: "活动资讯", date: "2025 / 09 / 09", summary: "攻略、绘画与视频创作者均可参与本期主题征集。", content: ["请在投稿时使用指定标签，并确保作品为原创内容。"] },
  { id: "next-week", rank: 6, title: "鸣潮线下展会参展情报", category: "活动资讯", date: "2025 / 09 / 08", summary: "展会时间、现场内容与预约信息已经开放。", content: ["具体参与方式请以主办方后续说明为准。"] },
  { id: "version-review", rank: 7, title: "2.3 版本内容回顾", category: "社区资讯", date: "2025 / 09 / 07", summary: "社区整理了上一版本的重点内容与玩家讨论。", content: ["本篇回顾聚焦版本活动、常见攻略方向与实战体验。"] },
  { id: "community-rules", rank: 8, title: "社区版规与友好交流倡议", category: "社区资讯", date: "2025 / 09 / 06", summary: "一起维护清晰、友好且有帮助的讨论环境。", content: ["请尊重每位创作者和玩家，共同建设社区内容。"] },
];

export const toolItems: ToolItem[] = [
  { slug: "calculator", title: "养成计算器", description: "角色 / 武器 / 声骸培养规划", icon: "calculator" },
  { slug: "echo", title: "声骸图鉴", description: "声骸数据查询与搭配参考", icon: "echo" },
  { slug: "team-builder", title: "配队模拟", description: "角色协同与队伍思路", icon: "team" },
];

export const characters: Character[] = [
  { id: "tide", name: "潮汐", role: "输出", title: "共鸣 · 湮灭", image: "/art/character-tide.png", description: "擅长控场与持续输出的远程共鸣者。", guideId: "changli-team" },
  { id: "rover", name: "银羽", role: "输出", title: "气动 · 迅刀", image: "/art/character-rover.png", description: "灵活切入战场，适合快速轮切的角色。", guideId: "tower-24" },
  { id: "lavender", name: "紫绫", role: "协同", title: "冷凝 · 协同", image: "/art/character-lavender.png", description: "以协同伤害和增益效果支援队伍。", guideId: "camellya-echo" },
];

export const echoSets: EchoSet[] = [
  { id: "floating-stars", name: "浮星祛暗", effect: "湮灭伤害提升", location: "荒石高地 / 无音区", role: "输出", description: "适合围绕湮灭伤害展开的主输出构筑。", guideId: "changli-team" },
  { id: "thunder", name: "彻空冥雷", effect: "导电伤害提升", location: "虎口矿场 / 归墟港市", role: "输出", description: "以导电伤害为核心的输出向套装。", guideId: "tower-24" },
  { id: "light-cloud", name: "轻云出月", effect: "共鸣解放增益", location: "瑝珑各地", role: "协同", description: "给轮切队提供稳定的共鸣解放增益。", guideId: "camellya-echo" },
  { id: "hidden-light", name: "隐世回光", effect: "治疗效果与协同", location: "云陵谷 / 乘霄山", role: "治疗", description: "适合治疗与协同角色的功能性选择。", guideId: "new-player-route" },
];
