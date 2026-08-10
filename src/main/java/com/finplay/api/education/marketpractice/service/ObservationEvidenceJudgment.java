// evidence A·B를 함께 반영한 관찰 1건의 최종 판정 결과를 담는 내부 전달 DTO
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeBoundary;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;

public record ObservationEvidenceJudgment(
	boolean closerToBoundary, PracticeBoundary closerBoundary, PracticeEvidenceType evidenceType) {
}
