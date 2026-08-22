// KIS 과거 분봉 수집 시도 하나의 결과를 나타내는 열거형
package com.finplay.api.domain.market.entity;

public enum ImportStatus {
	SUCCESS,
	PARTIAL_SUCCESS,
	FAILED,
	SKIPPED_DUPLICATE
}
