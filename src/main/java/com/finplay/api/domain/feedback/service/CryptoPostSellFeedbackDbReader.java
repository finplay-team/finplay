// 코인 매도 회고에서 원장·집계 조회만 맡는 컴포넌트 — REST 호출 구간을 사이에 두고 트랜잭션 둘로 나뉜다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Trade;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec §FEED-012 결정 5의 <b>트랜잭션 B·C</b>다 — 코인 회고가 DB에서 읽어야 하는 두 덩어리를 각자 짧은 읽기
 * 트랜잭션으로 끊는다.
 *
 * <pre>
 * (트랜잭션 B) findHeldPriceMoves → (트랜잭션 없음) 캔들 REST 4종 → (트랜잭션 C) buildPeerComparison
 * </pre>
 *
 * <p><b>왜 {@link CryptoPostSellFeedbackReader}에서 떼어 냈는가.</b> 같은 클래스에 두면 두 조회가 <b>자기호출</b>이
 * 되어 프록시를 타지 않아 애노테이션이 무효가 되고, 그렇다고 조립 메서드에 애노테이션을 붙이면 그 사이의 빗썸
 * REST 호출이 다시 트랜잭션 안에 갇힌다(이슈 #282의 원래 버그). 호출부인 조립 리더가 <b>밖에서</b> 이 빈의 메서드
 * 둘을 각각 부르기 때문에 경계가 실제로 둘로 갈린다.
 *
 * <p><b>{@code priceMoves}가 REST보다 먼저인 이유는 순서 취향이 아니다</b> — {@code scenarioAtFirstMoveAfterBuy}가
 * {@code priceMoves.get(0).windowEnd()}를 캔들 조회 인자로 쓴다. 반대로 {@code peerComparison}은 REST 결과와
 * 무관하지만 뒤로 미룬다 — {@code priceMoves}만 있으면 계산되므로 B에 합칠 이유가 없고, 합치면 트랜잭션이 REST
 * 구간을 가로질러 열려 있게 된다.
 */
@Component
@RequiredArgsConstructor
class CryptoPostSellFeedbackDbReader {

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveSourceLoader priceMoveSourceLoader;

	private final PriceMovePeerStatRepository priceMovePeerStatRepository;

	private final FeedbackCryptoProperties cryptoProperties;

	/**
	 * 보유 구간에 걸친 코인 변동 카드 (§C-9 · §C-5). <b>트랜잭션 B</b>다.
	 *
	 * <p><b>노출 게이트가 없다</b> — 코인 카드는 {@code reveal_time}이 {@code NULL}이라(실시간이라 스포일러가
	 * 성립하지 않는다) 주식이 쓰는 상한 계산이 여기에 나타나지 않는다. 카드를 찾는 축도 다르다: 주식은
	 * {@code (origin_trade_date, window_end)}이고 코인은 {@code occurred_at} 하나다.
	 *
	 * <p>구간 경계는 <b>분으로 내려</b> 넘긴다 — 체결 시각에는 소수 초가 붙어 있어 그대로 넘기면 <b>매수 분과
	 * 같은 분에 탐지된 카드가 하한 밖으로 밀린다.</b> 카드 쪽도 분 경계라 양쪽이 같은 축에서 비교된다
	 * ({@code CryptoPriceMoveWatcher}가 {@code occurred_at}을 분으로 내려 저장한다).
	 *
	 * <p><b>2026-08-17 정정 (이슈 #407).</b> 그전까지 이 자리는 "{@code occurred_at}은 정시인데"라고 적었지만
	 * 사실이 아니었다 — 감시 크론이 매 분 <b>30초</b>에 돌고 컬럼이 {@code DATETIME(6)}이라 저장된 값은
	 * {@code HH:mm:30.xxxxxx}였다. 그래서 상한이 {@code onMinuteBoundary(sellAt)}인 이 질의에서 <b>매도와 같은
	 * 분에 탐지된 카드가 오히려 빠지고 있었다.</b> 저장 쪽을 분 경계로 맞추면서 이 전제가 비로소 참이 됐다.
	 */
	@Transactional(readOnly = true)
	List<HeldPriceMoveItem> findHeldPriceMoves(Trade trade, LocalDateTime buyAt, LocalDateTime sellAt) {
		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
				trade.getInstrument().getId(),
				Market.CRYPTO,
				PostSellArithmetic.onMinuteBoundary(buyAt),
				PostSellArithmetic.onMinuteBoundary(sellAt));
		if (events.isEmpty()) {
			return List.of();
		}

		Map<Long, List<NewsItem>> sourcesByEventId = priceMoveSourceLoader.findSources(events);
		return events.stream()
			.map(event -> toHeldPriceMoveItem(
				event, buyAt, sellAt, sourcesByEventId.getOrDefault(event.getId(), List.of())))
			.toList();
	}

	/**
	 * 카드 1건을 응답 항목으로 옮긴다.
	 *
	 * <p><b>{@code windowStart}는 저장 컬럼이 아니라 파생값이다</b>(§C-9) — 코인 카드는 {@code occurred_at}
	 * 하나만 저장하고 {@code window_start}/{@code window_end}가 {@code NULL}이므로, 목록 조회
	 * ({@code PriceMoveItem.ofCrypto})와 <b>같은 규칙</b>으로 {@code occurredAt − rolling-window-minutes}를 쓴다.
	 * 두 곳이 다른 규칙을 쓰면 같은 카드가 화면마다 다른 구간으로 보인다.
	 */
	private HeldPriceMoveItem toHeldPriceMoveItem(
		PriceMoveEvent event, LocalDateTime buyAt, LocalDateTime sellAt, List<NewsItem> sources) {
		LocalDateTime windowEnd = event.getOccurredAt();
		return new HeldPriceMoveItem(
			event.getId(),
			windowEnd.minusMinutes(cryptoProperties.rollingWindowMinutes()),
			windowEnd,
			event.getChangeRate(),
			PostSellArithmetic.minutesBetween(buyAt, windowEnd),
			PostSellArithmetic.minutesBetween(windowEnd, sellAt),
			event.getNarrative(),
			sources);
	}

	/**
	 * 집단 비교 (§C-4 · §FEED-012 결정 3). <b>트랜잭션 C</b>다. 판정 순서는 {@code NO_EVENT}가 1순위다 — 기준
	 * 카드가 없으면 확정 집계 행이 애초에 생기지 않으므로, 행 존재만 보면 그 흔한 경우가 영원히 {@code NOT_YET}이
	 * 된다.
	 *
	 * <p><b>조회 키가 주식과 다르다.</b> 주식은 그 체결의 <b>서비스 날짜</b> 행을 보는데 코인 체결에는 서비스
	 * 날짜가 없다({@code stockReplaySession}이 {@code null}이다) — 그대로 넘기면 <b>행을 영원히 못 찾아
	 * {@code NOT_YET}으로 굳는다.</b> 코인은 재생이 없어 카드와 날짜가 1:1이므로 <b>그 카드 {@code occurred_at}의
	 * KST 날짜</b>를 쓴다. 배치가 저장하는 값과 같은 규칙이어야 한다.
	 */
	@Transactional(readOnly = true)
	PeerComparison buildPeerComparison(List<HeldPriceMoveItem> priceMoves) {
		if (priceMoves.isEmpty()) {
			return PostSellArithmetic.peerComparisonNoEvent();
		}

		HeldPriceMoveItem card = priceMoves.get(0);
		// yourMinutesToSell = 매도시각 − 카드 시각. card.minutesBeforeSell()이 이미 같은 계산이라 다시 재지 않는다.
		Integer yourMinutesToSell = card.minutesBeforeSell();
		return priceMovePeerStatRepository
			.findByPriceMoveEventIdAndServiceDate(card.id(), card.windowEnd().toLocalDate())
			.map(stat -> PostSellArithmetic.toPeerComparison(stat, card.id(), yourMinutesToSell))
			.orElseGet(PostSellArithmetic::peerComparisonNotYet);
	}

}
