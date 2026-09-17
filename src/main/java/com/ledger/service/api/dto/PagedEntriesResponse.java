package com.ledger.service.api.dto;

import com.ledger.service.domain.Entry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "A page of entries for an account, ordered by created_at then id")
public record PagedEntriesResponse(
        List<EntryResponse> entries,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static PagedEntriesResponse from(Page<Entry> page) {
        return new PagedEntriesResponse(
                page.getContent().stream().map(EntryResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
