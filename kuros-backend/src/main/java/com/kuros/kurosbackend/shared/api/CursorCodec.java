package com.kuros.kurosbackend.shared.api;

import com.kuros.kurosbackend.shared.exception.AuthRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 不透明游标编解码（切片 #13 / rp-04、rp-05 共用）。
 *
 * 为什么游标要「不透明」而不是直接暴露 {@code ?score=123&after=postId}？
 * ① 契约稳定：排序键的组成是实现细节（Feed 是 (score, postId)，帖子列表是 (publishedAt, id) 或
 *    (likeCount, commentCount, publishedAt, id)）。编码成单串后，将来调整排序键不破坏对外参数形态；
 * ② 防篡改：客户端把多个字段拼进一个 base64 串，服务端统一解码校验，避免逐参数被随意改动导致翻页错乱；
 * ③ 简洁：前端只需持有并透传一个 {@code cursor} 字符串，无需理解内部结构。
 *
 * 编码格式：token 用「单元分隔符」U+001F 连接后做 base64url（无 padding）。
 * 为什么用 U+001F 作分隔符？它是不可打印控制字符，绝不会出现在 UUID、数字、时间戳等真实 token 里，
 * 故无需转义即可安全 split。base64url（而非标准 base64）避免 '+' '/' '=' 出现在 query string 里需要再转义。
 *
 * 注意：base64 只是编码不是加密——游标内容对客户端是「可解码但不该依赖」的，安全性不依赖它不可读。
 */
public final class CursorCodec {

    /** 单元分隔符（U+001F）：token 间分隔，不会与真实 token 内容冲突。 */
    private static final String DELIMITER = "\u001F";

    private CursorCodec() {
    }

    /** 把有序 token 列表编码为不透明游标串。 */
    public static String encode(List<String> tokens) {
        String joined = String.join(DELIMITER, tokens);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标串为 token 列表。
     * @throws AuthRequestException 游标非法 base64（客户端篡改/损坏）时抛 INVALID_CURSOR，由全局异常处理映射为 400。
     */
    public static List<String> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return List.of();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor.trim());
            String joined = new String(decoded, StandardCharsets.UTF_8);
            if (joined.isEmpty()) {
                return List.of();
            }
            // split(-1) 保留尾部空 token，避免 token 本身为空时被吞掉导致字段错位
            return List.of(joined.split(DELIMITER, -1));
        } catch (IllegalArgumentException e) {
            throw new AuthRequestException("INVALID_CURSOR", "游标格式无效");
        }
    }
}
