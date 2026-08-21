// 저장된 실습 3단계 자유 복기 1건을 반환하는 응답 DTO
package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import java.time.LocalDateTime;

public record PracticeHoldingReflectionResponse(
	Long reflectionId,
	Long holdingId,
	String prompt,
	String answer,
	LocalDateTime createdAt,
	boolean rewardGranted) {

	// ai/specs/039-tutorial-flow-redesign TUTORIAL-FLOW-008: 사용자가 손절·익절을 직접 계획하지 않고 서버가
	// 체결가 기준 -3%·+5%를 자동 고정하며, 복기는 전량 매도를 마친 4단계(031 SANDBOX-006)에서만 열린다.
	public static final String PROMPT = "방금 판 이유가 무엇인가요? 화면에 표시된 손절선·익절선과 비교해서, 지금 돌아보면 그 판단이 어땠는지 한 줄로 적어 보세요.";

	public static PracticeHoldingReflectionResponse from(PracticeMarketReflection reflection, boolean rewardGranted) {
		return new PracticeHoldingReflectionResponse(
			reflection.getId(),
			reflection.getHolding().getId(),
			PROMPT,
			reflection.getAnswer(),
			reflection.getCreatedAt(),
			rewardGranted);
	}

	// ai/specs/040-tutorial-restart-after-completion TUTORIAL-RESTART-005/007: 재완료는
	// practice_market_reflections에 새 행을 만들지 않으므로 reflectionId가 없고, 사용자가 입력한 answer도
	// 영속되지 않는다(응답에는 evidence 검증을 통과한 이번 요청 값을 그대로 되돌려줄 뿐).
	public static PracticeHoldingReflectionResponse ofRecompletion(
		Long holdingId, String answer, LocalDateTime completedAt) {
		return new PracticeHoldingReflectionResponse(null, holdingId, PROMPT, answer, completedAt, false);
	}
}
