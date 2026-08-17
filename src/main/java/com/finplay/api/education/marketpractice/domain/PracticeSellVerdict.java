// 튜토리얼 매도 체결가가 교육용 손절선·익절선 대비 어디였는지를 나타내는 열거형
package com.finplay.api.education.marketpractice.domain;

/**
 * 이슈 #421. 어떤 컬럼에도 저장하지 않고 진행 조회 응답에서만 계산해 노출한다 —
 * {@link PracticeBoundary}와 같은 판정 어휘 자리에 둔다.
 */
public enum PracticeSellVerdict {
	/** 매도가 ≥ 익절선. */
	ABOVE_TAKE_PROFIT,
	/** 매도가 ≤ 손절선. */
	BELOW_STOP_LOSS,
	/** 두 선 사이. */
	BETWEEN_LINES
}
