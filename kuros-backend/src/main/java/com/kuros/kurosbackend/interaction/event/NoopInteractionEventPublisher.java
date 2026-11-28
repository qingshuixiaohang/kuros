package com.kuros.kurosbackend.interaction.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.interaction.async.enabled", havingValue = "false", matchIfMissing = true)
public class NoopInteractionEventPublisher implements InteractionEventPublisher {

    @Override
    public Result publish(InteractionEvent event) {
        return Result.DISABLED;
    }
}
