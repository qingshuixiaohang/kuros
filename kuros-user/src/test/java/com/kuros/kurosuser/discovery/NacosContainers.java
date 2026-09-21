package com.kuros.kurosuser.discovery;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

/**
 * Nacos 测试容器工厂：纪律性复制 kuros-backend / kuros-gateway 的同名工厂，
 * 端口方案（28848/29848）刻意保持一致——三工程的集成测试各在独立 CI job 中串行执行，
 * 固定端口不会互撞，而"所有工程同一组常量"比"每个工程随机取一组"少一个记忆点。
 *
 * gRPC 固定端口的原因：Nacos 客户端约定 gRPC 端口 = API 端口 + 1000（同一地址推导），
 * Testcontainers 默认随机映射会打破偏移量，必须固定绑定保持差值。
 * 客户端地址用 127.0.0.1 而非 localhost：Windows 上 localhost 优先解析为 IPv6 [::1]，
 * 实测 gRPC 连接 [::1] 端口映射会 Permission denied。
 */
public final class NacosContainers {

    public static final int API_PORT = 28848;
    public static final int GRPC_PORT = API_PORT + 1000;
    public static final String SERVER_ADDR = "127.0.0.1:" + API_PORT;

    private NacosContainers() {
    }

    public static GenericContainer<?> newNacos() {
        return new GenericContainer<>("nacos/nacos-server:v3.1.1")
                .withEnv("MODE", "standalone")
                // v3 镜像强制要求鉴权三件套才能启动；鉴权开关默认关闭（dev 语义，与 compose 一致）。
                // 镜像版本选 v3.1.1：SCA 2025.1.0.0 实际捆绑 nacos-client 3.1.1，
                // 服务端与客户端 minor 版本必须对齐，v3.0.3 实测 gRPC 握手失败
                .withEnv("NACOS_AUTH_TOKEN", "VGhpc0lzTXlDdXN0b21TZWNyZXRLZXkwMTIzNDU2Nzg=")
                .withEnv("NACOS_AUTH_IDENTITY_KEY", "serverIdentity")
                .withEnv("NACOS_AUTH_IDENTITY_VALUE", "kuros")
                .withCreateContainerCmdModifier(cmd -> cmd.withPortBindings(
                        new Ports(
                                new PortBinding(Ports.Binding.bindPort(API_PORT), ExposedPort.tcp(8848)),
                                new PortBinding(Ports.Binding.bindPort(GRPC_PORT), ExposedPort.tcp(9848)))))
                .waitingFor(Wait.forHttp("/nacos/actuator/prometheus")
                        .forPort(8848)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)));
    }

}
