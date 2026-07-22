package com.ems.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {
    @Test
    void validatesBoundsSortAndDirection() {
        assertThrows(ResponseStatusException.class,
                () -> Pagination.page(-1, 20, "createdAt", "desc", Set.of("createdAt")));
        assertThrows(ResponseStatusException.class,
                () -> Pagination.page(0, 101, "createdAt", "desc", Set.of("createdAt")));
        assertThrows(ResponseStatusException.class,
                () -> Pagination.page(0, 20, "password", "desc", Set.of("createdAt")));
        assertThrows(ResponseStatusException.class,
                () -> Pagination.page(0, 20, "createdAt", "sideways", Set.of("createdAt")));

        var pageable = Pagination.page(2, 20, "createdAt", "desc", Set.of("createdAt"));
        assertEquals(2, pageable.getPageNumber());
        assertEquals(List.of("createdAt: DESC", "id: DESC"),
                pageable.getSort().stream().map(Object::toString).toList());
    }

    @Test
    void standardResponseContainsNavigationAndStableSortMetadata() {
        var pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var response = PageResponse.from(new PageImpl<>(List.of("c", "d"), pageable, 5));

        assertEquals(2, response.numberOfElements());
        assertEquals(5, response.totalElements());
        assertEquals(3, response.totalPages());
        assertFalse(response.first());
        assertFalse(response.last());
        assertEquals(List.of("createdAt,desc", "id,desc"), response.sort());
    }

    @Test
    void rejectsOversizedFilters() {
        assertThrows(ResponseStatusException.class,
                () -> Pagination.filter("x".repeat(101), "search"));
    }
}
