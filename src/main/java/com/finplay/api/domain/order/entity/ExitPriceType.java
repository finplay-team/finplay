// 손절·익절 기준을 절대 가격으로 받는지 진입가 대비 퍼센트로 받는지 구분하는 열거형 (019 PRICE/PERCENT 정책)
package com.finplay.api.domain.order.entity;

public enum ExitPriceType {
	PRICE,
	PERCENT
}
