package com.kuros.kurosbackend.user.repository;

import com.kuros.kurosbackend.user.domain.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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
