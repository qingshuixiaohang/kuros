package com.kuros.kurosuser.shared.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /internal/** 共享密钥校验拦截器（安全加固 sec-01，A2 漏洞的拦截层）。
 *
 * 漏洞背景：内部 API（/internal/v1/users/batch|following|followers|follow-stats）
 * 此前在 SaTokenConfigure 里被 {@code notMatch("/internal/**")} 整段豁免——
 * 即不带任何凭证也能批量导出全站用户与关注关系。而 compose 又曾把容器端口
 * 发布到宿主机 0.0.0.0，等于这台机器网络上任何人都能拉全站数据。
 *
 * 两道修复（缺一不可）：
 * 1. 网络层：compose 端口绑定改 127.0.0.1（宿主机不再可达，内部调用走 compose 网络名）
 * 2. 应用层：就是这个拦截器——即便网络被穿透（端口映射改错、服务被直接暴露、
 *    内网横向移动），请求仍必须携带 {@code X-Internal-Token} 且值等于配置的密钥。
 *    为什么两层都要：单靠网络隔离是"配置写错就全裸"，单靠密钥是"泄漏即失守"；
 *    纵深防御的原则是任一层被绕过时另一层仍然有效。
 *
 * 为什么用 HandlerInterceptor 而不是 Servlet Filter：
 * 与 SaTokenConfigure/CsrfInterceptor 同属 MVC 拦截器栈，order 由注册处统一编排，
 * 能明确排在 SaToken 鉴权之前；Filter 需要额外注册且顺序靠声明位置隐式决定。
 *
 * 为什么 fail-closed（密钥未配置时空值一律拒绝）：
 * "两边都空就算通过"是常见的安全反模式——它让"忘记配置"从显式故障退化成
 * 静默无防护。宁可让内部链路 401（故障显眼、立刻被发现），也不允许零凭证放行。
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    /** 内部调用方（kuros-backend 的 Feign 客户端）必须携带的请求头名。 */
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /**
     * 错误码与 SaToken 的 UNAUTHORIZED 刻意区分：内部链路的调用方（backend 门面）
     * 要能从错误码判断"是密钥没配对还是用户没登录"，两种 401 的处置完全不同。
     */
    static final String ERROR_CODE = "INTERNAL_TOKEN_INVALID";

    private final String internalToken;

    public InternalTokenInterceptor(@Value("${app.internal.token:}") String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
        // 未配置密钥 = 内部 API 全部拒绝（fail-closed）。空串与 null 都走这条：
        // 不能用 isBlank 之外的判定，否则 " " 这种值会被当成"配了个空格密钥"。
        if (internalToken == null || internalToken.isBlank()) {
            reject(response);
            return false;
        }

        String presented = request.getHeader(INTERNAL_TOKEN_HEADER);
        // 用常量时间比较而非 equals：密钥比对走时序侧信道在真实项目里是标准防御，
        // 虽然本项目威胁模型（单人作品集）下不构成现实风险，但安全代码应当默认正确。
        if (presented == null || !constantTimeEquals(internalToken, presented)) {
            reject(response);
            return false;
        }
        return true;
    }

    /**
     * 401 + 与全局异常处理器同构的错误体。
     * 为什么手写 JSON 而不抛异常走 ApiExceptionHandler：异常处理器针对的是
     * Controller 内抛出的业务异常，而拦截器短路发生在 handler 解析之前；
     * 且这里的响应体必须与既有错误契约逐字同形（code/message/data），
     * 否则调用方的错误分支解析不到 code 字段。
     */
    private void reject(HttpServletResponse response) throws java.io.IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"code":"INTERNAL_TOKEN_INVALID","message":"内部接口凭证无效","data":null}
                """);
    }

    /**
     * 常量时间比较。
     *
     * 为什么容忍长度泄漏：真正防时序侧信道的做法是对两侧各自身份做哈希后再比对
     * （如 MessageDigest.isEqual），那样长度信息也被抹掉。本项目内部密钥由部署方
     * 控制、不在用户输入域内，时序攻击不构成现实威胁；这里取逐字符异或累积的
     * 简化形式，保留"失配位置不影响耗时"的性质，同时不为一个不存在的威胁引入
     * 哈希依赖与额外复杂度。若将来密钥改为用户可控，应换成 MessageDigest.isEqual。
     */
    private boolean constantTimeEquals(String expected, String presented) {
        if (expected.length() != presented.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ presented.charAt(i);
        }
        return diff == 0;
    }
}
