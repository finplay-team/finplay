package com.finplay.api.domain.education.marketpractice.dto.response;

public record PracticeStepResponse(Integer step, String status, Boolean locked, PracticeEvidenceResponse evidence) {
}
