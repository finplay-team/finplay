package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeBoundary;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;

public record ObservationEvidenceJudgment(
	boolean closerToBoundary, PracticeBoundary closerBoundary, PracticeEvidenceType evidenceType) {
}
