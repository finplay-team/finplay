// 변동 카드들의 근거 기사를 한 번에 읽어 카드 id로 묶는 조회 전용 컴포넌트
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link PriceMoveQueryService}·{@link PostSellFeedbackReader}·{@link CryptoPostSellFeedbackReader}가 같은
 * 본문을 각자 갖고 있던 것을 모았다 — {@code docs/conventions/code.md}의 "공통화는 <b>세 번째</b> 중복이 보이고
 * 책임이 명확할 때만 검토한다"가 정확히 이 시점을 트리거로 잡는다 (PR #281 리뷰).
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다.</b> 세 호출부가 전부 자기 트랜잭션 안에서 부르므로 여기에
 * 붙여도 기본 전파({@code REQUIRED})라 물리 경계가 늘지는 않는다 — 문제는 <b>호출부가 트랜잭션 없이 불러도
 * 조용히 도는 상태가 된다</b>는 것이다. "경계는 호출부가 갖는다"는 지금 계약이 그때 약해진다 (PR #281 리뷰).
 */
@Component
@RequiredArgsConstructor
public class PriceMoveSourceLoader {

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	/**
	 * 카드마다 따로 묻지 않고 한 번에 읽어 카드 id로 묶는다.
	 *
	 * <p><b>정렬 규칙은 이 메서드가 아니라 리포지토리 질의({@code publishedAt} 내림차순 + {@code id} 오름차순)에
	 * 있다</b>(계약). 여기서는 {@code LinkedHashMap}·{@code ArrayList}가 그 순서를 삽입 순서로 보존할 뿐이라
	 * 규칙이 두 곳으로 갈리지 않는다.
	 */
	public Map<Long, List<NewsItem>> findSources(List<PriceMoveEvent> events) {
		List<Long> eventIds = events.stream().map(PriceMoveEvent::getId).toList();
		Map<Long, List<NewsItem>> sourcesByEventId = new LinkedHashMap<>();
		for (PriceMoveEventSource source : priceMoveEventSourceRepository
			.findAllByPriceMoveEventIdIn(eventIds)) {
			sourcesByEventId
				.computeIfAbsent(source.getPriceMoveEvent().getId(), id -> new ArrayList<>())
				.add(NewsItem.from(source.getMarketNewsItem()));
		}
		return sourcesByEventId;
	}
}
