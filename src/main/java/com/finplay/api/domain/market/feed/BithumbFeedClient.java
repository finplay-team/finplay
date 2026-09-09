package com.finplay.api.domain.market.feed;

public interface BithumbFeedClient {

	void start();

	void stop();

	boolean isConnected();
}
