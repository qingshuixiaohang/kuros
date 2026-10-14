package com.kuros.kurosbackend.echoes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 声骸套装（前端审计 H-9：原先是前端 mock.ts 的 echoSets 数组）。
 *
 * 与 {@code GameCharacter} / {@code NewsArticle} 保持同一套形状约定：
 * slug 对外、UUID 主键、enabled 软开关、sort_order 稳定排序。
 *
 * 没有子表：声骸的字段是扁平的（效果/位置/定位各一个字符串），不像资讯正文
 * 需要分段维护，拆表只会多一次 join。
 */
@Entity
@Table(name = "echo_sets")
public class EchoSet {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(length = 80, nullable = false, unique = true)
    private String slug;

    @Column(length = 64, nullable = false)
    private String name;

    /** 两件套效果，如「湮灭伤害提升」。 */
    @Column(length = 120, nullable = false)
    private String effect;

    /** 推荐掉落区域，如「荒石高地 / 无音区」。 */
    @Column(length = 200, nullable = false)
    private String location;

    /** 定位：输出 / 协同 / 治疗（页面上那个三选一 tab）。 */
    @Column(length = 32, nullable = false)
    private String role;

    @Column(length = 500, nullable = false)
    private String description;

    /**
     * 关联攻略的 slug，可为空。
     *
     * 刻意不指向 posts 表的外键：echoes 是静态图鉴内容，posts 是用户生产的
     * 动态内容，两者生命周期不同。存一个跨域硬引用会让 echoes 依赖 posts 的
     * 种子数据，且用户删帖就会让图鉴数据悬空。可空让「未关联攻略」是一等
     * 公民——前端按钮只在有值时才渲染，不会生成打不开的链接。
     */
    @Column(name = "guide_slug", length = 80)
    private String guideSlug;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected EchoSet() {
    }

    public String getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public String getEffect() {
        return effect;
    }

    public String getLocation() {
        return location;
    }

    public String getRole() {
        return role;
    }

    public String getDescription() {
        return description;
    }

    public String getGuideSlug() {
        return guideSlug;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
