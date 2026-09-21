package com.kuros.kurosbackend.shared.api;

import com.kuros.kurosbackend.shared.api.PageMeta;

import java.util.List;

public record PageResult<T>(List<T> items, PageMeta meta) {
}
