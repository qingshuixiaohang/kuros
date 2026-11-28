package com.kuros.kurosbackend.post;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures SQL emitted by the post list integration test. */
public final class PostListSqlInspector implements StatementInspector {
    private static final List<String> statements = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        statements.add(sql);
        return sql;
    }

    public static void clear() {
        statements.clear();
    }

    public static List<String> statements() {
        return List.copyOf(statements);
    }
}
