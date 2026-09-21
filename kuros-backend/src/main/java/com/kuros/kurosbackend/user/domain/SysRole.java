package com.kuros.kurosbackend.user.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * RBAC 角色实体。
 * 为什么单独建表而不继续用 UserRole 枚举？
 * 因为 SaToken 的 StpInterface 需要动态查询用户的角色列表，
 * 枚举无法在运行时新增角色，也无法支持多角色（一个用户同时是 ADMIN 和 MODERATOR）。
 */
@Entity
@Table(name = "sys_role")
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "role_code", length = 32, nullable = false, unique = true)
    private String roleCode;

    @Column(name = "role_name", length = 64, nullable = false)
    private String roleName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected SysRole() {}

    public SysRole(String roleCode, String roleName) {
        this.roleCode = roleCode;
        this.roleName = roleName;
        this.createdAt = LocalDateTime.now();
    }

    public Integer getId() { return id; }
    public String getRoleCode() { return roleCode; }
    public String getRoleName() { return roleName; }
}
