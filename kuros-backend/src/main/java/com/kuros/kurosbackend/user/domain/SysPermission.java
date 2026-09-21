package com.kuros.kurosbackend.user.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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
