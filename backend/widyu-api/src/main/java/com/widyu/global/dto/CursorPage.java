package com.widyu.global.dto;

import java.util.List;
import java.util.function.Function;

public record CursorPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasNext
) {

    public static <E, R> CursorPage<R> from(
            List<E> content,
            int size,
            Function<E, R> mapper,
            Function<E, String> cursorExtractor
    ) {
        boolean hasNext = content.size() > size;
        List<E> pageContent = content;
        if (hasNext) {
            pageContent = content.subList(0, size);
        }

        String next = null;
        if (hasNext && !pageContent.isEmpty()) {
            next = cursorExtractor.apply(pageContent.getLast());
        }

        return new CursorPage<>(
                pageContent.stream().map(mapper).toList(),
                next,
                hasNext
        );
    }
}
