package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionEventPublisher;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InteractionAcceptanceService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final InteractionEventPublisher publisher;
    private final InteractionProjectionService projection;

    public InteractionAcceptanceService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                        InteractionEventPublisher publisher, InteractionProjectionService projection) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.publisher = publisher;
        this.projection = projection;
    }

    public InteractionEventPublisher.Result submit(InteractionEvent event) {
        boolean inserted;
        try {
            jdbc.update("insert into interaction_bindings (operation_id, post_id, user_id, operation_type, delivery_state) values (?, ?, ?, ?, 'READY')",
                    event.eventId(), event.postId(), event.userId(), event.type().name());
            inserted = true;
        } catch (DuplicateKeyException ignored) {
            // 已有绑定在下面的行锁内核对。
            inserted = false;
        }
        boolean newBinding = inserted;
        boolean[] attempted = {false};
        InteractionEventPublisher.Result outcome;
        try {
            outcome = transactions.execute(status -> {
                var rows = jdbc.query("select post_id, user_id, operation_type, delivery_state from interaction_bindings where operation_id = ? for update",
                        (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, event.eventId());
                if (rows.isEmpty()) {
                    return InteractionEventPublisher.Result.REJECTED;
                }
                String[] binding = rows.getFirst();
                if (!event.postId().equals(binding[0]) || !event.userId().equals(binding[1]) || !event.type().name().equals(binding[2])) {
                    throw new AuthRequestException("INTERACTION_OPERATION_CONFLICT", "操作编号已绑定其他互动");
                }
                if ("SENT".equals(binding[3])) return InteractionEventPublisher.Result.SENT;
                attempted[0] = true;
                InteractionEventPublisher.Result result;
                try {
                    result = publisher.publish(event);
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(InteractionAcceptanceService.class)
                            .warn("互动投递结果未确认: operationId={}", event.eventId(), failure);
                    result = InteractionEventPublisher.Result.UNKNOWN;
                }
                if (result == InteractionEventPublisher.Result.REJECTED && newBinding && "READY".equals(binding[3])) {
                    jdbc.update("delete from interaction_bindings where operation_id = ?", event.eventId());
                } else if (result == InteractionEventPublisher.Result.REJECTED) {
                    return InteractionEventPublisher.Result.UNKNOWN;
                } else if (result == InteractionEventPublisher.Result.SENT || result == InteractionEventPublisher.Result.UNKNOWN) {
                    jdbc.update("update interaction_bindings set delivery_state = ? where operation_id = ?",
                            result.name(), event.eventId());
                }
                return result;
            });
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure) {
            if (attempted[0]) {
                throw new com.kuros.kurosbackend.shared.exception.ServiceUnavailableException(
                        "INTERACTION_DELIVERY_UNKNOWN", "互动投递结果暂未确认");
            }
            throw failure;
        }
        if (outcome == InteractionEventPublisher.Result.DISABLED) {
            projection.apply(event);
            jdbc.update("update interaction_bindings set delivery_state = 'SENT' where operation_id = ?", event.eventId());
            return InteractionEventPublisher.Result.SENT;
        }
        return outcome;
    }
}
