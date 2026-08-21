// 주식 재생세션이 원본 거래일 데이터를 준비 중인지, 준비 완료했는지, 실패했는지를 나타내는 열거형
package com.finplay.api.domain.market.entity;

public enum PreparationStatus {
	PREPARING,
	READY,
	FAILED
}
