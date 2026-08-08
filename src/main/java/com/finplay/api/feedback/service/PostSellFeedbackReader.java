// 매도 직후 피드백의 원장 수치·파생 사실·게이트 판정을 한 트랜잭션에서 읽어 오는 읽기 전용 컴포넌트.
package com.finplay.api.feedback.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.domain.HoldHighBasis;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.feedback.dto.response.Counterfactuals;
import com.finplay.api.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PeerComparison;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.dto.response.PostSellFlow;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절이고 요구사항은 spec FEED-007이다.
 * 서술을 뺀 응답 전체를 조립한다 — {@code PostSellFeedbackService}가 여기서 받은 값에 서술만 얹는다.
 *
 * <p><b>왜 조회 서비스와 따로 있는가.</b> 이 엔드포인트는 spec 012에서 <b>조회 경로에 LLM이 들어오는 첫 자리</b>다
 * (FEED-007 — {@code docs/conventions.md}의 "GET은 부수효과 없음"에 대한 유일한 예외). LLM 호출은 중앙값 2.5초·
 * p95 3.1초이고(#198 실측) <b>그 동안 DB 커넥션을 쥐면 안 된다.</b> 트랜잭션은 메서드 단위라 읽기와 LLM 호출이
 * 한 메서드에 있으면 경계를 좁힐 방법이 없고, <b>같은 클래스의 private 메서드에 애노테이션을 붙이는 것은
 * 자기호출이라 프록시를 타지 않아 무효다.</b> 그래서 읽기를 별도 빈으로 뺐다 — {@code PriceMoveCardWriter}를
 * 저장 쪽에서 뺀 것과 같은 판단이고 방향만 반대다.
 *
 * <p><b>lazy 연관을 이 트랜잭션 안에서 전부 값으로 바꿔 돌려준다.</b> {@code spring.jpa.open-in-view=false}이므로
 * {@code Trade.instrument}·{@code Trade.stockReplaySession}은 이 메서드가 끝나면 접근할 수 없다 — 응답 record에
 * 담기는 것은 전부 스칼라·record이고 엔티티가 밖으로 나가지 않는다({@code docs/conventions.md}).
 *
 * <p><b>검증 순서를 바꾸지 않는다.</b> {@code TradeService.getOwnedTrade}가 이미 정한 존재(404 {@code NOT_FOUND})
 * → 소유(403 {@code FORBIDDEN})를 그대로 타고, 그 뒤에 매수 체결 → 400이다. 매도 회고 투자일기
 * ({@code JournalService})가 같은 순서를 쓰고 있다. <b>이 검증이 서술 생성보다 먼저 일어나야 한다</b> — 뒤로
 * 미루면 남의 체결로도 LLM이 한 번 불린 뒤에 400이 나간다.
 *
 * <p><b>이 클래스가 두 시장의 진입점이고 조립은 주식만 한다</b>(3차, 이슈 #275). 코인 체결은 검증과 배분 조회를
 * 마친 뒤 {@link CryptoPostSellFeedbackReader}에 넘긴다 — <b>검증과 트랜잭션 경계를 한 곳에 두기 위해서다.</b>
 * 시장 판정을 서비스로 올리면 조립 전에 체결을 한 번 더 읽어야 하고, 코인 쪽에 {@code @Transactional}을 새로
 * 열면 같은 조회가 두 트랜잭션에 걸친다. <b>아래 주식 경로는 3차에서 동작이 바뀌지 않았다</b> — 산술을
 * {@link PostSellArithmetic}으로 옮긴 것은 코인과 식을 공유하기 위한 이동이고 값은 그대로다.
 *
 * <p><b>수치는 원장에서 그대로 읽는다.</b> {@code buyPrice}는 FIFO 배분 가중평균 매수단가,
 * {@code sellPrice}·{@code quantity}·{@code fee}·{@code realizedPnl}은 {@code trades} 행 그대로다. 재계산하거나
 * LLM에게 계산시키지 않는다(PRD C-004). 배분·lot은 {@code portfolio} 소유라 서비스를 경유한다(§C-6).
 *
 * <p><b>원장에 쓰지 않는다.</b> 이 클래스는 읽기 전용이고, 이 spec이 회원별로 쓰는 유일한 테이블
 * ({@code trade_feedbacks})은 {@code TradeFeedbackWriter}만 건드린다.
 */
@Component
@RequiredArgsConstructor
class PostSellFeedbackReader {

	// 수익률·비율·수수료·분 단위 차이의 식과 scale은 PostSellArithmetic이 단일 출처다(이슈 #275).
	// 코인 경로(CryptoPostSellFeedbackReader)와 같은 산술을 써야 하는 자리라 여기에 상수를 다시 두지 않는다.
	// 이 클래스는 주식 전용이므로 요율도 그 시장의 것으로 한 번만 고른다.
	private static final BigDecimal STOCK_FEE_RATE = PostSellArithmetic.feeRateOf(Market.STOCK);

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

	private final CryptoPostSellFeedbackReader cryptoPostSellFeedbackReader;

	private final TradeService tradeService;

	private final SellAllocationQueryService sellAllocationQueryService;

	private final StockReplayService stockReplayService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	private final PriceMovePeerStatRepository priceMovePeerStatRepository;

	private final Clock clock;

	/**
	 * 본인 매도 체결 1건의 회고에서 <b>서술을 뺀 전부</b>를 읽는다.
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수 체결은 400
	 *     {@code VALIDATION_ERROR}다. <b>코인 체결은 400이 아니라 200이다</b>(3차, 이슈 #275)
	 * @return {@code narrative}·{@code narrativeSource}·{@code narrativeStatus} 셋만 {@code null}인 응답.
	 *     그 셋은 {@code PostSellFeedbackService}가 {@code withNarrative}로 얹는다
	 */
	@Transactional(readOnly = true)
	PostSellFeedbackResponse read(Long userId, Long tradeId) {
		Trade trade = tradeService.getOwnedTrade(userId, tradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		SellAllocationSummaryDto allocation = sellAllocationQueryService.getSellAllocationSummary(tradeId);
		if (trade.getInstrument().getMarket() == Market.CRYPTO) {
			return cryptoPostSellFeedbackReader.read(trade, allocation);
		}

		LocalDate sellSourceTradingDate = sourceTradingDateOf(trade);
		LocalDateTime buyAt = atOriginTradeDate(
			allocation.earliestBuyAt(), allocation.earliestBuySourceTradingDate());
		LocalDateTime sellAt = atOriginTradeDate(trade.getExecutedAt(), sellSourceTradingDate);

		// 파생 사실은 sameSessionCompleted가 참일 때만 성립한다 — 여러 재생일에 걸친 매매는 분봉이 불연속이라
		// 극값·간격 계산의 정의 자체가 없다(§파생 사실 계산). 계약이 정한 형태는 전부 null, priceMoves는 []다.
		boolean sameSessionCompleted = isSameSessionCompleted(sellSourceTradingDate, allocation, buyAt, sellAt);
		List<HeldPriceMoveItem> priceMoves = sameSessionCompleted
			? findHeldPriceMoves(trade, sellSourceTradingDate, buyAt, sellAt)
			: List.of();

		// 분봉은 한 번만 읽고 극값(보유 구간)과 매도 후 흐름·반사실(장 마감 뒤)이 나눠 쓴다. getFullDayCandles는
		// 게이트를 우회해 하루치를 그대로 주므로(§C-6) 노출을 가르는 것은 조회 횟수가 아니라 "어디까지 잘라
		// 쓰는가"다 — 극값은 매도 시각까지 자르고, 매도 이후 구간은 아래 marketClosed가 참일 때만 읽는다.
		List<StockCandleDto> fullDayCandles = sameSessionCompleted
			? stockReplayService.getFullDayCandles(trade.getInstrument().getId(), sellSourceTradingDate)
			: List.of();
		HoldExtremes extremes = sameSessionCompleted
			? findHoldExtremes(fullDayCandles, trade.getPrice(), sellSourceTradingDate, buyAt, sellAt)
			: HoldExtremes.absent();

		// §C-5의 장 마감 게이트. "오늘 15:30"이 아니라 "그 매도 체결의 서비스 날짜 15:30"이다.
		boolean marketClosed = isAfterMarketClose(serviceDateOf(trade));

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
			extremes.basis(),
			sameSessionCompleted ? buyToNewsMinutes(buyAt, priceMoves) : null,
			priceMoves,
			// 매도 후 흐름·반사실·집단 비교. sameSessionCompleted=false면 세 필드 모두 자기 자신이 null이고
			// status만 담은 껍데기를 내리지 않는다 — 계약이 정한 형태이며 postSellFlow도 그 nullable 목록에 있다
			// (§파생 사실 계산의 "위 전부"에 [매도 후 흐름] 블록이 포함된다).
			sameSessionCompleted
				? buildPostSellFlow(marketClosed, fullDayCandles, trade.getPrice(), sellSourceTradingDate, sellAt)
				: null,
			sameSessionCompleted
				? buildCounterfactuals(
					marketClosed, fullDayCandles, sellSourceTradingDate, extremes, priceMoves,
					trade.getQuantity(), allocation.allocatedCost() + allocation.allocatedBuyFee())
				: null,
			sameSessionCompleted ? buildPeerComparison(priceMoves, serviceDateOf(trade)) : null,
			// AI 서술 셋은 이 클래스가 채우지 않는다 — LLM 호출을 이 트랜잭션 안에 넣지 않기 위해서다.
			// PostSellFeedbackService가 트랜잭션이 끝난 뒤 withNarrative로 얹는다.
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
	private static BigDecimal returnRate(Trade trade, SellAllocationSummaryDto allocation) {
		return PostSellArithmetic.returnRate(
			trade.getRealizedPnl(), allocation.allocatedCost() + allocation.allocatedBuyFee());
	}

	/**
	 * {@code sellAt − buyAt} (분). <b>{@link #isReversed} 조합이면 {@code null}이다</b> (§파생 사실 계산,
	 * 2026-08-04 결정 · 이슈 #208).
	 *
	 * <p><b>음수를 0으로 clamp하거나 서비스 벽시계 경과분으로 대체하지 않는다.</b> 둘 다 같은 응답의
	 * {@code buyAt}·{@code sellAt}과 산술이 어긋나 화면이 "표시된 두 시각의 차"를 복원할 수 없게 된다 — <b>틀린
	 * 사실 대신 없음을 낸다.</b>
	 *
	 * <p><b>조건은 역전뿐이고 {@code sameSessionCompleted=false} 전체가 아니다.</b> 원본 거래일이 순방향인 정상
	 * cross-session 매매에서는 값이 의미가 있고, 계약의 {@code sameSessionCompleted=false} nullable 목록에도
	 * {@code holdingMinutes}가 없다. 역전이면 {@code sameSessionCompleted}도 함께 {@code false}가 되지만
	 * <b>그 역은 성립하지 않는다</b>는 것이 이 자리의 요점이다.
	 */
	private static Integer holdingMinutes(LocalDateTime buyAt, LocalDateTime sellAt) {
		return isReversed(buyAt, sellAt) ? null : PostSellArithmetic.minutesBetween(buyAt, sellAt);
	}

	/**
	 * 원본 거래일 시간축에서 <b>매도가 매수보다 앞선</b> 조합 — {@code holdingMinutes}의 {@code null} 조건과
	 * {@link #isSameSessionCompleted}의 실패 조건이 <b>같은 이 판정 하나</b>를 가리킨다.
	 *
	 * <p>두 값은 서로 다른 원본 거래일 축에 놓일 수 있다 — {@code buyAt}은 가장 이른 배분 lot의 원본 거래일이고
	 * {@code sellAt}은 이 매도 체결의 원본 거래일이다. <b>같은 원본 거래일을 여러 서비스 날짜에 재생할 수 있어</b>
	 * (첫 재생일 오후에 매수, 다음 재생일 오전에 매도) 원본 거래일이 <b>같은 채로 시각만 역전되는</b> 조합이
	 * 실제로 성립한다. 그러면 분 계산이 음수가 되고 보유 구간이 <b>빈 구간</b>이 되는데 예외도 로그도 남지 않는다.
	 *
	 * <p>비교를 분으로 내리지 않고 <b>체결 시각 그대로</b> 한다. 이 판정이 막는 것은 "응답에 실린 두 시각이 순서가
	 * 뒤집혀 있다"이고, 분으로 내리면 같은 분 안의 역전을 놓친다 — FIFO가 같은 세션 안의 역전을 이미 막으므로
	 * 실제로 도달하지 않는 자리지만, 판정을 느슨하게 둘 이유가 없다.
	 */
	private static boolean isReversed(LocalDateTime buyAt, LocalDateTime sellAt) {
		return sellAt.isBefore(buyAt);
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
	 * <p><b>{@code fullDayCandles}는 하루치 전부라 재생 노출 게이트를 우회한 원본이다</b>(§C-6) — 판정은 호출부
	 * 책임이다. 여기서 그것을 지키는 방법은 <b>매도 시각까지로 잘라내는 것</b>이다. 재생이 1배속이라 원본 거래일
	 * 시각과 서비스 날짜의 벽시계 시각이 1:1로 대응하므로 이미 체결된 매도 시각까지는 반드시 재생이 끝난 구간이다.
	 * <b>매도 이후 구간을 이 메서드가 보지 않는다</b> — {@code postSellFlow}·반사실이 §C-5의 장 마감 게이트를 통과한
	 * 뒤에만 그 구간을 읽는다.
	 *
	 * <p>극값이 동률이면 <b>이른 분봉</b>을 고른다. 화면이 "하락이 시작되기 몇 분 전"처럼 극값 시각을 서술의
	 * 근거로 쓰므로, 같은 종가가 여러 번 나온 날 뒤쪽 시각을 고르면 그 서술이 실행마다 달라진다.
	 *
	 * @return 그 구간에 분봉이 없으면 {@link HoldExtremes#absent()} — 조회는 200이고 극값만 {@code null}이다
	 */
	private static HoldExtremes findHoldExtremes(
		List<StockCandleDto> fullDayCandles,
		BigDecimal sellPrice,
		LocalDate sourceTradingDate,
		LocalDateTime buyAt,
		LocalDateTime sellAt) {
		LocalTime from = PostSellArithmetic.candleBoundary(buyAt);
		LocalTime to = PostSellArithmetic.candleBoundary(sellAt);
		List<StockCandleDto> candles = fullDayCandles.stream()
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

		return new HoldExtremes(
			high.close(),
			LocalDateTime.of(sourceTradingDate, high.candleTime()),
			low.close(),
			LocalDateTime.of(sourceTradingDate, low.candleTime()),
			PostSellArithmetic.rateAgainst(sellPrice, high.close()),
			PostSellArithmetic.rateAgainst(sellPrice, low.close()),
			// 주식은 언제나 1분봉으로 잰다 — DAILY는 코인 전용이다(§FEED-012 결정 4).
			HoldHighBasis.MINUTE);
	}

	/**
	 * §C-5의 장 마감 게이트 — {@code now() >= (그 매도 체결의 서비스 날짜) 15:30}이다.
	 *
	 * <p><b>"오늘 15:30"이 아니다.</b> 오늘로 잡으면 어제 판 체결을 오늘 오전에 열었을 때 {@code READY}였던 값이
	 * {@code NOT_YET}으로 되돌아간다(게이트 ⑭). <b>같은 날 조회만 재현하면 두 구현이 같은 답을 내므로</b> 날짜를
	 * 하루 넘긴 조회가 이 분기의 유일한 검증 수단이다.
	 *
	 * <p>기준 날짜는 {@link #serviceDateOf}({@code Trade.stockReplaySession.serviceDate})이고 15:30은
	 * {@link MarketSessionTimes#MARKET_CLOSE_TIME}이다 — <b>상수를 새로 선언하지 않는다</b>(§C-6). 이 값의 성격은
	 * §C-2-1이 <b>벽시계</b>로 못박았다: 분봉 존재 여부와 무관한 게이트이므로 "마지막 분봉 시각"으로 바꾸면 안
	 * 된다. 가격 조회 쪽만 분봉 표현을 쓴다.
	 *
	 * @return 서비스 날짜를 모르면 {@code false} — 기준이 없는데 열면 재생되지 않은 미래 가격이 나간다
	 */
	private boolean isAfterMarketClose(LocalDate serviceDate) {
		if (serviceDate == null) {
			return false;
		}
		return !LocalDateTime.now(clock)
			.isBefore(LocalDateTime.of(serviceDate, MarketSessionTimes.MARKET_CLOSE_TIME));
	}

	/**
	 * 매도 이후 그 거래일 마지막 분봉까지의 흐름 (§파생 사실 계산의 {@code [매도 후 흐름]}).
	 *
	 * <p><b>게이트 전에는 {@code status = NOT_YET}이고 가격 필드가 전부 {@code null}이다</b> — 14:40에 매도하고
	 * 14:41에 조회하면 장 마감까지의 가격은 아직 재생되지 않은 미래이고, 그걸 보여주면 같은 종목을 재매수할 때
	 * 답을 아는 상태가 된다. 그래서 <b>{@code fullDayCandles}의 매도 이후 구간을 여기서 처음 읽는다.</b>
	 *
	 * <p><b>{@code status}는 게이트만 반영하고 데이터 유무를 반영하지 않는다</b>(§C-4는 두 값을 게이트로만
	 * 정의한다). 게이트가 열렸는데 분봉이 없으면 {@code READY}에 값만 {@code null}이다 — 그 조합은 재생된 거래일
	 * 에서는 성립하지 않지만, 없는 데이터를 {@code NOT_YET}으로 감추면 장 마감 뒤에도 영원히 "아직"으로 보인다.
	 */
	private static PostSellFlow buildPostSellFlow(
		boolean marketClosed,
		List<StockCandleDto> fullDayCandles,
		BigDecimal sellPrice,
		LocalDate sourceTradingDate,
		LocalDateTime sellAt) {
		if (!marketClosed) {
			return new PostSellFlow(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null);
		}

		StockCandleDto lastCandle = lastCandle(fullDayCandles);
		StockCandleDto postSellHigh = highestCloseAfter(fullDayCandles, PostSellArithmetic.candleBoundary(sellAt));
		return new PostSellFlow(
			PostSellFeedbackStatus.READY,
			lastCandle == null ? null : lastCandle.close(),
			lastCandle == null ? null : LocalDateTime.of(sourceTradingDate, lastCandle.candleTime()),
			// sellToCloseRate = (종가 − 매도가) ÷ 매도가. 극값 두 비율과 기준가 자리가 뒤바뀐다.
			lastCandle == null ? null : PostSellArithmetic.rateAgainst(lastCandle.close(), sellPrice),
			postSellHigh == null ? null : postSellHigh.close(),
			postSellHigh == null ? null : LocalDateTime.of(sourceTradingDate, postSellHigh.candleTime()));
	}

	/**
	 * 같은 수량을 다른 시점에 팔았다면 어땠을지 (§반사실·집단 비교 계산). 게이트는 {@code postSellFlow}와 같다 —
	 * 아직 재생되지 않은 가격을 쓰므로 미래 정보다.
	 *
	 * <p>세 시나리오의 {@code returnRate}는 {@link #counterfactualReturnRate}가 채운다 — 시나리오 가격마다
	 * 매도수수료를 다시 계산하므로(가격이 바뀌면 수수료도 바뀐다) 본체 {@link #returnRate}를 재사용할 수 없다.
	 *
	 * @param quantity 매도 수량 — 세 시나리오가 전부 같은 수량을 판다고 가정한다(§반사실·집단 비교 계산)
	 * @param buyBasis 배분 매수원가 + 배분 매수수수료. 세 시나리오가 공유하는 분모다
	 */
	private static Counterfactuals buildCounterfactuals(
		boolean marketClosed,
		List<StockCandleDto> fullDayCandles,
		LocalDate sourceTradingDate,
		HoldExtremes extremes,
		List<HeldPriceMoveItem> priceMoves,
		BigDecimal quantity,
		long buyBasis) {
		if (!marketClosed) {
			return new Counterfactuals(PostSellFeedbackStatus.NOT_YET, null, null, null);
		}
		return new Counterfactuals(
			PostSellFeedbackStatus.READY,
			scenarioAtClose(fullDayCandles, sourceTradingDate, quantity, buyBasis),
			scenarioAtHoldHigh(extremes, quantity, buyBasis),
			scenarioAtFirstMoveAfterBuy(fullDayCandles, priceMoves, quantity, buyBasis));
	}

	/** {@code atClose} — 그 거래일 <b>마지막 분봉</b>의 close와 그 시각이다 (§C-2-1, 리터럴 15:30이 아니다). */
	private static CounterfactualScenario scenarioAtClose(
		List<StockCandleDto> fullDayCandles, LocalDate sourceTradingDate, BigDecimal quantity, long buyBasis) {
		StockCandleDto lastCandle = lastCandle(fullDayCandles);
		return lastCandle == null
			? null
			: new CounterfactualScenario(
				lastCandle.close(), LocalDateTime.of(sourceTradingDate, lastCandle.candleTime()),
				PostSellArithmetic.counterfactualReturnRate(lastCandle.close(), quantity, buyBasis, STOCK_FEE_RATE));
	}

	/** {@code atHoldHigh} — 보유 구간 최고가와 그 시각. 극값이 없으면(구간에 분봉이 없으면) {@code null}이다. */
	private static CounterfactualScenario scenarioAtHoldHigh(
		HoldExtremes extremes, BigDecimal quantity, long buyBasis) {
		return extremes.holdHighPrice() == null
			? null
			: new CounterfactualScenario(extremes.holdHighPrice(), extremes.holdHighAt(),
				PostSellArithmetic.counterfactualReturnRate(
					extremes.holdHighPrice(), quantity, buyBasis, STOCK_FEE_RATE));
	}

	/**
	 * {@code atFirstMoveAfterBuy} — <b>보유 구간(매수~매도) 안의 첫 변동 카드</b> {@code windowEnd}의 종가다.
	 *
	 * <p>기준 카드는 {@code priceMoves.get(0)}이고 그 순서를 고정하는 것이 파인더의 정렬 두 키
	 * ({@code windowStart} 오름차순 + {@code id} 오름차순)다 — 2차 키가 없으면 <b>같은 체결의 반사실 값이 조회마다
	 * 달라진다.</b> 파인더가 {@code windowEnd}를 보유 구간으로 좁히므로 <b>매도 이후의 카드는 애초에 목록에
	 * 없다</b>(보유하지 않은 구간이라 반사실 기준이 될 수 없다).
	 *
	 * @return 보유 구간에 카드가 0건이면 {@code null}. 카드는 종목·거래일당 {@code max-intraday-cards}건이고
	 *     근거 기사가 없으면 생성되지 않으므로 <b>0건이 오히려 흔한 경우다</b>. 그 카드의 {@code windowEnd} 분봉이
	 *     없어도 {@code null}이다 — 가격을 지어내지 않는다
	 */
	private static CounterfactualScenario scenarioAtFirstMoveAfterBuy(
		List<StockCandleDto> fullDayCandles, List<HeldPriceMoveItem> priceMoves, BigDecimal quantity, long buyBasis) {
		if (priceMoves.isEmpty()) {
			return null;
		}
		LocalDateTime windowEnd = priceMoves.get(0).windowEnd();
		return fullDayCandles.stream()
			.filter(candle -> candle.candleTime().equals(windowEnd.toLocalTime()))
			.findFirst()
			.map(candle -> new CounterfactualScenario(
				candle.close(),
				windowEnd,
				PostSellArithmetic.counterfactualReturnRate(candle.close(), quantity, buyBasis, STOCK_FEE_RATE)))
			.orElse(null);
	}

	/**
	 * 그 거래일 <b>마지막 분봉</b>이다 — {@code getFullDayCandles}가 {@code candleTime} 오름차순으로 주므로 마지막
	 * 원소가 그것이다.
	 *
	 * <p><b>{@code 15:30}을 리터럴 시각으로 찾지 않는다</b>(§C-2-1). 수집기가 {@code 09:00~15:30}을 허용하지만
	 * 15:30 분봉이 오는 것은 보장되지 않아 그날 마지막 분봉이 15:29일 수 있다 — 리터럴로 찾으면 <b>없는 날
	 * {@code null}이 되고 예외는 안 난다.</b> {@code closePrice}·{@code atClose}가 조용히 비는 자리다.
	 * {@code StockReplayService.getPreviousTradingDayClose}가 시가 갭 판정에서 같은 이유로 같은 방식을 쓴다.
	 */
	private static StockCandleDto lastCandle(List<StockCandleDto> fullDayCandles) {
		return fullDayCandles.isEmpty() ? null : fullDayCandles.get(fullDayCandles.size() - 1);
	}

	/**
	 * 매도 시각 <b>이후</b> 분봉 중 {@code close} 최댓값 ({@code postSellHighPrice}·{@code postSellHighAt}).
	 *
	 * <p>경계를 <b>배타</b>로 둔 것은 보유 구간 극값이 매도 분봉을 <b>포함</b>하기 때문이다(§파생 사실 계산이
	 * "양 끝 포함"으로 정했다) — 같은 분봉이 "보유 중 최고가"와 "매도 후 최고가"에 동시에 잡히면 화면이 두 값을
	 * 나란히 놓는 의미가 없어진다. 여기서도 {@code high}가 아니라 {@code close}만 쓴다.
	 *
	 * <p>동률이면 이른 분봉을 고른다 — 극값과 같은 규칙이다.
	 *
	 * @return 매도 이후 분봉이 없으면(마지막 분봉에 매도했으면) {@code null}
	 */
	private static StockCandleDto highestCloseAfter(List<StockCandleDto> fullDayCandles, LocalTime sellTime) {
		StockCandleDto highest = null;
		for (StockCandleDto candle : fullDayCandles) {
			if (!candle.candleTime().isAfter(sellTime)) {
				continue;
			}
			if (highest == null || candle.close().compareTo(highest.close()) > 0) {
				highest = candle;
			}
		}
		return highest;
	}

	/**
	 * 집단 비교 상태 판정 (§C-4·§반사실·집단 비교 계산). 판정 순서는 {@code NO_EVENT}가 1순위다 — 기준 카드
	 * 자체가 없으면 {@code price_move_peer_stats} 확정 집계 행이 애초에 생기지 않으므로, 행 존재만 보면 이 흔한
	 * 경우가 영원히 {@link #peerComparisonNotYet()}이 된다.
	 *
	 * <p>기준 카드는 {@code priceMoves.get(0)}이다 — 반사실 {@code atFirstMoveAfterBuy}와 같은 카드고, 정렬 두 키
	 * ({@code windowStart} 오름차순 + {@code id} 오름차순)가 그 순서를 고정한다. 조회는 <b>그 매도 체결의 서비스
	 * 날짜</b> 행만 본다(§C-9) — 같은 카드가 재재생으로 다른 서비스 날짜에도 행을 가질 수 있어 날짜를 넘기지 않으면
	 * 남의 재생일 통계가 섞인다.
	 *
	 * @param sellServiceDate 조회 중인 매도 체결의 서비스 날짜 ({@link #serviceDateOf})
	 */
	private PeerComparison buildPeerComparison(List<HeldPriceMoveItem> priceMoves, LocalDate sellServiceDate) {
		if (priceMoves.isEmpty()) {
			return PostSellArithmetic.peerComparisonNoEvent();
		}

		HeldPriceMoveItem card = priceMoves.get(0);
		// yourMinutesToSell = 매도시각 − 카드 windowEnd (분). card.minutesBeforeSell()이 이미 같은 계산
		// (toHeldPriceMoveItem의 minutesBetween(windowEnd, sellAt))이라 다시 계산하지 않고 그대로 쓴다.
		Integer yourMinutesToSell = card.minutesBeforeSell();
		return priceMovePeerStatRepository
			.findByPriceMoveEventIdAndServiceDate(card.id(), sellServiceDate)
			.map(stat -> PostSellArithmetic.toPeerComparison(stat, card.id(), yourMinutesToSell))
			.orElseGet(PostSellArithmetic::peerComparisonNotYet);
	}

	/**
	 * 보유 구간에 걸친 변동 카드를 노출 게이트까지 적용해 조회한다 (게이트 ⑮).
	 *
	 * <p>파인더가 구간 좁히기·게이트·정렬 두 키를 전부 담고 있으며 이 메서드는 <b>게이트 상한을 그 체결의
	 * 서비스 날짜로 계산해 넘기는 것</b>과 응답 조립만 한다. 상한 계산은 {@link #revealCutoff}에 있다.
	 *
	 * <p>보유 구간 두 경계는 {@link #candleBoundary}로 분 단위로 내려 넘긴다 — {@code window_end}가 소수 초 없는
	 * {@code TIME}인데 체결 시각에는 소수 초가 붙어, 그대로 넘기면 <b>매수 분과 같은 분에 끝난 카드가 하한 밖으로
	 * 밀린다.</b> 이유는 그 메서드에 있다.
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
				PostSellArithmetic.candleBoundary(buyAt),
				PostSellArithmetic.candleBoundary(sellAt),
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
		// 오늘 분기도 나노초를 버린다 — 위 PAST_SERVICE_DATE_CUTOFF와 같은 트랩이다. 23:59:59.5~.999에 조회하면
		// MySQL이 TIME 파라미터의 소수 초를 올림해 00:00:00으로 접고, 그 순간 상한이 자정이 되어 그날 카드가
		// 전부 사라진다. 창이 하루 0.5초뿐이라 재현이 사실상 불가능하고 예외도 로그도 없으므로, 값으로 막는다.
		return LocalTime.now(clock).withNano(0);
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
			PostSellArithmetic.minutesBetween(buyAt, windowEnd),
			PostSellArithmetic.minutesBetween(windowEnd, sellAt),
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
			.map(firstNewsAt -> PostSellArithmetic.minutesBetween(buyAt, firstNewsAt))
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
	 *
	 * <p><b>원본 거래일이 같아도 {@link #isReversed} 조합이면 {@code false}다</b> (2026-08-05 결정 · 이슈 #208).
	 * 같은 원본 거래일을 두 서비스 날짜에 재생하면 첫 재생일 오후에 매수하고 다음 재생일 오전에 매도할 수 있어,
	 * <b>원본 거래일은 같은 채로 시각만 역전되는</b> 조합이 성립한다. 날짜만 보면 {@code true}가 되는데 보유 구간이
	 * 빈 구간이라 극값·카드·반사실이 전부 {@code null}·{@code []}로 나가서 <b>계약의 "{@code true}면 채워진다"와
	 * 정면으로 어긋난다.</b> 사실상 같은 장에서 완결된 거래가 아니고, 계약이 이미 {@code false}에서 그 값들을 전부
	 * {@code null}로 정해 뒀으므로 <b>계약을 고치지 않고 약속과 실제가 맞는다.</b>
	 *
	 * <p>판정을 {@code isReversed} 하나로 모은 것이 요점이다 — {@code holdingMinutes}의 {@code null} 조건이 같은
	 * 함수를 쓴다. 두 곳에 따로 쓰면 한쪽만 고치는 순간 "{@code sameSessionCompleted=true}인데
	 * {@code holdingMinutes}가 {@code null}" 같은 조합이 응답에 나간다.
	 */
	private boolean isSameSessionCompleted(
		LocalDate sellSourceTradingDate,
		SellAllocationSummaryDto allocation,
		LocalDateTime buyAt,
		LocalDateTime sellAt) {
		if (sellSourceTradingDate == null) {
			return false;
		}
		if (isReversed(buyAt, sellAt)) {
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

}
