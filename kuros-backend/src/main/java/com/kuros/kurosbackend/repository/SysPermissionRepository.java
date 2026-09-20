package com.kuros.kurosbackend.repository;

import com.kuros.kurosbackend.domain.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SysPermissionRepository extends JpaRepository<SysPermission, Integer> {

    /**
     * 查询某用户通过角色间接拥有的所有权限 code。
     * SaToken 的 StpInterface.getPermissionList() 需要返回 List<String>。
     * 联表路径：sys_user_role → sys_role_permission → sys_permission
     *
     * 两张关联表都没有映射为 JPA 实体，JPQL 无法 join 未映射的表，
     * 所以用原生 SQL（同 SysRoleRepository.findRoleCodesByUserId 的理由）。
     */
    @Query(value = """
            select distinct p.permission_code from sys_permission p
            join sys_role_permission rp on rp.permission_id = p.id
            join sys_user_role ur on ur.role_id = rp.role_id
            where ur.user_id = :userId
            """, nativeQuery = true)
    List<String> findPermissionCodesByUserId(@Param("userId") String userId);
}
