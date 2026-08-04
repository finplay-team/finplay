// 본인 매도 체결 1건의 매도 직후 피드백을 조립하는 조회 서비스 — 원장 수치를 읽기만 하고 원장에 쓰지 않는다.
package com.finplay.api.feedback.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.service.StockCandleDto;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.SellAllocationQueryService;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절이고 요구사항은 spec FEED-007이다.
 *
 * <p><b>검증 순서를 바꾸지 않는다.</b> {@code TradeService.getOwnedTrade}가 이미 정한 존재(404 {@code NOT_FOUND})
 * → 소유(403 {@code FORBIDDEN})를 그대로 타고, 그 뒤에 매수 체결 → 400, 코인 체결 → 400이다. 매도 회고
 * 투자일기({@code JournalService})가 같은 순서를 쓰고 있다.
 *
 * <p><b>코인은 빈 값을 채운 200을 돌려주지 않는다</b>(FEED-007 각주) — 게이트가 전부 장 마감과 원본 거래일에
 * 묶여 있는데 24시간 거래인 코인에는 둘 다 없다. 코인 매도 회고는 3차다.
 *
 * <p><b>수치는 원장에서 그대로 읽는다.</b> {@code buyPrice}는 FIFO 배분 가중평균 매수단가,
 * {@code sellPrice}·{@code quantity}·{@code fee}·{@code realizedPnl}은 {@code trades} 행 그대로다. 재계산하거나
 * LLM에게 계산시키지 않는다(PRD C-004). 배분·lot은 {@code portfolio} 소유라 서비스를 경유한다(§C-6).
 *
 * <p><b>원장에 쓰지 않는다.</b> 이 spec이 회원별로 쓰는 테이블은 {@code trade_feedbacks} 하나뿐이고, 그 쓰기는
 * 서술을 저장하는 항목(이슈 #208의 4·5번)이 별도 {@code @Transactional} 컴포넌트로 더한다 — <b>LLM 호출을
 * 트랜잭션 안에 넣지 않기 위해서다.</b> 이 클래스는 그때까지 읽기 전용이다.
 */
@Service
@RequiredArgsConstructor
public class PostSellFeedbackService {

	// 계약이 정한 수익률 scale·라운딩. PortfolioService·HoldingValuationService와 같은 값이다.
	private static final int RETURN_RATE_SCALE = 4;

	// 파생 사실 비율(sellVsHighRate·sellVsLowRate)의 scale. 계약 예시(-0.0325·0.0059)가 소수 4자리다.
	// returnRate와 값은 같지만 근거가 다르다 — 그쪽은 계약이 식과 함께 못박은 값이고 이쪽은 §파생 사실 계산의
	// 뺄셈·나눗셈이라, 한쪽 정밀도를 바꿀 이유가 생겼을 때 다른 쪽이 딸려 가지 않게 따로 둔다.
	private static final int DERIVED_RATE_SCALE = 4;

	/**
	 * 과거 서비스 날짜의 카드 노출 게이트 상한 — 그날 카드는 이미 전부 노출된 상태라 "그날의 가장 늦은 시각"이다.
	 *
	 * <p><b>{@code LocalTime.MAX}로 되돌리지 마라.</b> {@code reveal_time}은 소수 초가 없는 {@code TIME}인데
	 * {@code LocalTime.MAX}는 {@code 23:59:59.999999999}다. Connector/J가 나노초를 실어 보내고 MySQL이 그 소수 초를
	 * <b>올림해 {@code 00:00:00}으로 접는다</b>(실측: {@code select cast(? as char)}가 {@code LocalTime.MAX}에
	 * {@code "00:00:00"}을 돌려주고 {@code time '11:26:00' <= LocalTime.MAX}가 {@code 0}이다). 그러면 상한이
	 * 자정이 되어 <b>어제 이전에 판 체결의 {@code priceMoves}가 항상 빈 배열이고 {@code buyToNewsMinutes}가 항상
	 * {@code null}인데 예외도 로그도 남지 않는다</b> — 게이트 되돌림을 막으려 넣은 분기가 더 심하게 감추는 방향으로
	 * 뒤집힌다.
	 *
	 * <p>고치는 방향은 <b>나노초를 버리는 것뿐</b>이다. 질의를 {@code <}로 바꾸거나 상한 파라미터를 없애면 게이트
	 * 판정 자체가 달라진다. {@code PriceMoveEventRepositoryTest}가 이 트랩을 실제 쿼리로 못박아 뒀다.
	 */
	private static final LocalTime PAST_SERVICE_DATE_CUTOFF = LocalTime.MAX.withNano(0);

	private final TradeService tradeService;

	private final SellAllocationQueryService sellAllocationQueryService;

	private final StockReplayService stockReplayService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	private final Clock clock;

	/**
	 * 본인 매도 체결 1건의 회고를 조회한다.
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수·코인 체결은 400
	 *     {@code VALIDATION_ERROR}다
	 * @return 원장 수치와 {@code sameSessionCompleted}까지 채운 응답. 파생 사실·카드·매도 후 흐름·반사실·
	 *     집단 비교·서술은 뒤 항목이 채운다
	 */
	@Transactional(readOnly = true)
	public PostSellFeedbackResponse getPostSellFeedback(Long userId, Long tradeId) {
		Trade trade = tradeService.getOwnedTrade(userId, tradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (trade.getInstrument().getMarket() == Market.CRYPTO) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		SellAllocationSummaryDto allocation = sellAllocationQueryService.getSellAllocationSummary(tradeId);
		LocalDate sellSourceTradingDate = sourceTradingDateOf(trade);
		LocalDateTime buyAt = atOriginTradeDate(
			allocation.earliestBuyAt(), allocation.earliestBuySourceTradingDate());
		LocalDateTime sellAt = atOriginTradeDate(trade.getExecutedAt(), sellSourceTradingDate);

		// 파생 사실은 sameSessionCompleted가 참일 때만 성립한다 — 여러 재생일에 걸친 매매는 분봉이 불연속이라
		// 극값·간격 계산의 정의 자체가 없다(§파생 사실 계산). 계약이 정한 형태는 전부 null, priceMoves는 []다.
		boolean sameSessionCompleted = isSameSessionCompleted(sellSourceTradingDate, allocation);
		List<HeldPriceMoveItem> priceMoves = sameSessionCompleted
			? findHeldPriceMoves(trade, sellSourceTradingDate, buyAt, sellAt)
			: List.of();
		HoldExtremes extremes = sameSessionCompleted
			? findHoldExtremes(trade, sellSourceTradingDate, buyAt, sellAt)
			: HoldExtremes.absent();

		return new PostSellFeedbackResponse(
			trade.getId(),
			trade.getInstrument().getId(),
			trade.getInstrument().getSymbol(),
			trade.getInstrument().getName(),
			buyAt,
			sellAt,
			allocation.buyPrice(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getFee(),
			trade.getRealizedPnl(),
			returnRate(trade, allocation),
			holdingMinutes(buyAt, sellAt),
			sameSessionCompleted,
			// 보유 구간 극값·뉴스 대비 타이밍·보유 구간 카드 (§파생 사실 계산). 극값은 분봉 close만 쓴다.
			extremes.holdHighPrice(),
			extremes.holdHighAt(),
			extremes.holdLowPrice(),
			extremes.holdLowAt(),
			extremes.sellVsHighRate(),
			extremes.sellVsLowRate(),
			sameSessionCompleted ? buyToNewsMinutes(buyAt, priceMoves) : null,
			priceMoves,
			// 매도 후 흐름·반사실·집단 비교 — 3번 항목이 §C-5 게이트 판정과 함께 채운다.
			null,
			null,
			null,
			// AI 서술 — 4·5번 항목이 생성·저장·재사용·재생성과 함께 채운다. narrativeStatus는 항상 READY이고
			// 이 엔드포인트에 UNAVAILABLE이 존재하지 않는다(§C-4).
			null,
			null,
			null);
	}

	/**
	 * {@code realizedPnl ÷ (배분된 매수원가 합 + 배분된 매수수수료 합)}. 계약이 정한 식·scale·라운딩 그대로다.
	 *
	 * <p>분모가 0이면 {@code ZERO}로 둔다 — 배분 원가와 수수료가 동시에 0인 체결은 원장에 생기지 않지만
	 * {@code ArithmeticException}으로 조회 전체가 500이 되는 것보다 낫다.
	 */
	private BigDecimal returnRate(Trade trade, SellAllocationSummaryDto allocation) {
		long buyBasis = allocation.allocatedCost() + allocation.allocatedBuyFee();
		if (buyBasis == 0L || trade.getRealizedPnl() == null) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(trade.getRealizedPnl())
			.divide(BigDecimal.valueOf(buyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * {@code sellAt − buyAt} (분). <b>원본 거래일이 역전되면 {@code null}이다</b> (§파생 사실 계산,
	 * 2026-08-04 결정 · 이슈 #208).
	 *
	 * <p>두 값은 서로 다른 원본 거래일 축에 놓일 수 있다 — {@code buyAt}은 가장 이른 배분 lot의 원본 거래일이고
	 * {@code sellAt}은 이 매도 체결의 원본 거래일인데, <b>같은 원본 거래일을 여러 서비스 날짜에 재생할 수 있어</b>
	 * 매도의 원본 거래일이 매수 lot의 것보다 앞선 조합이 실제로 성립한다. 그러면 뺄셈이 음수가 되고 예외도 로그도
	 * 남지 않는다.
	 *
	 * <p><b>음수를 0으로 clamp하거나 서비스 벽시계 경과분으로 대체하지 않는다.</b> 둘 다 같은 응답의
	 * {@code buyAt}·{@code sellAt}과 산술이 어긋나 화면이 "표시된 두 시각의 차"를 복원할 수 없게 된다 — <b>틀린
	 * 사실 대신 없음을 낸다.</b> 이 조합은 이미 {@code sameSessionCompleted=false}이고 파생 사실·반사실·집단
	 * 비교가 전부 {@code null}이라 결이 같다.
	 *
	 * <p><b>조건은 "역전"뿐이고 {@code sameSessionCompleted=false} 전체가 아니다.</b> 원본 거래일이 순방향인
	 * 정상 cross-session 매매에서는 값이 의미가 있고, 계약의 {@code sameSessionCompleted=false} nullable 목록에도
	 * {@code holdingMinutes}가 없다.
	 */
	private static Integer holdingMinutes(LocalDateTime buyAt, LocalDateTime sellAt) {
		if (sellAt.isBefore(buyAt)) {
			return null;
		}
		return (int)Duration.between(buyAt, sellAt).toMinutes();
	}

	/**
	 * 보유 구간({@code buyAt} ~ {@code sellAt}, <b>양 끝 포함</b>)의 분봉에서 극값을 찾는다 (§파생 사실 계산).
	 *
	 * <p><b>분봉의 {@code close}만 쓴다 — {@code high}/{@code low}를 쓰지 않는다.</b> 이 서비스의 시장가 체결은
	 * 직전 완료 분봉의 종가로만 이루어지므로({@code StockReplayService}), {@code high}로 극값을 잡으면
	 * <b>사용자가 애초에 얻을 수 없었던 가격</b>이 된다. 3번 항목이 이 값을 반사실 {@code atHoldHigh}에 그대로
	 * 올리기 때문에, 여기서 한 번 틀리면 실현 불가능한 수익률로 후회를 유도하는 표가 나간다. 컴파일도 되고
	 * 예외도 없어 <b>세 값이 같은 픽스처에서는 두 구현이 같은 답을 낸다.</b>
	 *
	 * <p><b>{@code getFullDayCandles}는 재생 노출 게이트를 우회한다</b>(§C-6) — 판정은 호출부 책임이다. 여기서
	 * 그것을 지키는 방법은 <b>매도 시각까지로 잘라내는 것</b>이다. 재생이 1배속이라 원본 거래일 시각과 서비스
	 * 날짜의 벽시계 시각이 1:1로 대응하므로 이미 체결된 매도 시각까지는 반드시 재생이 끝난 구간이다.
	 * <b>매도 이후 구간은 이 항목이 건드리지 않는다</b> — {@code postSellFlow}·반사실이 §C-5의 장 마감 게이트를
	 * 쓰며 3번 항목 소유다.
	 *
	 * <p>극값이 동률이면 <b>이른 분봉</b>을 고른다. 화면이 "하락이 시작되기 몇 분 전"처럼 극값 시각을 서술의
	 * 근거로 쓰므로, 같은 종가가 여러 번 나온 날 뒤쪽 시각을 고르면 그 서술이 실행마다 달라진다.
	 *
	 * @return 그 구간에 분봉이 없으면 {@link HoldExtremes#absent()} — 조회는 200이고 극값만 {@code null}이다
	 */
	private HoldExtremes findHoldExtremes(
		Trade trade, LocalDate sourceTradingDate, LocalDateTime buyAt, LocalDateTime sellAt) {
		LocalTime from = buyAt.toLocalTime();
		LocalTime to = sellAt.toLocalTime();
		List<StockCandleDto> candles = stockReplayService
			.getFullDayCandles(trade.getInstrument().getId(), sourceTradingDate)
			.stream()
			.filter(candle -> !candle.candleTime().isBefore(from) && !candle.candleTime().isAfter(to))
			.toList();
		if (candles.isEmpty()) {
			return HoldExtremes.absent();
		}

		StockCandleDto high = candles.get(0);
		StockCandleDto low = candles.get(0);
		for (StockCandleDto candle : candles) {
			if (candle.close().compareTo(high.close()) > 0) {
				high = candle;
			}
			if (candle.close().compareTo(low.close()) < 0) {
				low = candle;
			}
		}

		BigDecimal sellPrice = trade.getPrice();
		return new HoldExtremes(
			high.close(),
			LocalDateTime.of(sourceTradingDate, high.candleTime()),
			low.close(),
			LocalDateTime.of(sourceTradingDate, low.candleTime()),
			rateAgainst(sellPrice, high.close()),
			rateAgainst(sellPrice, low.close()));
	}

	/** {@code (매도가 − 기준가) ÷ 기준가} (§파생 사실 계산). 기준가가 0인 분봉은 원장에 없지만 500을 내지 않는다. */
	private static BigDecimal rateAgainst(BigDecimal sellPrice, BigDecimal basePrice) {
		if (basePrice.signum() == 0) {
			return null;
		}
		return sellPrice.subtract(basePrice).divide(basePrice, DERIVED_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 보유 구간에 걸친 변동 카드를 노출 게이트까지 적용해 조회한다 (게이트 ⑮).
	 *
	 * <p>파인더가 구간 좁히기·게이트·정렬 두 키를 전부 담고 있으며 이 메서드는 <b>게이트 상한을 그 체결의
	 * 서비스 날짜로 계산해 넘기는 것</b>과 응답 조립만 한다. 상한 계산은 {@link #revealCutoff}에 있다.
	 */
	private List<HeldPriceMoveItem> findHeldPriceMoves(
		Trade trade, LocalDate sourceTradingDate, LocalDateTime buyAt, LocalDateTime sellAt) {
		LocalTime revealCutoff = revealCutoff(serviceDateOf(trade));
		if (revealCutoff == null) {
			return List.of();
		}

		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				trade.getInstrument().getId(),
				sourceTradingDate,
				buyAt.toLocalTime(),
				sellAt.toLocalTime(),
				revealCutoff);
		if (events.isEmpty()) {
			return List.of();
		}

		Map<Long, List<NewsItem>> sourcesByEventId = findSources(events);
		return events.stream()
			.map(event -> toHeldPriceMoveItem(
				event, buyAt, sellAt, sourcesByEventId.getOrDefault(event.getId(), List.of())))
			.toList();
	}

	/**
	 * 카드의 노출 게이트 상한을 <b>그 체결의 서비스 날짜</b> 기준으로 계산한다 (§C-5).
	 *
	 * <p>게이트는 {@code (서비스 날짜 + reveal_time) <= now()}이고 {@code reveal_time}은 {@code TIME}이다.
	 * {@code PriceMoveQueryService}가 {@code LocalTime.now(clock)}을 그대로 넘기는 것은 그쪽이 <b>오늘 재생 중인
	 * 세션</b>만 보기 때문이며, 매도 회고는 <b>과거 서비스 날짜의 체결</b>을 조회하므로 그 식이 그대로 성립하지
	 * 않는다. 오늘 벽시계로 자르면 어제 판 체결을 오늘 오전에 열었을 때 <b>그날 오후 카드가 다시 감춰진다</b> —
	 * 게이트 ⑭이 매도 후 흐름에 대해 막는 되돌림과 같은 형태이며, 같은 날 조회만 재현하면 두 구현이 같은 답을
	 * 낸다.
	 *
	 * @return 서비스 날짜가 과거면 {@link #PAST_SERVICE_DATE_CUTOFF}(그날 카드는 전부 노출된 상태), 오늘이면
	 *     현재 벽시계 시각, 미래이거나 서비스 날짜를 모르면 {@code null}(카드를 노출하지 않는다)
	 */
	private LocalTime revealCutoff(LocalDate serviceDate) {
		if (serviceDate == null) {
			return null;
		}
		LocalDate today = LocalDate.now(clock);
		if (serviceDate.isBefore(today)) {
			return PAST_SERVICE_DATE_CUTOFF;
		}
		if (serviceDate.isAfter(today)) {
			return null;
		}
		return LocalTime.now(clock);
	}

	/**
	 * 카드 1건을 응답 항목으로 옮긴다. 구간은 카드의 {@code originTradeDate}를 붙여 {@code buyAt}·{@code sellAt}과
	 * 같은 <b>원본 거래일 축</b>으로 내린다 — 조회한 날짜를 붙이면 한 객체 안에서 날짜가 갈린다.
	 *
	 * @param buyAt  {@code minutesAfterBuy = 카드 windowEnd − 매수시각}
	 * @param sellAt {@code minutesBeforeSell = 매도시각 − 카드 windowEnd}. 파인더가 {@code windowEnd}를 보유
	 *     구간으로 좁히므로 두 값은 음수가 되지 않는다
	 */
	private static HeldPriceMoveItem toHeldPriceMoveItem(
		PriceMoveEvent event, LocalDateTime buyAt, LocalDateTime sellAt, List<NewsItem> sources) {
		LocalDateTime windowStart = LocalDateTime.of(event.getOriginTradeDate(), event.getWindowStart());
		LocalDateTime windowEnd = LocalDateTime.of(event.getOriginTradeDate(), event.getWindowEnd());
		return new HeldPriceMoveItem(
			event.getId(),
			windowStart,
			windowEnd,
			event.getChangeRate(),
			(int)Duration.between(buyAt, windowEnd).toMinutes(),
			(int)Duration.between(windowEnd, sellAt).toMinutes(),
			event.getNarrative(),
			sources);
	}

	/**
	 * {@code buyToNewsMinutes = (T0 − 매수시각)} 분. {@code T0}는 <b>보유 구간 카드의 근거 기사 중 가장 이른
	 * 발행시각</b>이다 (§파생 사실 계산).
	 *
	 * <p><b>부호를 뒤집지 않는다</b> — 매수가 기사보다 앞이면 <b>양수</b>다. 계약이 "양수면 매수가 기사보다
	 * 앞섰다는 뜻"으로 문장까지 적어 뒀고, 부호가 반대면 화면이 "기사를 보고 매수했다"와 "기사 전에 매수했다"를
	 * 정확히 거꾸로 말한다.
	 *
	 * <p>모수가 <b>게이트를 통과한 카드의 근거</b>인 것도 규칙이다 — 감춰진 카드의 기사를 여기에 넣으면 분 수
	 * 하나로 그 기사의 존재와 시각이 새어 나간다.
	 *
	 * @return 근거 기사가 없으면 {@code null}. 4번 항목이 프롬프트의 {@code firstNewsAt}도 함께 {@code null}로 둔다
	 */
	private static Integer buyToNewsMinutes(LocalDateTime buyAt, List<HeldPriceMoveItem> priceMoves) {
		return priceMoves.stream()
			.flatMap(move -> move.sources().stream())
			.map(NewsItem::publishedAt)
			.min(Comparator.naturalOrder())
			.map(firstNewsAt -> (int)Duration.between(buyAt, firstNewsAt).toMinutes())
			.orElse(null);
	}

	/**
	 * 카드마다 따로 묻지 않고 한 번에 읽어 카드 id로 묶는다 — {@code PriceMoveQueryService}와 같은 조회를 쓴다.
	 *
	 * <p><b>정렬 규칙은 이 메서드가 아니라 리포지토리 질의({@code publishedAt} 내림차순 + {@code id} 오름차순)에
	 * 있다</b>(계약). 여기서는 {@code LinkedHashMap}·{@code ArrayList}가 그 순서를 삽입 순서로 보존할 뿐이라
	 * 규칙이 두 곳으로 갈리지 않는다.
	 */
	private Map<Long, List<NewsItem>> findSources(List<PriceMoveEvent> events) {
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

	/**
	 * <b>배분된 lot 전부를 본다</b>(FEED-007). 각 lot의 매수 체결과 이 매도 체결의
	 * {@code stockReplaySession.sourceTradingDate}를 대조하고 <b>하나라도 다르면 {@code false}</b>다 — 가장 이른
	 * lot 하나만 보고 판정하면 그 뒤 lot이 다른 재생일이어도 {@code true}가 되고, 그러면 분봉이 불연속인 구간에서
	 * 극값·반사실을 계산해 예외도 로그도 없이 틀린 값이 나간다.
	 *
	 * <p>매도 체결의 원본 거래일을 모르면 {@code false}다 — 대조 기준이 없는데 {@code true}로 두면 위와 같은
	 * 상태가 된다.
	 */
	private boolean isSameSessionCompleted(
		LocalDate sellSourceTradingDate, SellAllocationSummaryDto allocation) {
		if (sellSourceTradingDate == null) {
			return false;
		}
		return allocation.buySourceTradingDates().stream().allMatch(sellSourceTradingDate::equals);
	}

	private LocalDate sourceTradingDateOf(Trade trade) {
		StockReplaySession session = trade.getStockReplaySession();
		return session == null ? null : session.getSourceTradingDate();
	}

	/**
	 * 그 체결이 발생한 <b>서비스 날짜</b>다 — 노출 게이트의 기준 날짜이며 {@code sourceTradingDate}(원본 거래일)와
	 * 다른 값이다(§C-5). 같은 원본 거래일이 두 번 재생될 수 있어 둘을 섞으면 게이트가 남의 재생일을 본다.
	 */
	private static LocalDate serviceDateOf(Trade trade) {
		StockReplaySession session = trade.getStockReplaySession();
		return session == null ? null : session.getServiceDate();
	}

	/**
	 * 체결 시각을 <b>원본 거래일 시간축</b>으로 옮긴다. 재생이 1배속이라 원본 거래일의 시각과 서비스 날짜의
	 * 벽시계 시각이 1:1로 대응하므로 {@code LocalTime}은 그대로고 날짜만 갈린다(spec §핵심 제약 — 재생 시간축).
	 *
	 * <p>계약이 {@code buyAt}·{@code sellAt}을 <b>"원본 거래일 기준 체결 시각"</b>으로 정했다. 같은 응답의
	 * {@code priceMoves[].windowStart}·{@code postSellFlow.closeAt}·{@code holdHighAt}이 전부 원본 거래일 축이라,
	 * 여기만 서비스 날짜로 두면 한 객체 안에서 날짜가 갈려 화면이 시간축을 복원할 수 없다.
	 *
	 * <p>원본 거래일을 모르면 체결 시각을 그대로 둔다 — 주식 체결에는 항상 재생세션이 있고({@code Trade}가 그것을
	 * 강제한다) 거래가 성립한 세션은 {@code READY}라 실제로는 도달하지 않는 자리다.
	 */
	private static LocalDateTime atOriginTradeDate(LocalDateTime executedAt, LocalDate originTradeDate) {
		return originTradeDate == null ? executedAt : LocalDateTime.of(originTradeDate, executedAt.toLocalTime());
	}

	/**
	 * 보유 구간 극값 여섯 값을 함께 옮기는 내부 묶음이다 — 응답 DTO가 아니라 조립 중간값이라
	 * {@code dto/response/}에 두지 않는다(§C-6에 이 이름이 없는 이유다).
	 *
	 * <p>여섯 값은 <b>함께 있거나 함께 없다.</b> 개별 {@code null}로 흩뜨리면 "극값은 있는데 비율만 빈" 조합이
	 * 표현 가능해지고, 그 상태가 응답에 나가면 화면이 극값 시각은 그리면서 매도가와의 거리는 못 그린다.
	 */
	private record HoldExtremes(
		BigDecimal holdHighPrice,
		LocalDateTime holdHighAt,
		BigDecimal holdLowPrice,
		LocalDateTime holdLowAt,
		BigDecimal sellVsHighRate,
		BigDecimal sellVsLowRate) {

		/** {@code sameSessionCompleted=false}이거나 보유 구간에 분봉이 없을 때. 계약이 정한 전부 {@code null}이다. */
		static HoldExtremes absent() {
			return new HoldExtremes(null, null, null, null, null, null);
		}
	}
}
