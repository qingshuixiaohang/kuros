package com.kuros.kurosbackend.echoes.api;

/**
 * 声骸响应体。
 *
 * 与前端 mock 的 EchoSet 对齐（name/effect/location/role/description），
 * 但有两处刻意不同：
 * - 对外用 slug 而不是 UUID——URL 可读，且不暴露内部主键
 * - guideSlug 可为空——未关联攻略时前端不渲染「查看适配攻略」按钮，
 *   而不是渲染一个必然 404 的链接
 */
public record EchoResponse(
        String id,
        String slug,
        String name,
        String effect,
        String location,
        String role,
        String description,
        String guideSlug
) {
}
