package com.kuros.kurosbackend.interaction.event;

public enum InteractionType {

    LIKE(InteractionKind.LIKE),
    UNLIKE(InteractionKind.LIKE),
    FAVORITE(InteractionKind.FAVORITE),
    UNFAVORITE(InteractionKind.FAVORITE);

    private final InteractionKind kind;
    InteractionType(InteractionKind kind) {
        this.kind = kind;
    }

    public InteractionKind kind() {
        return kind;
    }

}
