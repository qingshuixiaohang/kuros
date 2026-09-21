package com.kuros.kurosuser.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * RBAC 权限实体（split-06 自 kuros-backend 迁入）。
 * 权限数据属用户域：拆分后 kuros-user 是三张 sys_* 关系表与两实体表的唯一权威。
 */
@Entity
@Table(name = "sys_permission")
public class SysPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "permission_code", length = 64, nullable = false, unique = true)
    private String permissionCode;

    @Column(name = "permission_name", length = 128, nullable = false)
    private String permissionName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected SysPermission() {}

    public Integer getId() { return id; }
    public String getPermissionCode() { return permissionCode; }
}
