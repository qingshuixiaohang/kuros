package com.kuros.kurosbackend.post.repository;

import com.kuros.kurosbackend.post.domain.PostStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/** Keep these predicates equivalent to findVisiblePosts while omitting absent filters. */
public class CommunityPostCountRepositoryImpl implements CommunityPostCountRepository {
    private final EntityManager entityManager;

    public CommunityPostCountRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public long countVisiblePosts(PostStatus status, String category, String tag, String keyword) {
        StringBuilder jpql = new StringBuilder("select count(p) from CommunityPost p where p.status = :status");
        if (category != null) {
            jpql.append(" and p.category = :category");
        }
        if (tag != null) {
            jpql.append(" and exists (select t from p.tags t where t.name = :tag)");
        }
        if (keyword != null) {
            jpql.append(" and (lower(p.title) like lower(concat('%', :keyword, '%'))")
                    .append(" or lower(p.excerpt) like lower(concat('%', :keyword, '%')))");
        }
        TypedQuery<Long> query = entityManager.createQuery(jpql.toString(), Long.class);
        query.setParameter("status", status);
        if (category != null) {
            query.setParameter("category", category);
        }
        if (tag != null) {
            query.setParameter("tag", tag);
        }
        if (keyword != null) {
            query.setParameter("keyword", keyword);
        }
        return query.getSingleResult();
    }
}
