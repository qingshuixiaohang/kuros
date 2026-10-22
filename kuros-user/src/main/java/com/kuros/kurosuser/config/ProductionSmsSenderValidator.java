package com.kuros.kurosuser.config;

import com.kuros.kurosuser.auth.SmsSender;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
@Profile("production")
public class ProductionSmsSenderValidator implements BeanFactoryPostProcessor {

    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "test");

    public ProductionSmsSenderValidator() {
    }

    @Override
    public void postProcessBeanFactory(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        Environment environment = beanFactory.getBean(Environment.class);
        Set<String> activeProfiles = Set.of(environment.getActiveProfiles());
        if (activeProfiles.stream().anyMatch(DEVELOPMENT_PROFILES::contains)) {
            throw new IllegalStateException("production cannot be combined with dev or test profiles");
        }
        if (beanFactory.getBeanNamesForType(SmsSender.class, true, false).length == 0) {
            throw new IllegalStateException("production requires an SmsSender adapter");
        }
    }
}
