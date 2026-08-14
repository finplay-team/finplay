// 튜토리얼 attempt의 종목 선택부터 완료까지의 영속 상태를 정의하는 열거형
package com.finplay.api.education.marketpractice.domain;

public enum PracticeAttemptStatus {
	SELECTING_INSTRUMENT,
	IN_PROGRESS,
	EXPIRED,
	COMPLETED
}
