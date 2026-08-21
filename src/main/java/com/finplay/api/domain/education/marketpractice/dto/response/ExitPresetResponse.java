// 선택 가능한 손절·익절 프리셋 하나의 식별자와 비율을 내려보내는 응답 DTO (042 EXITPRESET-001)
package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 비율은 퍼센트 수다(3%는 {@code 3}). 클라이언트가 이 값과 기준 가격으로 금액을 병기한다
 * (042 EXITPRESET-006 — 서버가 계산하려면 "고른 수량"을 먼저 받아야 해서 왕복이 하나 늘어난다).
 *
 * <p><b>표시 이름은 담지 않는다.</b> 서버가 쓰지 않는 문구를 열거형에 두지 않기로 042 1번이 정했고,
 * 조심스럽게·보통·느긋하게 같은 문구는 클라이언트가 갖는다.
 */
public record ExitPresetResponse(String preset, BigDecimal stopLossRate, BigDecimal takeProfitRate) {

	// List.copyOf로 감싸는 것은 형식이 아니라 실제 요구다 — 정적 필드를 그대로 돌려주면 호출자가 목록을
	// 바꿔 다른 요청에 영향을 준다(SpotBugs MS_EXPOSE_REP).
	private static final List<ExitPresetResponse> ALL = List.copyOf(Arrays.stream(ExitPreset.values())
		.map(ExitPresetResponse::from)
		.toList());

	public static ExitPresetResponse from(ExitPreset preset) {
		return new ExitPresetResponse(preset.name(), preset.stopLossRate(), preset.takeProfitRate());
	}

	/** 열거형 선언 순서대로 전부. 고정 3개라 매 요청 새로 만들지 않는다. */
	public static List<ExitPresetResponse> all() {
		return ALL;
	}
}
