package com.kuros.kurosbackend.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Function;

/**
 * 搜索索引别名生命周期管理（切片 #14 se-03）。
 *
 * <p>核心设计：对外只暴露别名 {@code post_search}，真实索引带版本号 {@code post_search_v1 / v2 / ...}。
 * 读写走别名 → 全量重建时先把新数据 bulk 写进 {@code v{n+1}}，写完再「原子切别名 + 删旧索引」，
 * 全程搜索不中断（zero-downtime reindex，ADR 0007）。若直接对真实索引 delete+recreate，重建窗口内搜索会命中空索引。
 *
 * <p>别名创建有两条幂等触发路径（都软失败：ES 不可用只影响搜索，绝不阻断主链路或应用启动）：
 * <ol>
 *   <li><b>启动引导</b>（切片 #14 code-review 修复）：{@link SearchIndexBootstrap} 在 {@code ApplicationReadyEvent}
 *       （应用已就绪、readiness 探针已过）后调 {@link #ensureAliasIfAvailable()}——ES 在线则全新栈一启动就有别名，
 *       搜索直接返回空结果而非 503；ES 不可用则仅记 warn 不上抛，故不会拖垮启动（保留 se-02「ES 是软依赖」的承诺）。</li>
 *   <li><b>首次写入兜底</b>：{@link PostIndexService#index} 调 {@link #ensureAlias()}——覆盖「启动时 ES 恰好不可用、
 *       之后才恢复」的窗口，届时首条 CDC 写入即补建别名。</li>
 * </ol>
 */
@Component
public class SearchIndexManager {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexManager.class);

    static final String ALIAS = PostSearchDoc.INDEX_ALIAS;
    static final String INDEX_PREFIX = "post_search_v";

    private final ElasticsearchOperations operations;

    public SearchIndexManager(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    /**
     * 幂等确保别名 {@code post_search} 已指向某个 {@code post_search_v{n}}；不存在则建 v1 并挂别名。
     * synchronized 收敛单实例内的并发冷启竞态；跨实例竞态由 createVersionedIndex 的 already-exists 兜底吸收。
     */
    synchronized void ensureAlias() {
        Optional<String> current = resolveCurrentIndex();
        if (current.isPresent()) {
            return;
        }
        String first = INDEX_PREFIX + "1";
        createVersionedIndex(first);
        switchAlias(null, first);
        log.info("搜索索引别名初始化完成：{} -> {}", ALIAS, first);
    }

    /**
     * 启动期软失败确保别名就绪（供 {@link SearchIndexBootstrap} 在 {@code ApplicationReadyEvent} 后调用）。
     *
     * <p>与 {@link #ensureAlias()} 的唯一区别：把 ES 不可用（连接拒绝/超时）吞成一条 warn 而非上抛——
     * ES 宕机时应用启动不受影响（保留 se-02「ES 是软依赖」的承诺），ES 在线时全新栈搜索即刻返回空结果而非 503。
     * 放在应用就绪之后跑：即使 ES 慢到超时，也只是延后这条日志，不阻塞启动完成。
     */
    void ensureAliasIfAvailable() {
        try {
            ensureAlias();
        } catch (RuntimeException e) {
            log.warn("启动期初始化搜索索引别名失败（ES 可能不可用），降级为懒建——搜索将在 ES 恢复后经首次写入/全量重建可用：{}", e.toString());
        }
    }

    /**
     * 零停机全量重建：建 {@code v{n+1}} → 由 {@code writer} 把全量文档 bulk 写入新索引 →
     * 原子切别名（remove 旧 + add 新，ES 在单次 _aliases 调用内原子生效）→ 删旧索引。
     *
     * @param writer 接收新索引坐标、执行 bulk 写入、返回写入文档数（由 {@link PostIndexService} 提供回源组装逻辑）
     * @return 本次重建索引的文档数
     */
    long reindex(Function<IndexCoordinates, Long> writer) {
        String oldIndex = resolveCurrentIndex().orElse(null);
        int nextVersion = (oldIndex == null ? 0 : parseVersion(oldIndex)) + 1;
        String newIndex = INDEX_PREFIX + nextVersion;

        createVersionedIndex(newIndex);
        long count = writer.apply(IndexCoordinates.of(newIndex));
        // 刷新使新索引文档立即可搜，再切别名——否则切完瞬间搜索命中尚未 refresh 的新索引会短暂少结果
        operations.indexOps(IndexCoordinates.of(newIndex)).refresh();
        switchAlias(oldIndex, newIndex);
        if (oldIndex != null) {
            deleteIndex(oldIndex);
        }
        log.info("搜索索引全量重建完成：{} -> {}，共 {} 篇", oldIndex, newIndex, count);
        return count;
    }

    /** 解析别名当前指向的真实索引（{@code post_search_v{n}}）；别名不存在返回 empty。 */
    Optional<String> resolveCurrentIndex() {
        try {
            // getAliases(alias) 返回 {真实索引名 -> 该索引上的别名集合}，取符合版本前缀的键即当前索引
            var aliases = operations.indexOps(IndexCoordinates.of(ALIAS)).getAliases(ALIAS);
            return aliases.keySet().stream()
                    .filter(name -> name.startsWith(INDEX_PREFIX))
                    .findFirst();
        } catch (RuntimeException e) {
            // 别名不存在时 ES 抛 index_not_found；视为「尚未初始化」，交由 ensureAlias 建 v1
            return Optional.empty();
        }
    }

    private void createVersionedIndex(String indexName) {
        IndexOperations indexOps = operations.indexOps(IndexCoordinates.of(indexName));
        if (indexOps.exists()) {
            return;
        }
        // mapping 从 PostSearchDoc 的 @Field 注解派生（含 ik 分词器），再套到带版本号的真实索引上
        Document mapping = operations.indexOps(PostSearchDoc.class).createMapping();
        try {
            indexOps.create();
            indexOps.putMapping(mapping);
        } catch (RuntimeException e) {
            // 跨实例并发冷启：另一节点已建同名索引，吸收 resource_already_exists 后继续挂别名
            log.warn("创建索引 {} 时冲突（可能已被其他节点创建），继续：{}", indexName, e.toString());
        }
    }

    private void switchAlias(String oldIndex, String newIndex) {
        AliasActions actions = new AliasActions();
        if (oldIndex != null) {
            actions.add(new AliasAction.Remove(aliasParams(oldIndex)));
        }
        actions.add(new AliasAction.Add(aliasParams(newIndex)));
        operations.indexOps(PostSearchDoc.class).alias(actions);
    }

    private AliasActionParameters aliasParams(String indexName) {
        return AliasActionParameters.builder()
                .withIndices(indexName)
                .withAliases(ALIAS)
                .build();
    }

    private void deleteIndex(String indexName) {
        try {
            operations.indexOps(IndexCoordinates.of(indexName)).delete();
        } catch (RuntimeException e) {
            // 删旧索引失败不影响新索引已生效的读写，仅记警告（可由运维手动清理残留）
            log.warn("删除旧索引 {} 失败（不影响别名已切换）：{}", indexName, e.toString());
        }
    }

    private int parseVersion(String indexName) {
        try {
            return Integer.parseInt(indexName.substring(INDEX_PREFIX.length()));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
