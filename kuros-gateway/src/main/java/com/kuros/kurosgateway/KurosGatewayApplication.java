package com.kuros.kurosgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * kuros-gateway：微服务体系统一入口（切片 #9）。
 *
 * 纯换门（Q6 决策）：路由转发是唯一职责，鉴权/限流/CORS 全部留在 kuros-backend，
 * 因此这里除启动类外没有任何业务代码。
 */
@SpringBootApplication
public class KurosGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(KurosGatewayApplication.class, args);
    }

}
