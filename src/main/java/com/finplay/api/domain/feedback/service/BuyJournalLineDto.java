package com.finplay.api.domain.feedback.service;

import java.time.LocalDateTime;

public record BuyJournalLineDto(LocalDateTime buyAt, String content) {
}
