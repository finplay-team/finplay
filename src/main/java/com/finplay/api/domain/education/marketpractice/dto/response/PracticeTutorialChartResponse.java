// 현재 attempt/run의 가상 시각·결정적 29+1 일봉과 대본 진행 상태를 반환하는 응답 DTO
package com.finplay.api.domain.education.marketpractice.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 뒤 네 필드는 041 6번에서 더했다 — 대본을 쓰지 않는 attempt(생성기 버전 1·완료 replay)에서는
 * {@code scenarioStage}·{@code scenarioProgressing}·{@code causeStatus}가 {@code null}이고
 * {@code revealedEvents}가 빈 목록이다. 클라이언트는 {@code scenarioStage == null}로 "대본 UI 없음"을
 * 판정한다.
 *
 * @param scenarioStage       {@code IDLE_ENTRY|ACT1|ACT2|IDLE_REENTRY|ACT3|ACT4|FINISHED|ORDER_BASICS}.
 *                            대본 내부 구간이 아니라 act 단위다. {@code ORDER_BASICS}는 049 2단계 대본의
 *                            유일한 구간 id를 act 없이 그대로 내보낸 값이다. {@code FINISHED}가 대본 종료
 *                            알림이며 서버에 별도 완료 플래그를 두지 않는다
 * @param scenarioProgressing 현재 구간이 진행 구간인가. {@code false}면 대기 구간이고 보유 여부와 무관하다
 * @param causeStatus         {@code REVEALED|NONE_KNOWN} 둘뿐이다. 미공개 사건이 있는 구간도
 *                            {@code NONE_KNOWN}이라 두 경우를 구분할 수 없다(SCENARIO-015·016)
 * @param revealedEvents      공개 시점이 지난 사건만, 공개 순서다. 시각을 담지 않는다
 * @param priceGuideRange     대본이 안내하는 가격 변동 범위. 사건이 있는 대본이거나 대본을 쓰지 않는
 *                            attempt에서는 {@code null}이다(049 ORDERBASICS-009~011)
 */
public record PracticeTutorialChartResponse(
	Long attemptId,
	long runNumber,
	Long instrumentId,
	LocalDateTime virtualDateTime,
	int secondsPerVirtualMinute,
	List<PracticeTutorialCandleResponse> candles,
	String scenarioStage,
	Boolean scenarioProgressing,
	String causeStatus,
	List<PracticeScenarioEventResponse> revealedEvents,
	PriceGuideRangeResponse priceGuideRange) {
	public PracticeTutorialChartResponse {
		candles = List.copyOf(candles);
		revealedEvents = revealedEvents == null ? List.of() : List.copyOf(revealedEvents);
	}
}
