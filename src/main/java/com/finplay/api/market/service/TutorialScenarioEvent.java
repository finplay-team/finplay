// 대본에 사전 확정된 가상 사건 하나와 그 영향 구간·공개 지연을 담는 값 객체
package com.finplay.api.market.service;

public record TutorialScenarioEvent(
	String stageId, int impactStartMinute, int impactMinutes, int revealDelayMinutes, String headline) {

	// 공개 시점은 영향 시작보다 뒤다 — 원인은 가격이 움직인 뒤에만 열린다(041 SCENARIO-015).
	public int revealMinute() {
		return impactStartMinute + revealDelayMinutes;
	}
}
