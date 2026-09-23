package com.kuros.kurosbackend.search.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuros.kurosbackend.search.PostIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CDC 变更处理器单测（切片 #14 se-05，消费端组装逻辑 seam）。
 *
 * <p>纯单测（mock {@link PostIndexService}，无 canal/broker/ES/Spring 上下文，毫秒级、**非门控、进主套件**）：
 * 只验证 {@link PostCdcHandler} 的**外部可观测行为**——「给定一条 FlatMessage，触发了哪些 index/delete 调用」，
 * 不绑定私有方法实现。Canal→RocketMQ 传输层太重太脆，交 se-07 compose 端到端冒烟覆盖（见工单）。
 *
 * <p>覆盖 ADR 0007 D3/D4 的分发规则：posts 增改→index、posts 物理删→delete、
 * post_tags 任何变更（含解绑 DELETE）→按 post_id index（**绝不删帖子文档**）、同批次去重、DDL/非目标表忽略。
 */
@ExtendWith(MockitoExtension.class)
class PostCdcHandlerTest {

    private static final String POST_ID = "10000000-0000-0000-0000-000000000001";
    private static final String OTHER_POST_ID = "10000000-0000-0000-0000-000000000002";

    @Mock
    PostIndexService postIndexService;

    @InjectMocks
    PostCdcHandler handler;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 用一条**真实结构的 canal FlatMessage JSON**（含被忽略的 id/pkNames/isDdl/es/ts/sql/sqlType/old 字段）验证 DTO 绑定 + 分发。 */
    @Test
    void posts表INSERT的FlatMessageJson触发按id回源index() throws Exception {
        String json = """
                {
                  "id": 1,
                  "database": "kuros",
                  "table": "posts",
                  "pkNames": ["id"],
                  "isDdl": false,
                  "type": "INSERT",
                  "es": 1726000000000,
                  "ts": 1726000000001,
                  "sql": "",
                  "sqlType": {"id": 12, "title": 12, "status": 12},
                  "data": [
                    {"id": "%s", "title": "鸣潮的攻略详解", "status": "PUBLISHED"}
                  ],
                  "old": null
                }
                """.formatted(POST_ID);

        FlatMessage message = mapper.readValue(json, FlatMessage.class);
        // DTO 只绑定 database/table/type/data，其余字段被 @JsonIgnoreProperties 忽略（canal 值全为 String）
        assertThat(message.table()).isEqualTo("posts");
        assertThat(message.type()).isEqualTo("INSERT");
        assertThat(message.data()).hasSize(1);
        assertThat(message.data().get(0).get("id")).isEqualTo(POST_ID);

        handler.handle(message);

        verify(postIndexService).index(POST_ID);
        verify(postIndexService, never()).delete(POST_ID);
    }

    /** 逻辑删（status→DELETED）在 binlog 里是 UPDATE：仍走 index 重组（写入 status=DELETED），由搜索期 filter 排除，不物理删文档（D4）。 */
    @Test
    void posts表UPDATE逻辑删仍走index而非删文档() {
        FlatMessage message = new FlatMessage("kuros", "posts", "UPDATE",
                List.of(Map.of("id", POST_ID, "status", "DELETED")));

        handler.handle(message);

        verify(postIndexService).index(POST_ID);
        verify(postIndexService, never()).delete(POST_ID);
    }

    /** 物理 DELETE（清理任务/手工删库）：兜底删 ES 文档，避免孤儿（D4 末条）。 */
    @Test
    void posts表物理DELETE触发删索引文档() {
        FlatMessage message = new FlatMessage("kuros", "posts", "DELETE",
                List.of(Map.of("id", POST_ID)));

        handler.handle(message);

        verify(postIndexService).delete(POST_ID);
        verify(postIndexService, never()).index(POST_ID);
    }

    /** post_tags 变更（绑定新标签）：据外键 post_id 重组所属帖子的 tags，而非动 tag 行本身。 */
    @Test
    void postTags表INSERT按外键post_id重组所属帖子() {
        FlatMessage message = new FlatMessage("kuros", "post_tags", "INSERT",
                List.of(Map.of("id", "999", "post_id", POST_ID, "tag_id", "7")));

        handler.handle(message);

        verify(postIndexService).index(POST_ID);
        verify(postIndexService, never()).delete(POST_ID);
    }

    /**
     * 关键区分：post_tags 的 DELETE 是「解绑标签」，帖子本身还在 → 必须 index 重组（去掉该标签），
     * **绝不能删帖子文档**（否则解绑一个标签会让整篇帖子从索引消失）。
     */
    @Test
    void postTags表DELETE是解绑标签仍index而非删帖子文档() {
        FlatMessage message = new FlatMessage("kuros", "post_tags", "DELETE",
                List.of(Map.of("id", "999", "post_id", POST_ID, "tag_id", "7")));

        handler.handle(message);

        verify(postIndexService).index(POST_ID);
        verify(postIndexService, never()).delete(POST_ID);
    }

    /** 同批次多行命中同一 postId（如批量改标签）：回源读 DB 当前态，只需 index 一次（去重）。 */
    @Test
    void 同批次重复postId去重只index一次() {
        FlatMessage message = new FlatMessage("kuros", "post_tags", "INSERT",
                List.of(
                        Map.of("post_id", POST_ID, "tag_id", "7"),
                        Map.of("post_id", POST_ID, "tag_id", "8"),
                        Map.of("post_id", OTHER_POST_ID, "tag_id", "7")));

        handler.handle(message);

        verify(postIndexService, times(1)).index(POST_ID);
        verify(postIndexService, times(1)).index(OTHER_POST_ID);
    }

    /** DDL 事件（CREATE/ALTER/DROP）data 为空：无需索引，直接跳过（无需读 isDdl）。 */
    @Test
    void DDL空批次不触发任何索引() {
        handler.handle(new FlatMessage("kuros", "posts", "CREATE", null));
        handler.handle(new FlatMessage("kuros", "posts", "ALTER", List.of()));

        verifyNoInteractions(postIndexService);
    }

    /** 非订阅目标表（理论上 canal filter.regex 已挡掉）：防御性忽略，不触发索引。 */
    @Test
    void 非目标表变更被忽略() {
        FlatMessage message = new FlatMessage("kuros", "comments", "INSERT",
                List.of(Map.of("id", "c1", "post_id", POST_ID)));

        handler.handle(message);

        verifyNoInteractions(postIndexService);
    }
}
