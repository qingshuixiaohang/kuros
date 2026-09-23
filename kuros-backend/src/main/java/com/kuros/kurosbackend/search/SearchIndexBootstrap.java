package com.kuros.kurosbackend.search;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 搜索索引启动引导（切片 #14 code-review 修复）。
 *
 * <p>为什么需要它：别名 {@code post_search} 原本只在<b>写路径</b>（{@link PostIndexService#index} 首次索引）懒创建。
 * 于是一个全新部署、尚未发帖也未跑全量重建的栈上，{@code GET /api/v1/search} 会因 index_not_found 被
 * {@link SearchQueryService} 的软依赖 catch 转成 503「搜索服务暂不可用」——可 ES 其实活得好好的，只是索引没建，
 * 这个信号会误导用户/运维以为搜索挂了（S3 冒烟脚本不得不专门容忍初始 503，即此摩擦的外部证据）。
 *
 * <p>修复：应用就绪后主动引导一次别名。用 {@link ApplicationReadyEvent}（而非 {@code @PostConstruct}）是刻意的——
 * 此时上下文已刷新、readiness 探针已通过，即便 ES 慢到连接超时也只是延后一条日志，绝不阻塞启动完成；
 * 且 {@link SearchIndexManager#ensureAliasIfAvailable()} 内部软失败（ES 不可用只记 warn），
 * 完整保留 se-02「ES 是 backend 软依赖、宕机不拖垮应用」的承诺。引导成功后，全新栈搜索直接返回空结果（语义正确），
 * 503 收窄为「ES 真的连不上」这一种情形。
 */
@Component
public class SearchIndexBootstrap {

    private final SearchIndexManager indexManager;

    public SearchIndexBootstrap(SearchIndexManager indexManager) {
        this.indexManager = indexManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        indexManager.ensureAliasIfAvailable();
    }
}
