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
#
# 国内拉取兜底（se-02 验收②）：基础镜像与 ik 下载地址都做成 ARG，默认指向官方源
# （行为与 se-01 spike 一致），国内网络不畅时可在 .env / compose build.args 覆盖为镜像源
# （对齐 quay.io/minio 的可配风格），无需改本文件。ik 版本必须与 ES 严格等版本，
# 故两个 ARG 的版本号要同步改。
#
# ⚠ Dockerfile 作用域陷阱：FROM **之前**声明的 ARG 只在 FROM 行可见，不进入构建阶段；
# 故用于 FROM 的 ES_BASE_IMAGE 声明在前，而 RUN 里用的 IK_PLUGIN_URL 必须在 FROM **之后**
# 重新声明，否则 RUN 阶段 ${IK_PLUGIN_URL} 为空 → elasticsearch-plugin install 缺 URL 报 exit 64。
ARG ES_BASE_IMAGE=docker.elastic.co/elasticsearch/elasticsearch:9.4.5

FROM ${ES_BASE_IMAGE}

# FROM 之后重新声明，使 RUN 阶段可见（可被 compose build.args / --build-arg 覆盖）
ARG IK_PLUGIN_URL=https://get.infini.cloud/elasticsearch/analysis-ik/9.4.5

RUN bin/elasticsearch-plugin install --batch ${IK_PLUGIN_URL}
