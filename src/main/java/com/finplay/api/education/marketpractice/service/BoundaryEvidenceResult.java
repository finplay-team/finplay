// evidence A(경계 접근) 단독 판정 결과를 담는 내부 전달 DTO
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeBoundary;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;

public record BoundaryEvidenceResult(
	boolean closerToBoundary, PracticeBoundary closerBoundary, PracticeEvidenceType evidenceType) {
}
