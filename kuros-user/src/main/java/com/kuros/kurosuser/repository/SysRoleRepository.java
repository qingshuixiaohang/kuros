package com.kuros.kurosuser.repository;

import com.kuros.kurosuser.domain.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * RBAC 角色仓储（split-06 自 kuros-backend 迁入）。
 * 拆分后 RBAC 的权威数据归用户域：StpInterfaceImpl 的角色查询以本库为准。
 */
public interface SysRoleRepository extends JpaRepository<SysRole, Integer> {

    /**
     * 查询某用户拥有的所有角色 code。
     * SaToken 的 StpInterface.getRoleList() 需要返回 List<String>，
     * 这里直接用原生 SQL 联表查询，避免 N+1。
     *
     * 为什么用 nativeQuery 而不是 JPQL？
     * sys_user_role 是纯关联表，没有映射为 JPA 实体（只为它建实体没有业务价值），
     * JPQL 无法 join 未映射的表，所以这里直接写原生 SQL。
     */
    @Query(value = """
            select r.role_code from sys_role r
            join sys_user_role ur on ur.role_id = r.id
            where ur.user_id = :userId
            """, nativeQuery = true)
    List<String> findRoleCodesByUserId(@Param("userId") String userId);
}
