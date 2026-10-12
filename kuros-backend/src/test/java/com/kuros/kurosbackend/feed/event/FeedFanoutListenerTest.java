package com.kuros.kurosbackend.feed.event;

import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feed 扇出监听器测试（审计 D3：此前零覆盖）。
 *
 * 纯 Mockito 单元测试而不是 @SpringBootTest：被测逻辑是"翻页取粉丝"，
 * 不依赖 Redis/DB——Spring 上下文在这里只会让测试变慢，且 Spring Boot 4
 * 已移除 @MockitoBean，没有轻量的 bean 替换手段。
 *
 * 要锁定的契约：
 * 1. 粉丝数超过单页上限时必须翻页取全——不能静默丢人
 * 2. 单页能装下时只发一次请求，不多打
 * 3. 最后一页刚好满（total 是页大小整数倍）也正确终止
 * 4. 无粉丝时直接结束
 * 5. Feign 异常上抛（由 onPostPublished 的 try/catch 降级），不在这里吞
 *
 * 关于第 1 条（本类的主要价值）：原实现只请求第一页且不看 totalPages，
 * 粉丝超过 5000 的大 V 会丢掉第 5001 个以后的粉丝——那些人的 timeline
 * 永远看不到这条帖子，且没有任何日志。彻底方案是 #13 的"推拉混合"，
 * 但在那之前"取全"是最低要求。
 */
@ExtendWith(MockitoExtension.class)
class FeedFanoutListenerTest {

    /** 单页上限，须与 FeedFanoutListener.MAX_FOLLOWERS_PAGE_SIZE 一致。 */
    private static final int PAGE_SIZE = 5000;

    private static final String AUTHOR_ID = "10000000-0000-0000-0000-000000000001";

    @Mock
    private UserDirectoryFacade userDirectory;

    private FeedFanoutListener listener;

    @BeforeEach
    void setUp() {
        // 直接构造：timelineStore 在取粉丝这条路径上用不到，传 null 即可
        listener = new FeedFanoutListener(userDirectory, null);
    }

    /** 构造一页粉丝响应。 */
    private PageResult<UserBriefDto> pageOf(List<String> ids, int page, int size, long total) {
        List<UserBriefDto> items = new ArrayList<>();
        for (String id : ids) {
            items.add(new UserBriefDto(id, "nick-" + id, null, null));
        }
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResult<>(items, new PageMeta(page, size, total, totalPages));
    }

    private List<String> ids(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> String.format("f%08d", i))
                .toList();
    }

    /**
     * 核心契约：粉丝超过单页上限时翻页取全，一个都不能少。
     *
     * 构造 5000 + 300 = 5300 个粉丝：第一页满、还有第二页。
     * 只 mock 两页——若实现请求第三页会拿到 null，随后 NPE，
     * 正好暴露"翻页边界算错"。
     */
    @Test
    void 粉丝超过单页上限时翻页取全() {
        int total = PAGE_SIZE + 300;
        List<String> all = ids(total);
        List<String> first = all.subList(0, PAGE_SIZE);
        List<String> second = all.subList(PAGE_SIZE, total);

        when(userDirectory.followers(eq(AUTHOR_ID), eq(1), anyInt()))
                .thenReturn(pageOf(first, 1, PAGE_SIZE, total));
        when(userDirectory.followers(eq(AUTHOR_ID), eq(2), anyInt()))
                .thenReturn(pageOf(second, 2, PAGE_SIZE, total));

        List<String> fetched = listener.fetchFollowerIds(AUTHOR_ID);

        assertThat(fetched)
                .as("5300 个粉丝必须全部取到")
                .hasSize(total)
                .containsExactlyInAnyOrderElementsOf(all);

        // 只应请求两页：多打一次是无用请求，少打一次就是丢人
        verify(userDirectory, times(1)).followers(AUTHOR_ID, 1, PAGE_SIZE);
        verify(userDirectory, times(1)).followers(AUTHOR_ID, 2, PAGE_SIZE);
        verify(userDirectory, times(0)).followers(eq(AUTHOR_ID), eq(3), anyInt());
    }

    /**
     * 单页能装下时只发一次请求：不为翻页而翻页。
     */
    @Test
    void 单页能装下时只请求一次() {
        List<String> followers = ids(50);
        when(userDirectory.followers(eq(AUTHOR_ID), anyInt(), anyInt()))
                .thenReturn(pageOf(followers, 1, PAGE_SIZE, followers.size()));

        List<String> fetched = listener.fetchFollowerIds(AUTHOR_ID);

        assertThat(fetched).containsExactlyInAnyOrderElementsOf(followers);
        verify(userDirectory, times(1)).followers(AUTHOR_ID, 1, PAGE_SIZE);
    }

    /**
     * 最后一页刚好满（total 是 PAGE_SIZE 的整数倍）时也要正确终止：
     * totalPages = 2、第二页满，不能再打第三页。
     *
     * 这个用例专门防"用本页是否满来判断是否还有下一页"的写法——那种写法
     * 在整除场景下会多发一次请求。
     */
    @Test
    void 最后一页刚好满时正确终止() {
        int total = PAGE_SIZE * 2;
        List<String> all = ids(total);

        when(userDirectory.followers(eq(AUTHOR_ID), eq(1), anyInt()))
                .thenReturn(pageOf(all.subList(0, PAGE_SIZE), 1, PAGE_SIZE, total));
        when(userDirectory.followers(eq(AUTHOR_ID), eq(2), anyInt()))
                .thenReturn(pageOf(all.subList(PAGE_SIZE, total), 2, PAGE_SIZE, total));

        List<String> fetched = listener.fetchFollowerIds(AUTHOR_ID);

        assertThat(fetched).hasSize(total);
        verify(userDirectory, times(0)).followers(eq(AUTHOR_ID), eq(3), anyInt());
    }

    /**
     * 无粉丝时直接结束，不发多余请求。
     */
    @Test
    void 无粉丝时只请求一次() {
        when(userDirectory.followers(eq(AUTHOR_ID), anyInt(), anyInt()))
                .thenReturn(pageOf(List.of(), 1, PAGE_SIZE, 0));

        List<String> fetched = listener.fetchFollowerIds(AUTHOR_ID);

        assertThat(fetched).isEmpty();
        verify(userDirectory, times(1)).followers(AUTHOR_ID, 1, PAGE_SIZE);
    }

    /**
     * Feign 异常必须上抛：吞在这里会让 onPostPublished 以为扇出成功，
     * 而降级日志是唯一的排查线索。
     */
    @Test
    void feign异常时上抛交由降级处理() {
        when(userDirectory.followers(eq(AUTHOR_ID), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("user-service 503"));

        assertThatThrownBy(() -> listener.fetchFollowerIds(AUTHOR_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("503");
    }
}
