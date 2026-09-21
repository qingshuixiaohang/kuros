package com.kuros.kurosuser.repository;

import com.kuros.kurosuser.domain.CommunityUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用户仓储（split-06 自 kuros-backend 迁入，方法签名与查询逐字一致）。
 * 迁移后的权威归属：登录建号与角色分配只发生在 kuros-user；
 * backend 侧的副本在 split-08（Feign 组合视图）前仅供内容域作者组装读取，不承担写路径。
 */
public interface CommunityUserRepository extends JpaRepository<CommunityUser, String> {

    java.util.Optional<CommunityUser> findByPhone(String phone);

    /**
     * 为新用户分配默认角色（写入 sys_user_role 关联表）。
     * 为什么用 native query 而不是 JPA 实体关联？
     * 因为 sys_user_role 是纯关联表，没有业务字段，不值得为它建一个完整的 Entity。
     * 用 @Modifying + native query 直接 INSERT 更简洁。
     *
     * 为什么按 role_code 而不是 role_id 分配？
     * 自增 ID 的顺序不是语义保证（不同环境/迁移重跑可能不同），
     * role_code 是业务唯一键，INSERT ... SELECT 子查询在 MySQL 和 H2 都可用。
     */
    @Modifying
    @Query(value = """
            INSERT INTO sys_user_role (user_id, role_id, created_at)
            SELECT :userId, r.id, CURRENT_TIMESTAMP FROM sys_role r WHERE r.role_code = :roleCode
            """, nativeQuery = true)
    void assignRole(@Param("userId") String userId, @Param("roleCode") String roleCode);
}
