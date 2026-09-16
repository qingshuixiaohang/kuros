package com.kuros.kurosbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "content_tags")
public class ContentTag {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(length = 64, nullable = false, unique = true)
    private String name;

    protected ContentTag() {
    }

    public ContentTag(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
