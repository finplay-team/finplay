package com.finplay.api.domain.journal.service;

import java.time.LocalDateTime;

public record JournalContentDto(Long tradeId, String content, LocalDateTime updatedAt) {
}
