// 빗썸 WebSocket 코인 실시간 시세 피드의 연결 생명주기 공통 계약 (구현체: 실제 WebSocket 클라이언트·FakeBithumbFeedClient)
package com.finplay.api.domain.market.feed;

public interface BithumbFeedClient {

	void start();

	void stop();

	boolean isConnected();
}
