# 切片 #14：Elasticsearch 9.4.5 + analysis-ik 9.4.5 中文分词插件（自建镜像）
#
# 为什么自建而非用官方镜像？官方 docker.elastic.co/elasticsearch/elasticsearch 不含
# ik 中文分词器，而本项目搜索的核心难点正是中文分词（ik_max_word 建索引 / ik_smart 搜索）。
# ik 插件必须与 ES 严格等版本（9.4.5 ↔ 9.4.5），否则插件加载时版本校验直接拒绝启动。
#
# 为什么用 get.infini.cloud 而非 GitHub releases？infinilabs（ik 官方维护方）已把插件包
# 从 GitHub releases 迁到 release.infinilabs.com，get.infini.cloud/elasticsearch/analysis-ik/<版本>
# 是其稳定的在线安装入口，elasticsearch-plugin 会自动拉取并校验；国内访问比 GitHub 通畅。
# --batch 自动应答"是否信任该插件"的交互提示（Dockerfile 构建无 TTY，不加会挂起）。
FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.5

RUN bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/9.4.5
