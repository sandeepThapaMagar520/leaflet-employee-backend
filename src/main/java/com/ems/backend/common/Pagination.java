package com.ems.backend.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

public final class Pagination {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_FILTER_LENGTH = 100;

    private Pagination() {
    }

    public static Pageable page(
            int page,
            int size,
            String sortBy,
            String sortDirection,
            Set<String> allowedSorts
    ) {
        if (page < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST, "size must be between 1 and " + MAX_SIZE);
        }
        if (sortBy == null || !allowedSorts.contains(sortBy)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported sort property");
        }
        Sort.Direction direction;
        try {
            direction = Sort.Direction.fromString(sortDirection == null ? "desc" : sortDirection);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "sortDir must be asc or desc");
        }
        Sort sort = Sort.by(direction, sortBy).and(Sort.by(direction, "id"));
        return PageRequest.of(page, size, sort);
    }

    public static String filter(String value, String name) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_FILTER_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, name + " must be at most " + MAX_FILTER_LENGTH + " characters");
        }
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
