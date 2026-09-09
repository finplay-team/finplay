package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "feedback.query-cache.enabled=false")
class FeedbackQueryCacheDisabledWiringIntegrationTest extends FeedbackQueryCacheWiringSupport {

	@Test
	@DisplayName("[대조군] 주식 요약 조회 3번이면 요약 행을 3번 읽는다")
	void readsTheStockSummaryRowOncePerQuery() {
		assertThat(stockSummaryRowCallsAcrossThreeQueries()).isEqualTo(3);
	}

	@Test
	@DisplayName("[대조군] 코인 요약 조회 3번이면 요약 행을 3번 읽는다")
	void readsTheCryptoSummaryRowOncePerQuery() {
		assertThat(cryptoSummaryRowCallsAcrossThreeQueries()).isEqualTo(3);
	}

	@Test
	@DisplayName("[대조군] 주식 브리핑 두 번째 조회도 DB를 3건(텍스트 1 + items 2) 부른다")
	void hitsTheDatabaseAgainOnTheSecondStockBriefingQuery() {
		assertThat(stockBriefingDbCallsOnASecondQuery()).isEqualTo(3);
	}

	@Test
	@DisplayName("[대조군] 코인 브리핑 두 번째 조회도 브리핑 텍스트 행을 다시 읽는다")
	void readsTheCryptoBriefingRowAgainOnTheSecondQuery() {
		assertThat(cryptoBriefingTextCallsOnASecondQuery()).isEqualTo(1);
	}

	@Test
	@DisplayName("[대조군] 주식 요약의 items 수집은 조회마다 3번이다")
	void collectsStockSummaryItemsOncePerQuery() {
		assertThat(stockSummaryItemCallsAcrossThreeQueries()).isEqualTo(3);
	}

	@Test
	@DisplayName("[대조군] 코인 브리핑의 items 수집은 두 번째 조회에서도 1번이다")
	void collectsCryptoBriefingItemsOnTheSecondQuery() {
		assertThat(cryptoBriefingItemCallsOnASecondQuery()).isEqualTo(1);
	}
}
