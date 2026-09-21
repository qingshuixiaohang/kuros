package com.kuros.kurosbackend;

import com.kuros.kurosbackend.user.client.UserDirectoryClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
// split-08：精确列举 Feign 客户端（clients=... 而非 basePackages 扫描）——
// 启动期跨服务依赖面明确，避免误扫到非客户端接口，读者一眼能看到唯一的远程调用点
@EnableFeignClients(clients = UserDirectoryClient.class)
public class KurosBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(KurosBackendApplication.class, args);
    }

}
