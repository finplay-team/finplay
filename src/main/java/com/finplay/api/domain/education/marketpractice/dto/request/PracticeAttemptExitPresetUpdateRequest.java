package com.finplay.api.domain.education.marketpractice.dto.request;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import jakarta.validation.constraints.NotNull;

public record PracticeAttemptExitPresetUpdateRequest(@NotNull
ExitPreset preset) {
}
