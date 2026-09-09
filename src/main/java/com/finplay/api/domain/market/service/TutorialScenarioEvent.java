package com.finplay.api.domain.market.service;

public record TutorialScenarioEvent(
	String stageId, int impactStartMinute, int impactMinutes, int revealDelayMinutes, String headline) {

	public int revealMinute() {
		return impactStartMinute + revealDelayMinutes;
	}
}
