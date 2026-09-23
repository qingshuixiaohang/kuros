package com.kuros.kurosbackend.search.cdc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Canal 扁平化变更消息（切片 #14 se-05）：{@code canal.mq.flatMessage=true} 时投递到 RocketMQ 的 JSON 载体。
 *
 * <p>Canal 把一行 binlog 变更拍平成 JSON（见 se-01 spike {@code FlatMessageDeserializationSpikeTest} 已验证的真实结构）：
 * {@code data[]} 是本批次变更行的数组，**每行的列值全为 String**（binlog 不保留 Java 类型）；
 * {@code old[]} 仅 UPDATE 时带变更前镜像；{@code type} 是消息级的 INSERT/UPDATE/DELETE（同批 data 共享一个 type）。
 *
 * <p>为什么只绑定这几个字段：消费端（{@link PostCdcHandler}）的唯一职责是「从变更里认出哪个 postId 动了」，
 * 然后回源 DB + Feign 重组权威文档（ADR 0007 D3）——**不直接用 data 里的列值拼文档**（缺 tags/authorName、String 转类型易错）。
 * 故 {@code id/pkNames/isDdl/es/ts/sql/sqlType/old} 等一律忽略（{@code @JsonIgnoreProperties(ignoreUnknown=true)}）：
 * DDL 事件的 {@code data} 为空，由 handler 的空批次守卫天然跳过，无需显式读 {@code isDdl}。
 *
 * <p>用 record：不可变、天然携带 JSON 反序列化所需的组件访问器（Spring Cloud Stream 默认 application/json，同 #11 {@code InteractionEvent}）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlatMessage(
        String database,
        String table,
        String type,
        List<Map<String, String>> data
) {
}
