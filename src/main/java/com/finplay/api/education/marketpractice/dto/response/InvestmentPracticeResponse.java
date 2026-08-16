// 실제 favorite·intention·buyTrade·holding·관찰·복기 증거로 계산한 3단계 투자 실습 진행 상태 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record InvestmentPracticeResponse(
	String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps,
	LocalDateTime completedAt, Long rewardAmount, PracticeAttemptResponse attempt) {

	public InvestmentPracticeResponse {
		// steps는 List 필드라 방어적 복사 없이는 SpotBugs EI_EXPOSE_REP/REP2로 잡힌다(agent-mistakes.md 2026-07-29).
		steps = List.copyOf(steps);
	}
}
