package com.kuros.kurosbackend.interaction.event;

public interface InteractionEventPublisher {

    enum Result {
        DISABLED, SENT, REJECTED, UNKNOWN
    }

    Result publish(InteractionEvent event);
}
