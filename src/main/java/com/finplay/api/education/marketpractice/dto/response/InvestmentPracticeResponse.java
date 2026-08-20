// 실제 favorite·intention·buyTrade·holding·관찰·복기 증거로 계산한 3단계 투자 실습 진행 상태 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 뒤 세 필드는 041 6번에서 더했다(SCENARIO-019b·020·021).
 *
 * @param revealedEvents  그 실행에서 <b>공개된</b> 사건만, 공개 순서다. 완료 시점에도 미공개 사건은
 *                        노출하지 않는다(SCENARIO-020). 시각을 담지 않는 이유는
 *                        {@code PracticeScenarioEventResponse}에 적었다
 * @param priceAfterSell  "그때 팔지 않았다면"의 기준 가격. <b>진행 중에는 현재 대본가</b>이고
 *                        <b>완료 응답에서는 대본의 마지막 진행 구간 끝 가격</b>이라, 완료 대조는 사용자가
 *                        실제로 그 지점까지 진행했는지와 무관하다(SCENARIO-021). 대본을 쓰지 않는 실행은
 *                        {@code null}
 * @param entries         진입별 대조 배열. 매수 전이거나 attempt가 없는 legacy 경로는 빈 목록이다
 *
 * @param tutorialStageProgress 이슈 #503에서 더했다. 튜토리얼 5단계(종목 고르기 → 시장가 → 지정가 →
 *                        프리셋 → 복기) 중 <b>주문 방법·프리셋 단계를 그 실행에서 실제로 마쳤는지</b>를
 *                        서버가 체결 원장으로 판정해 내려보낸다. 화면이 스스로 세면 새로고침에 날아간다.
 *                        판정하지 않는 경로(attempt 없음, 종목 미선택)는 세 값 모두 {@code false}이며
 *                        <b>{@code null}이 되지 않는다</b> — 클라이언트가 null 분기를 갖지 않게 한다
 */
public record InvestmentPracticeResponse(
	String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps,
	LocalDateTime completedAt, Long rewardAmount, PracticeAttemptResponse attempt,
	List<PracticeScenarioEventResponse> revealedEvents, BigDecimal priceAfterSell,
	List<PracticeEntryResponse> entries, PracticeStageProgressResponse tutorialStageProgress) {

	public InvestmentPracticeResponse {
		// steps는 List 필드라 방어적 복사 없이는 SpotBugs EI_EXPOSE_REP/REP2로 잡힌다(agent-mistakes.md 2026-07-29).
		steps = List.copyOf(steps);
		revealedEvents = revealedEvents == null ? List.of() : List.copyOf(revealedEvents);
		entries = entries == null ? List.of() : List.copyOf(entries);
		tutorialStageProgress = tutorialStageProgress == null
			? PracticeStageProgressResponse.none()
			: tutorialStageProgress;
	}

	/**
	 * 대본·진입 대조가 없는 응답(legacy chain, favorite만, 미착수, attempt 없는 완료 기록)용.
	 *
	 * <p><b>여기서는 기본값을 두어도 안전하다.</b> 세 값 모두 "이 경로에는 없다"가 곧 정답이라
	 * 빈 목록·{@code null}이 사용자에게 잘못된 것을 보여 주지 않는다 — 잘못 {@code false}로 내리면 바꿀 수
	 * 없는 컨트롤을 열어 주던 {@code exitPresetLocked}(042 EXITPRESET-003)와 성격이 다르다.
	 */
	public InvestmentPracticeResponse(
		String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps,
		LocalDateTime completedAt, Long rewardAmount, PracticeAttemptResponse attempt) {
		this(
			tutorialKey, status, currentStep, steps, completedAt, rewardAmount, attempt, List.of(), null, List.of(),
			PracticeStageProgressResponse.none());
	}
}
