package com.kuros.kurosbackend.health;

import com.kuros.kurosbackend.storage.StorageStrategy;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 存储策略健康指示器。
 *
 * 为什么需要自定义 HealthIndicator？
 * Spring Boot Actuator 默认只检查数据库连接，
 * 但存储策略（本地/MinIO）的连通性不在默认检查范围内。
 * 如果存储挂了但 DB 正常，健康检查仍然返回 UP，容器不会被 K8s 重启。
 *
 * 自定义指示器会在 /actuator/health 中增加 "storage" 组件，
 * 检查 StorageStrategy 是否可用。不可用时返回 DOWN，K8s 会自动重启 Pod。
 */
@Component("storage")
public class StorageHealthIndicator implements HealthIndicator {

    private final StorageStrategy storageStrategy;

    public StorageHealthIndicator(StorageStrategy storageStrategy) {
        this.storageStrategy = storageStrategy;
    }

    @Override
    public Health health() {
        try {
            // 尝试读取一个不存在的 key，如果存储策略正常工作会抛 ResourceNotFoundException
            // 如果存储挂了会抛连接异常
            storageStrategy.get("health-check-nonexistent-key");
            return Health.up()
                    .withDetail("strategy", storageStrategy.getClass().getSimpleName())
                    .build();
        } catch (Exception e) {
            // getUrl 对不存在的 key 返回 null 或抛异常都是"存储可用"的信号
            // 只有连接异常（如 MinIO 不可达）才算 DOWN
            if (isConnectionError(e)) {
                return Health.down()
                        .withDetail("strategy", storageStrategy.getClass().getSimpleName())
                        .withException(e)
                        .build();
            }
            // 非连接异常（如 key 不存在）说明存储本身是通的
            return Health.up()
                    .withDetail("strategy", storageStrategy.getClass().getSimpleName())
                    .build();
        }
    }

    private boolean isConnectionError(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.net.ConnectException ||
                    cause instanceof java.net.UnknownHostException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
