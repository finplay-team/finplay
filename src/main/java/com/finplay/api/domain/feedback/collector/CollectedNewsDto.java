package com.finplay.api.domain.feedback.collector;

import java.time.LocalDateTime;

public record CollectedNewsDto(String title, String publisher, String url, LocalDateTime publishedAt) {
}
