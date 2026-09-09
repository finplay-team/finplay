package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public record ExitPresetResponse(String preset, BigDecimal stopLossRate, BigDecimal takeProfitRate) {

	private static final List<ExitPresetResponse> ALL = List.copyOf(Arrays.stream(ExitPreset.values())
		.map(ExitPresetResponse::from)
		.toList());

	public static ExitPresetResponse from(ExitPreset preset) {
		return new ExitPresetResponse(preset.name(), preset.stopLossRate(), preset.takeProfitRate());
	}

	public static List<ExitPresetResponse> all() {
		return ALL;
	}
}
