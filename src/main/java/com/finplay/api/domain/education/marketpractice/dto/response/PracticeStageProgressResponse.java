package com.finplay.api.domain.education.marketpractice.dto.response;

public record PracticeStageProgressResponse(
	boolean marketBuySellCompleted,
	boolean limitBuySellCompleted,
	boolean exitPresetSelected) {

	private static final PracticeStageProgressResponse NONE = new PracticeStageProgressResponse(false, false, false);

	public static PracticeStageProgressResponse none() {
		return NONE;
	}
}
