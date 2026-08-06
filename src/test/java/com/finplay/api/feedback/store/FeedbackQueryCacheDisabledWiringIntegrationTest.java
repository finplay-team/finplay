// 대조군 — feedback.query-cache.enabled=false에서 같은 조회가 원본을 매번 부르는지 확인한다 (tasks.md 항목 3·4).
package com.finplay.api.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

// 이 클래스가 "캐시가 없으면 이렇게 된다"의 기준선이다. 짝인 FeedbackQueryCacheEnabledWiringIntegrationTest와
// 시나리오는 완전히 같고(둘 다 상위 클래스의 메서드를 부른다) 기대 숫자만 다르다 — 그 차이가 곧 캐시의 효과다.
//
// 기준선이 없으면 "1회"라는 숫자가 캐시 덕인지, 애초에 그 조회가 한 번만 부르는 것인지 구분되지 않는다.
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

	// 캐시 대상이 아닌 두 목록은 양쪽에서 숫자가 같아야 한다 — 그래야 위 감소가 "캐시한 조각에서만" 일어났다는
	// 것이 확인된다. 여기서 줄어들면 노출 게이트가 캐시에 걸린 것이다.
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
