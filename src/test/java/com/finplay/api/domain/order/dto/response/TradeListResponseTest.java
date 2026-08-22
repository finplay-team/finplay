// TradeListResponse의 컬렉션 방어적 복사(List.copyOf)가 실제로 동작하는지 검증하는 단위 테스트다.
package com.finplay.api.domain.order.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradeListResponseTest {

	@Test
	void mutatingOriginalListAfterConstructionDoesNotAffectResponseContent() {
		List<TradeListItemResponse> original = new ArrayList<>();
		original.add(sampleItem(1L));

		TradeListResponse response = TradeListResponse.of(original, "cursor-1", true);

		original.add(sampleItem(2L));
		original.clear();

		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).tradeId()).isEqualTo(1L);
	}

	@Test
	void contentIsImmutableAndRejectsModification() {
		TradeListResponse response = TradeListResponse.of(List.of(sampleItem(1L)), null, false);

		assertThatThrownBy(() -> response.content().add(sampleItem(2L)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ofExposesNextCursorAndHasNextAsGiven() {
		TradeListResponse response = TradeListResponse.of(List.of(sampleItem(1L)), "2026-07-18T10:00:00_42", true);

		assertThat(response.nextCursor()).isEqualTo("2026-07-18T10:00:00_42");
		assertThat(response.hasNext()).isTrue();
	}

	@Test
	void ofWithEmptyContentReturnsEmptyListNullCursorAndNoNextPage() {
		TradeListResponse response = TradeListResponse.of(List.of(), null, false);

		assertThat(response.content()).isEmpty();
		assertThat(response.nextCursor()).isNull();
		assertThat(response.hasNext()).isFalse();
	}

	private static TradeListItemResponse sampleItem(long tradeId) {
		return new TradeListItemResponse(
			tradeId,
			999L,
			"BUY",
			BigDecimal.valueOf(75000),
			BigDecimal.valueOf(10),
			750_000L,
			100L,
			null,
			LocalDateTime.of(2026, 7, 18, 10, 0, 0));
	}
}
