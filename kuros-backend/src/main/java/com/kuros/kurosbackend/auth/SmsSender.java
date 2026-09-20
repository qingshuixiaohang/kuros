package com.kuros.kurosbackend.auth;

/**
 * 短信发送接口（策略模式）。
 *
 * 为什么不直接写死阿里云短信 SDK？
 * 因为接真实短信需要阿里云账号 + 签名审核 + 模板审核，这是运维问题不是代码问题。
 * 用接口抽象后，dev 环境走 DevSmsSender（日志输出），生产环境切换为 AliyunSmsSender，
 * 只需改一个 @Profile 注解，业务代码完全不动。
 *
 * 对应小哈书第五章 5.4：自定义线程池 + 短信异步发送。
 */
public interface SmsSender {

    /**
     * 异步发送验证码短信。
     * 实现类应该标注 @Async，由调用方通过线程池异步执行。
     *
     * @param phone 手机号
     * @param code  6 位验证码
     */
    void send(String phone, String code);
}
