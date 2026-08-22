// 튜토리얼 자동 예약(042)이 엔진에 넘기는 attempt 귀속과 대본 기준가 snapshot
package com.finplay.api.domain.order.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 이 record가 non-null이면 튜토리얼 자동 예약 경로다. 일반 경로·교육 경로와 셋 다 배타적이며, 엔진은
 * 귀속 값을 해석하지 않고 plan에 그대로 저장한다.
 *
 * <p><b>{@code baselinePrice}를 호출부가 주입한다.</b> 엔진 기본 경로는
 * {@code priceQueryService.getPrice}로 baseline을 확정하는데, 그것은 튜토리얼 샘플 종목이면 031이 만든
 * 주기 180초·진폭 ±3%의 <b>벽시계 사인파</b>로 분기한다. 대본과 아무 관계 없는 값이
 * {@code baseline_price}에 영속되고 039 TUTORIAL-FLOW-011("화면 현재가·체결 판정·tick 정산이 같은 값")과
 * 어긋난다. 매수가 실패하지는 않지만 거짓 데이터가 남는다(042 plan §자동 예약 생성).
 */
public record ExitPlanPracticeOriginDto(Long attemptId, long runNumber, BigDecimal baselinePrice) {

	public ExitPlanPracticeOriginDto {
		if (attemptId == null || runNumber <= 0) {
			throw new IllegalArgumentException("튜토리얼 자동 예약은 attemptId와 양의 runNumber가 필요합니다.");
		}
		if (baselinePrice == null) {
			throw new IllegalArgumentException("튜토리얼 자동 예약은 대본 기준가(baselinePrice)가 필요합니다.");
		}
	}

	/**
	 * {@code exit_plans.request_hash}에 넣을 감사용 값. <b>멱등키가 아니다</b> — 그 컬럼에 UNIQUE가 없고
	 * 엔진도 읽지 않는다. 실제 중복 방어는 엔진 4단계의 {@code validateNoPendingPlan}과, attempt를 잠근
	 * 트랜잭션 안에서 진입 순번을 산출하는 직렬화다.
	 *
	 * <p>자동 예약(042)과 사용자 주도 예약(052 EXITFREE-020)이 <b>같은 값</b>을 쓴다 — 같은 진입을 가리키는
	 * 두 경로가 서로 다른 감사 키를 남기면 원장에서 같은 진입의 예약을 되짚을 수 없다. 두 경로는 애초에
	 * 같은 실행에 공존하지 않으므로(대본 식별자로 갈린다) 값이 충돌하지도 않는다.
	 */
	public static String auditRequestHash(Long attemptId, long runNumber, int entrySequence) {
		String source = attemptId + ":" + runNumber + ":" + entrySequence;
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", ex);
		}
	}
}
