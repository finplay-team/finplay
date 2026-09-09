package com.finplay.api.domain.order.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record ExitPlanPracticeOriginDto(Long attemptId, long runNumber, BigDecimal baselinePrice) {

	public ExitPlanPracticeOriginDto {
		if (attemptId == null || runNumber <= 0) {
			throw new IllegalArgumentException("튜토리얼 자동 예약은 attemptId와 양의 runNumber가 필요합니다.");
		}
		if (baselinePrice == null) {
			throw new IllegalArgumentException("튜토리얼 자동 예약은 대본 기준가(baselinePrice)가 필요합니다.");
		}
	}

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
