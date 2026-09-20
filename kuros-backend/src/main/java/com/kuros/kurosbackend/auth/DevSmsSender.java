package com.kuros.kurosbackend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 开发环境短信发送实现：只打印日志，不真正发短信。
 *
 * 为什么用 @Profile("!production") 而不是 @ConditionalOnProperty？
 * 因为小哈书的做法是按环境切换：dev/test 走日志，production 走阿里云 SDK。
 * 后面你接真实短信时，只需新建一个 AliyunSmsSender 标注 @Profile("production")，
 * Spring 会自动按 Profile 选择实现，业务代码零改动。
 */
@Component
@Profile("!production")
public class DevSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(DevSmsSender.class);

    @Async("smsExecutor")
    @Override
    public void send(String phone, String code) {
        // 模拟短信发送延迟（真实场景是网络 IO）
        log.info("[DevSms] 向 {} 发送验证码: {}（开发环境仅打印日志，不真正发送）", phone, code);
    }
}
