package com.kuros.kurosbackend;

/**
 * 测试专属 H2 库名工厂（切片 #10 全量串库问题的修复，见 docs/learning）。
 *
 * 背景：application-test.properties 的默认 H2 库名固定为 kuros，而 Spring 测试
 * 框架的 context 缓存是 JVM 级静态的——先跑完的测试类 context 不销毁，Hikari
 * 连接持续持有同名内存库，导致后续测试类的 {@code @DirtiesContext} 重建 context
 * 时库不销毁、Flyway 不重放，数据在测试类之间串（表现为"单类跑绿、全量跑红"）。
 *
 * 修复：每个测试类通过 {@code @DynamicPropertySource} 注入唯一库名，
 * 库的生命周期与该类的 context 严格对齐——context 关闭即库销毁，重建即 Flyway
 * 重放（@DirtiesContext 恢复真实语义）。
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    /** 每个测试类传入自己的 slug（类语义化短名），库名全库唯一。 */
    public static String h2Url(String slug) {
        return "jdbc:h2:mem:kuros-" + slug + ";MODE=MySQL;DB_CLOSE_DELAY=0;DATABASE_TO_LOWER=TRUE";
    }
}
