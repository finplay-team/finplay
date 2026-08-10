// Redis 연결 장애로 RankingStore가 랭킹 조회를 수행할 수 없을 때 던지는 예외 (이슈 #288)
package com.finplay.api.ranking.store;

// RankingService가 이 예외만 잡아 RankingStatus.UNAVAILABLE 응답으로 바꾼다 — GlobalExceptionHandler의
// Exception 캐치올(500 INTERNAL_ERROR)까지 가지 않게 하는 것이 이 타입의 존재 이유다. 유실(ZSET이 비어
// 있음)과 달리 "ZSET 자체를 읽지 못했다"는 신호라 REBUILDING과 구분해야 한다.
public class RankingStoreUnavailableException extends RuntimeException {

	public RankingStoreUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
