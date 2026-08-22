// evidence A(경계 접근) 단독 판정 결과를 담는 내부 전달 DTO
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeBoundary;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;

public record BoundaryEvidenceResult(
	boolean closerToBoundary, PracticeBoundary closerBoundary, PracticeEvidenceType evidenceType) {
}
