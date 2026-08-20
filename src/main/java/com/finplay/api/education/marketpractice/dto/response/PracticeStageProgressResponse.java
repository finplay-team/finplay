// 튜토리얼 5단계 중 주문 방법·프리셋 단계를 그 실행에서 실제로 마쳤는지 담는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

/**
 * 이슈 #503. 화면이 "이 사용자가 시장가로 사고팔았나", "지정가까지 해봤나"를 스스로 세는 것을 그만두게
 * 한다 — 스스로 센 값은 새로고침에 날아가지만 체결 원장은 남는다.
 *
 * <p><b>판정 범위는 현재 attempt의 현재 실행 세대다.</b> 재시작하면 셋 다 {@code false}로 돌아간다.
 * attempt가 없는 경로(legacy chain, 즐겨찾기만, 미착수)도 셋 다 {@code false}다.
 *
 * <p><b>기존 {@code steps[]}와 섞지 않는다.</b> 그 배열은 026의 1~4단계(즐겨찾기→매수→관찰→매도/복기)
 * 의미가 이미 박혀 있어, 같은 배열에 "주문 유형을 배웠는가"를 넣으면 한 배열이 두 의미를 갖는다.
 *
 * @param marketBuySellCompleted 이 실행에서 시장가로 <b>사고 판</b> 것까지 마쳤는가. <b>손절·익절 예약이
 *                               발동시킨 매도는 세지 않는다</b> — 그 매도도 원장에는 {@code MARKET}으로
 *                               남지만 사용자가 낸 주문이 아니라, 세면 프리셋에 청산당하기만 한 사용자가
 *                               이 단계를 통과한 것으로 표시된다
 * @param limitBuySellCompleted  이 실행에서 지정가로 사고 판 것까지 마쳤는가. 튜토리얼 지정가 매수는
 *                               {@code POST /api/education/practice/limit-orders}, 매도는
 *                               {@code POST /api/orders/limit}으로 들어온다
 * @param exitPresetApplied      이 실행에서 손절·익절 프리셋을 <b>고르고 그 프리셋으로 진입까지</b>
 *                               했는가. 고르기만 하고 아직 사지 않았으면 {@code false}다 — 프리셋은
 *                               다음 진입에만 적용되므로 "배웠다"의 증거는 그 진입의 기준선이다
 */
public record PracticeStageProgressResponse(
	boolean marketBuySellCompleted,
	boolean limitBuySellCompleted,
	boolean exitPresetApplied) {

	private static final PracticeStageProgressResponse NONE = new PracticeStageProgressResponse(false, false, false);

	/** 판정할 실행이 없는 경로(attempt 없음, 종목 미선택)의 값. */
	public static PracticeStageProgressResponse none() {
		return NONE;
	}
}
