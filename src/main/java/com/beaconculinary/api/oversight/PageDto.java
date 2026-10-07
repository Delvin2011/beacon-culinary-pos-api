package com.beaconculinary.api.oversight;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** A stable JSON page envelope (Spring Data's PageImpl serialization isn't a stable contract). */
public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <S, T> PageDto<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageDto<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
