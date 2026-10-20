package com.kuros.kurosbackend.echoes.repository;

import com.kuros.kurosbackend.echoes.domain.EchoSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EchoSetRepository extends JpaRepository<EchoSet, String> {

    /**
     * 列表查询：只取 enabled，按 sort_order、name、id 稳定排序，
     * 保证分页游走时顺序确定。
     */
    Page<EchoSet> findByEnabledTrue(Pageable pageable);

    /** 按定位过滤（页面上「套装 / 单体声骸 / 掉落位置」之外的 role 维度）。 */
    Page<EchoSet> findByEnabledTrueAndRole(String role, Pageable pageable);

    /** 详情查询：未知或未上线 slug 都拿不到——404 契约由上层转成业务错误码。 */
    Optional<EchoSet> findBySlugAndEnabledTrue(String slug);
}
