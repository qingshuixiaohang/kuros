package com.kuros.kurosbackend.shared.cache;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

/**
 * 把「缓存失效」与「缓存回填」延迟到事务提交后执行的工具（审计 H1 修复）。
 *
 * 为什么需要它：Redis 是非事务资源。若在 @Transactional 方法体内直接
 * evict + 回填，而事务随后回滚，会出现两个后果——
 * ① 回滚前那次回填把**从未提交的内容**写进了 L1/L2，且旧内容已被 evict
 *    掉，只能等 TTL 自然过期，期间所有节点读到幽灵数据；
 * ② 白白制造一次跨节点失效广播（evict 成本 = Redis DEL + pub/sub）。
 *
 * 项目其实早有这条成文纪律：HANDOFF 规范第 4 条与 kuros-user 的
 * AuthService 都要求"Redis 写入必须 afterCommit"，但缓存侧一直违反。
 * 本类把该纪律固化为可复用的调用点，避免每个 service 各写一遍
 * registerSynchronization 的样板。
 *
 * 为什么不在没有事务时也延迟：evict 在非事务上下文（如定时任务、测试直接
 * 调用）没有"回滚"可等，延迟反而让失效晚一步。此时同步执行更符合直觉。
 */
public final class CacheInvalidationAfterCommit {

    private CacheInvalidationAfterCommit() {
    }

    /**
     * 注册一个事务提交后才执行的动作；当前没有事务时立即执行。
     *
     * @param action 提交后要做的缓存操作（驱逐 / 回填 / 两者）
     */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    /**
     * {@link #run(Runnable)} 的带参形式，省去调用方自己写闭包捕获。
     *
     * @param source  要操作的对象（通常是缓存名或 key）
     * @param action  对该对象执行的操作
     */
    public static <T> void run(T source, Consumer<T> action) {
        run(() -> action.accept(source));
    }
}
