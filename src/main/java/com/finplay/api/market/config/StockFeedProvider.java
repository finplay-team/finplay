// 주식 시세를 어디서 받아오는지(KRX 과거 데이터 재생·KIS 실시간 체결)를 나타내는 설정값
package com.finplay.api.market.config;

public enum StockFeedProvider {
	KIS_REALTIME,
	KRX_REPLAY
}
