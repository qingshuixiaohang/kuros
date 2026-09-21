package com.kuros.kurosbackend.shared.api;

import com.kuros.kurosbackend.shared.api.PageMeta;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, PageMeta meta) {
}
