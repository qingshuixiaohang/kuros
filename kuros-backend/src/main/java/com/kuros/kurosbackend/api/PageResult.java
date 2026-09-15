package com.kuros.kurosbackend.api;

import java.util.List;

public record PageResult<T>(List<T> items, PageMeta meta) {
}
