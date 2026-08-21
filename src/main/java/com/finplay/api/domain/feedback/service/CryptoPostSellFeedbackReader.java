// 코인 매도 회고의 파생 사실·반사실·집단 비교를 조립하는 컴포넌트 (spec §FEED-012, 이슈 #275).
package com.finplay.api.domain.feedback.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.domain.feedback.dto.response.Counterfactuals;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItemResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.dto.response.PostSellFlow;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.CandleInterval;
import com.finplay.api.domain.market.service.CandleQueryService;
import com.finplay.api.domain.market.service.CryptoCandleDto;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 계약은 {@code docs/api/feedback.md}의 "매도 직후 피드백 조회 — 코인 체결의 차이" 소절이고 결정과 근거는
 * spec §FEED-012다. 산술은 {@link PostSellArithmetic}이 주식과 공유한다.
 *
 * <p><b>진입점이 아니다.</b> 존재(404)·소유(403)·매수 체결(400) 검증과 배분 조회는
 * {@link PostSellFeedbackContextReader}(트랜잭션 A)가 마쳤고, 시장 분기는 {@link PostSellFeedbackReader}가 한다.
 * 이 클래스는 코인 체결의 조립만 맡는다 — 검증을 여기에도 두면 순서가 두 곳으로 갈린다.
 *
 * <p><b>이 클래스에는 {@code @Transactional}이 없고, 그것이 결정이다</b>(spec §FEED-012 결정 5, 이슈 #282). 아래
 * {@code read}는 빗썸 REST를 최대 4회 부르는데(타임아웃 예산 connect 2초·read 3초) 그 구간이 트랜잭션 안이면
 * 요청 하나가 십수 초 동안 Hikari 커넥션 1개를 쥔다(풀 20). DB에서 읽어야 하는 두 덩어리는
 * {@link CryptoPostSellFeedbackDbReader}가 <b>REST 구간 앞뒤로 갈라진 짧은 트랜잭션 둘</b>(B·C)로 갖는다 —
 * 여기에 애노테이션을 되살리면 그 둘이 다시 REST 구간을 가로질러 하나로 합쳐진다.
 *
 * <p><b>코인에는 재생 시간축이 없다</b>(§FEED-012 결정 0). {@code Trade}가 코인 체결의
 * {@code stockReplaySession}을 {@code null}로 강제하므로 원본 거래일도 서비스 날짜도 없고,
 * {@code executed_at}이 곧 실제 시각이다. <b>주식 경로가 두 축을 오가며 하는 변환이 여기서는 전부 항등이라
 * 아예 나타나지 않는다</b> — 날짜를 갈아 끼우는 코드가 이 클래스에 없는 것이 누락이 아니라 결정이다.
 *
 * <p><b>게이트는 KST 자정이다</b>(§C-5·§FEED-012 결정 1). 주식의 15:30 게이트는 <b>재생 스포일러 차단</b>이
 * 존재 이유인데(매도 후 가격이 아직 오지 않은 미래다) 코인은 실시간이라 그 위험이 없다. 그래서 게이트가 남은
 * 이유는 <b>종가와 집단 집계가 언제 확정되는가</b> 하나뿐이고, 그 답이 "그 날짜가 끝나면"이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoPostSellFeedbackReader {

	/**
	 * 1분봉으로 덮을 수 있는 최대 구간(분) — 공급자 상한이 <b>200봉</b>이라 양 끝을 포함하면 {@code 199분}이다
	 * ({@code 013-candle-interval} 38행).
	 *
	 * <p><b>200으로 두면 안 된다.</b> {@code [t, t+200분]}은 봉 201개라 공급자가 <b>예외 없이</b> {@code to} 기준
	 * 최신 200개로 잘라 주고, 그러면 <b>보유 구간 맨 앞 1분이 빠진 극값</b>이 나간다 — 그 봉이 최고가였던 경우에만
	 * 값이 달라지므로 대부분의 픽스처에서 두 구현이 같은 답을 낸다.
	 *
	 * <p>이 상한 자체는 이 이슈에서 바꾸지 않는다({@code 013-candle-interval} 107행이 범위 제외로 두었다). 넘는
	 * 구간은 §FEED-012 결정 4의 일봉 표본으로 내려간다.
	 */
	private static final int MAX_MINUTE_SPAN_MINUTES = 199;

	/** 일봉 close를 응답 시각으로 표기할 때 쓰는 그 일자의 마지막 분 (§FEED-012 결정 2 — 조회가 아니라 표기다). */
	private static final LocalTime DAY_END_LABEL = LocalTime.of(23, 59);

	private final CandleQueryService candleQueryService;

	private final CryptoPostSellFeedbackDbReader cryptoPostSellFeedbackDbReader;

	private final Clock clock;

	/**
	 * 코인 매도 체결 1건의 회고에서 <b>서술을 뺀 전부</b>를 조립한다.
	 *
	 * <p><b>단계 순서가 트랜잭션 경계다</b>(§FEED-012 결정 5) — DB 조회 둘 사이에 REST 4종이 들어가고, 그 넷은
	 * 어느 트랜잭션에도 속하지 않는다. {@code priceMoves}를 뒤로 미룰 수 없는 것은 순서 취향이 아니라
	 * {@link #scenarioAtFirstMoveAfterBuy}가 {@code priceMoves.get(0).windowEnd()}를 캔들 조회 인자로 쓰기
	 * 때문이고, 반대로 {@code peerComparison}은 REST 결과와 무관한데도 뒤로 미뤄 트랜잭션이 REST 구간을 가로지르지
	 * 않게 한다.
	 *
	 * <p><b>조립을 생성자 인자 안에서 하지 않는다.</b> 응답 record 인자 목록에 조회를 그대로 쓰면 실행 순서가
	 * 인자 평가 순서에 숨어, 인자 자리를 옮기는 것만으로 트랜잭션 경계가 조용히 바뀐다.
	 *
	 * @param trade 코인 매도 체결. 검증은 호출부가 이미 마쳤다
	 * @return {@code narrative}·{@code narrativeSource}·{@code narrativeStatus} 셋만 {@code null}인 응답
	 */
	PostSellFeedbackResponse read(Trade trade, SellAllocationSummaryDto allocation) {
		String symbol = trade.getInstrument().getSymbol();
		// 축 변환이 없다 — 코인 체결 시각이 곧 실제 시각이다(§FEED-012 결정 0).
		LocalDateTime buyAt = allocation.earliestBuyAt();
		LocalDateTime sellAt = trade.getExecutedAt();
		long buyBasis = allocation.allocatedCost() + allocation.allocatedBuyFee();

		// (트랜잭션 B) 카드 조회 — 아래 REST 조회가 이 결과를 인자로 쓴다.
		List<HeldPriceMoveItemResponse> priceMoves = cryptoPostSellFeedbackDbReader.findHeldPriceMoves(trade, buyAt,
			sellAt);

		// (트랜잭션 없음) 캔들 REST 4종.
		HoldExtremes extremes = findHoldExtremes(symbol, trade.getPrice(), buyAt, sellAt);
		boolean dayClosed = isAfterDayClose(sellAt);
		// 매도일 종가는 매도 후 흐름과 반사실이 같은 값을 쓴다. 각자 부르면 같은 일봉을 외부에서 두 번 받는데
		// 일봉은 캐시되지 않아(CryptoCandleStore는 분봉 전용) 그 두 번이 전부 실제 REST 호출이다.
		BigDecimal sellDayClose = dayClosed ? sellDayClose(symbol, sellAt) : null;
		PostSellFlow postSellFlow = buildPostSellFlow(symbol, dayClosed, sellDayClose, trade.getPrice(), sellAt);
		Counterfactuals counterfactuals = buildCounterfactuals(
			symbol, dayClosed, sellDayClose, extremes, priceMoves, trade.getQuantity(), buyBasis, sellAt);

		// (트랜잭션 C) 집단 비교 — priceMoves만 있으면 계산되지만 REST 구간 뒤로 미룬다.
		PeerComparison peerComparison = cryptoPostSellFeedbackDbReader.buildPeerComparison(priceMoves);

		return new PostSellFeedbackResponse(
			trade.getId(),
			trade.getInstrument().getId(),
			symbol,
			trade.getInstrument().getName(),
			buyAt,
			sellAt,
			allocation.buyPrice(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getFee(),
			trade.getRealizedPnl(),
			PostSellArithmetic.returnRate(trade.getRealizedPnl(), buyBasis),
			PostSellArithmetic.minutesBetween(buyAt, sellAt),
			// 코인은 항상 true다 — 재생일이 없어 시간축이 언제나 연속이고, FIFO 배분이라 시각 역전도 없다
			// (§FEED-012 결정 0). 필드를 없애지 않는 것은 프론트가 이 값으로 응답 형태를 가르기 때문이다.
			true,
			extremes.holdHighPrice(),
			extremes.holdHighAt(),
			extremes.holdLowPrice(),
			extremes.holdLowAt(),
			extremes.sellVsHighRate(),
			extremes.sellVsLowRate(),
			extremes.basis(),
			buyToNewsMinutes(buyAt, priceMoves),
			priceMoves,
			postSellFlow,
			counterfactuals,
			peerComparison,
			// 서술 셋은 PostSellFeedbackService가 트랜잭션이 끝난 뒤 withNarrative로 얹는다.
			null,
			null,
			null);
	}

	/**
	 * §C-5의 코인 게이트 — {@code now() >= (매도 체결 시각의 KST 날짜 + 1일) 00:00}이다.
	 *
	 * <p><b>"오늘 자정"이 아니라 "그 체결 날짜의 다음 자정"이다.</b> 오늘로 잡으면 어제 판 체결이 오늘 다시
	 * {@code NOT_YET}으로 되돌아간다 — 주식의 "오늘 15:30이 아니라 그 체결의 서비스 날짜 15:30"과 같은 함정이며,
	 * <b>같은 날 조회만 재현하면 두 구현이 같은 답을 낸다.</b>
	 */
	private boolean isAfterDayClose(LocalDateTime sellAt) {
		return !LocalDateTime.now(clock).isBefore(sellAt.toLocalDate().plusDays(1).atStartOfDay());
	}

	/**
	 * 보유 구간({@code buyAt} ~ {@code sellAt}, <b>양 끝 포함</b>)의 극값 (§파생 사실 계산 · §FEED-012 결정 4).
	 *
	 * <p><b>200봉 상한이 정밀도를 가른다.</b> 구간이 {@link #MAX_MINUTE_SPAN_MINUTES} 이하면 1분봉으로 주식과
	 * 똑같이 계산하고({@link HoldHighBasis#MINUTE}), 넘으면 일봉 표본으로 내려간다
	 * ({@link HoldHighBasis#DAILY}). <b>넘는 구간을 그냥 1분봉으로 조회하면 예외가 나지 않는다</b> — 공급자가
	 * 조용히 뒤쪽 200개만 주므로 "보유 구간 앞부분을 안 본" 극값이 정상 200으로 나간다.
	 *
	 * <p><b>{@code close}만 쓴다 — {@code high}/{@code low}를 쓰지 않는다.</b> 이 서비스의 시장가 체결은 직전
	 * 완료 봉의 종가로만 이루어지므로 {@code high}로 극값을 잡으면 <b>사용자가 애초에 얻을 수 없었던 가격</b>이
	 * 되고, 그 값이 반사실 {@code atHoldHigh}에 그대로 올라가 실현 불가능한 수익률로 후회를 유도한다. 주식
	 * 경로와 같은 판단이다.
	 */
	private HoldExtremes findHoldExtremes(
		String symbol, BigDecimal sellPrice, LocalDateTime buyAt, LocalDateTime sellAt) {
		if (PostSellArithmetic.minutesBetween(buyAt, sellAt) <= MAX_MINUTE_SPAN_MINUTES) {
			LocalDateTime from = PostSellArithmetic.onMinuteBoundary(buyAt);
			LocalDateTime to = PostSellArithmetic.onMinuteBoundary(sellAt);
			return extremesOf(
				minuteCandlesWithin(symbol, from, to), sellPrice, HoldHighBasis.MINUTE, CryptoCandleDto::sourceTime);
		}
		return extremesOf(
			dailyCandlesWithinHold(symbol, buyAt, sellAt),
			sellPrice,
			HoldHighBasis.DAILY,
			candle -> LocalDateTime.of(candle.sourceTime().toLocalDate(), DAY_END_LABEL));
	}

	/**
	 * {@code [from, to]}(양 끝 포함) 안의 1분봉만 남긴다.
	 *
	 * <p><b>받은 봉을 반드시 다시 걸러야 한다.</b> 운영 공급자 {@code BithumbRestCandleProvider}는 빗썸에
	 * {@code to}와 {@code count = 분차 + 1}만 보내고 <b>{@code from}을 하한으로 보내지 않는다</b>. 그래서 시장에
	 * 빠진 분이 있으면 그 개수만큼 <b>{@code from} 이전 봉이 따라온다.</b> 거르지 않으면 그 봉이 최고가일 때
	 * {@code holdHighPrice}·{@code holdHighAt}·{@code sellVsHighRate}·반사실 {@code atHoldHigh}가 <b>보유하지
	 * 않은 구간의 가격</b>으로 나가고, {@code postSellHighPrice}는 매도 <b>이전</b> 가격을 "매도 후 최고가"로
	 * 올려 배타 경계의 근거가 무너진다 — <b>예외도 로그도 남지 않는다.</b>
	 *
	 * <p>같은 클래스의 {@link #dailyCandlesWithinHold}·{@link #scenarioAtFirstMoveAfterBuy}와 주식 경로가 이미
	 * 같은 이유로 같은 필터를 걸고 있다 — <b>중복이 아니다.</b>
	 */
	private List<CryptoCandleDto> minuteCandlesWithin(String symbol, LocalDateTime from, LocalDateTime to) {
		return candles(symbol, CandleInterval.ONE_MINUTE, from, to).stream()
			.filter(candle -> !candle.sourceTime().isBefore(from) && !candle.sourceTime().isAfter(to))
			.toList();
	}

	/**
	 * 봉 조회 — <b>공급자 장애를 회고 전체의 실패로 만들지 않는다.</b>
	 *
	 * <p>빗썸 5xx·타임아웃이면 {@code CandleQueryService}가
	 * {@link ErrorCode#MARKET_DATA_PROVIDER_ERROR}(502)를 던지는데, 그대로 올려보내면 이미 다 만들어 둔
	 * <b>원장 수치·보유 구간 카드·서술까지 포함한 200이 통째로 사라진다.</b> 계약({@code api-contracts.md}
	 * 실패 처리 표)은 반대로 적고 있다 — "일봉을 못 받으면 관련 값 넷이 {@code null}이고 {@code status}는
	 * 게이트대로 {@code READY}"다. 가격을 못 구한 것과 회고를 못 만든 것은 다르다.
	 *
	 * <p>다른 {@code ErrorCode}는 그대로 올린다 — 여기서 흡수할 근거가 있는 것은 <b>공급자 장애</b> 하나뿐이고,
	 * 나머지까지 삼키면 진짜 결함이 조용히 빈 값으로 나간다. 흡수한 경우에도 {@code WARN}을 남겨 "값이 왜
	 * 비었는가"를 추적할 수 있게 한다.
	 */
	private List<CryptoCandleDto> candles(
		String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		try {
			return candleQueryService.getCryptoCandles(symbol, interval, from, to);
		} catch (BusinessException ex) {
			if (ex.getErrorCode() != ErrorCode.MARKET_DATA_PROVIDER_ERROR) {
				throw ex;
			}
			log.warn("코인 봉 조회에 실패해 관련 값을 비운 채 회고를 만든다. 종목={} 간격={} 구간={}~{}",
				symbol, interval, from, to, ex);
			return List.of();
		}
	}

	/**
	 * 보유 구간의 일봉 표본 — <b>매수 시각 이후·매도 시각 이전에 확정된 일봉</b>만 남긴다 (§FEED-012 결정 4).
	 *
	 * <p><b>이 조건이 규칙의 핵심이다.</b> 일봉 {@code close}가 확정되는 시각은 그 일자의 {@code 24:00}이므로,
	 * 남는 것은 <b>매수일 ~ 매도 전날</b>의 일봉이다. 구간을 매수일~매도일로 통째로 잡으면 <b>매도일 일봉의
	 * close(매도 다음날 자정)가 보유하지 않은 구간의 가격</b>이라 "안 팔았다면 얻을 수 있었던 값"이 아니게 된다 —
	 * 그건 근사가 아니라 <b>틀린 값</b>이다.
	 *
	 * <p>조건을 붙이면 남는 값이 전부 보유 중 실제로 존재했던 가격이므로 이 계산은 거짓을 말하지 않고 <b>표본이
	 * 성길 뿐</b>이다. 결과는 실제 최고가 <b>이하</b>다 — 그 사실을 계약이 적고 있고 응답의 {@code holdHighBasis}가
	 * 화면에 알린다.
	 *
	 * @return 조건을 만족하는 일자가 하나도 없으면(같은 날 안에서 {@link #MAX_MINUTE_SPAN_MINUTES} 초과 보유)
	 *     빈 목록 — 호출부에서 {@link HoldExtremes#absent()}가 되어 극값과 {@code atHoldHigh}가 전부
	 *     {@code null}이다
	 */
	private List<CryptoCandleDto> dailyCandlesWithinHold(
		String symbol, LocalDateTime buyAt, LocalDateTime sellAt) {
		LocalDate firstDay = buyAt.toLocalDate();
		// 마지막 대상 일자 D 의 조건은 (D + 1일) 00:00 <= sellAt 이고, 이는 매도 시각과 무관하게 D <= 매도일 − 1일
		// 로 정리된다 — 매도일 자정(그 일봉의 확정 시각)은 언제나 매도 시각보다 뒤이기 때문이다.
		LocalDate lastDay = sellAt.toLocalDate().minusDays(1);
		if (lastDay.isBefore(firstDay)) {
			return List.of();
		}
		return candles(symbol, CandleInterval.ONE_DAY, firstDay.atStartOfDay(), lastDay.atStartOfDay())
			.stream()
			.filter(candle -> !candle.sourceTime().toLocalDate().isBefore(firstDay))
			.filter(candle -> !candle.sourceTime().toLocalDate().isAfter(lastDay))
			.toList();
	}

	/**
	 * 봉 목록에서 {@code close} 극값과 두 비율을 만든다. 주식 경로와 같은 규칙이다.
	 *
	 * <p>극값이 동률이면 <b>이른 봉</b>을 고른다 — 화면이 극값 시각을 서술의 근거로 쓰므로, 같은 종가가 여러 번
	 * 나온 구간에서 뒤쪽을 고르면 그 서술이 실행마다 달라진다.
	 *
	 * @param at 봉의 시각을 응답 시각으로 옮기는 규칙. 1분봉은 그대로이고 일봉은 그 일자의 {@code 23:59}다
	 */
	private static HoldExtremes extremesOf(
		List<CryptoCandleDto> candles,
		BigDecimal sellPrice,
		HoldHighBasis basis,
		Function<CryptoCandleDto, LocalDateTime> at) {
		if (candles.isEmpty()) {
			return HoldExtremes.absent();
		}

		CryptoCandleDto high = candles.get(0);
		CryptoCandleDto low = candles.get(0);
		for (CryptoCandleDto candle : candles) {
			if (candle.close().compareTo(high.close()) > 0) {
				high = candle;
			}
			if (candle.close().compareTo(low.close()) < 0) {
				low = candle;
			}
		}

		return new HoldExtremes(
			high.close(),
			at.apply(high),
			low.close(),
			at.apply(low),
			// sellVsHighRate·sellVsLowRate = (매도가 − 극값) ÷ 극값. 아래 sellToCloseRate와 기준가 자리가 반대다.
			PostSellArithmetic.rateAgainst(sellPrice, high.close()),
			PostSellArithmetic.rateAgainst(sellPrice, low.close()),
			basis);
	}

	/**
	 * 매도 이후 그 날짜가 끝날 때까지의 흐름 (§파생 사실 계산의 {@code [매도 후 흐름]} · §FEED-012 결정 2).
	 *
	 * <p><b>{@code postSellHighPrice}에는 일봉 근사를 쓰지 않는다.</b> 매도~자정에 걸친 일봉은 그 하루 하나뿐인데
	 * <b>그 일봉은 매도 전 시간대를 통째로 포함</b>하므로, "매도 후 최고가"로 올리면 근사가 아니라 틀린 값이다.
	 * 구간이 200봉을 넘으면 {@code null}로 둔다 — 20시에 판 체결은 자정까지 4시간이라 실제로 흔하다.
	 *
	 * <p><b>{@code status}는 게이트만 반영하고 데이터 유무를 반영하지 않는다</b>(§C-4). 게이트가 열렸는데 일봉이
	 * 없으면 {@code READY}에 값만 {@code null}이다 — 없는 데이터를 {@code NOT_YET}으로 감추면 자정이 지난 뒤에도
	 * 영원히 "아직"으로 보인다.
	 */
	private PostSellFlow buildPostSellFlow(
		String symbol, boolean dayClosed, BigDecimal closePrice, BigDecimal sellPrice, LocalDateTime sellAt) {
		if (!dayClosed) {
			return new PostSellFlow(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null);
		}

		CryptoCandleDto postSellHigh = highestCloseAfterSell(symbol, sellAt);
		return new PostSellFlow(
			PostSellFeedbackStatus.READY,
			closePrice,
			closePrice == null ? null : LocalDateTime.of(sellAt.toLocalDate(), DAY_END_LABEL),
			// sellToCloseRate = (종가 − 매도가) ÷ 매도가. 극값 두 비율과 기준가 자리가 뒤바뀐다.
			PostSellArithmetic.rateAgainst(closePrice, sellPrice),
			postSellHigh == null ? null : postSellHigh.close(),
			postSellHigh == null ? null : postSellHigh.sourceTime());
	}

	/**
	 * 매도 시각이 속한 KST 일자의 일봉 {@code close} — 코인의 "종가"다 (§FEED-012 결정 2).
	 *
	 * <p>주식이 "그날 마지막 분봉"을 쓰는 자리인데, 코인에는 마지막 분봉이라 부를 시각이 없어 <b>일봉 버킷의
	 * 종가</b>를 쓴다. 버킷 경계가 우리가 만든 것이 아니라 공급자 계약에 있다는 점이 이 선택의 근거다.
	 *
	 * @return 그 일자의 일봉을 못 받으면 {@code null} — 가격을 지어내지 않는다
	 */
	private BigDecimal sellDayClose(String symbol, LocalDateTime sellAt) {
		LocalDate sellDate = sellAt.toLocalDate();
		return candles(symbol, CandleInterval.ONE_DAY, sellDate.atStartOfDay(), sellDate.atStartOfDay())
			.stream()
			.filter(candle -> candle.sourceTime().toLocalDate().equals(sellDate))
			.findFirst()
			.map(CryptoCandleDto::close)
			.orElse(null);
	}

	/**
	 * 매도 시각 <b>이후</b> 그 날짜 끝까지의 {@code close} 최댓값.
	 *
	 * <p>경계를 <b>배타</b>로 둔 것은 보유 구간 극값이 매도 봉을 <b>포함</b>하기 때문이다(§파생 사실 계산이
	 * "양 끝 포함"으로 정했다) — 같은 봉이 "보유 중 최고가"와 "매도 후 최고가"에 동시에 잡히면 화면이 두 값을
	 * 나란히 놓는 의미가 없어진다.
	 *
	 * @return 구간이 {@link #MAX_MINUTE_SPAN_MINUTES}를 넘거나 봉이 없으면 {@code null}
	 */
	private CryptoCandleDto highestCloseAfterSell(String symbol, LocalDateTime sellAt) {
		LocalDateTime from = PostSellArithmetic.onMinuteBoundary(sellAt).plusMinutes(1);
		LocalDateTime to = LocalDateTime.of(sellAt.toLocalDate(), DAY_END_LABEL);
		if (from.isAfter(to) || PostSellArithmetic.minutesBetween(from, to) > MAX_MINUTE_SPAN_MINUTES) {
			return null;
		}
		// minuteCandlesWithin이 구간 밖 봉을 걸러 준다 — 공급자가 from을 하한으로 보내지 않아 매도 봉 이전 가격이
		// 따라오면 그 값이 "매도 후 최고가"로 나가고 배타 경계의 근거가 무너진다.
		return minuteCandlesWithin(symbol, from, to).stream()
			// 동률이면 이른 봉을 고른다 — 극값과 같은 규칙이다.
			.reduce((left, right) -> right.close().compareTo(left.close()) > 0 ? right : left)
			.orElse(null);
	}

	/**
	 * 같은 수량을 다른 시점에 팔았다면 어땠을지 (§반사실·집단 비교 계산). 게이트는 {@code postSellFlow}와 같다.
	 *
	 * <p><b>수수료율이 코인 것이다</b>({@code 0.0005}) — 주식 요율을 쓰면 세 시나리오의 수익률이 예외도 로그도
	 * 없이 어긋난다. 값은 {@link PostSellArithmetic#feeRateOf}가 시장으로 고른다.
	 */
	private Counterfactuals buildCounterfactuals(
		String symbol,
		boolean dayClosed,
		BigDecimal closePrice,
		HoldExtremes extremes,
		List<HeldPriceMoveItemResponse> priceMoves,
		BigDecimal quantity,
		long buyBasis,
		LocalDateTime sellAt) {
		if (!dayClosed) {
			return new Counterfactuals(PostSellFeedbackStatus.NOT_YET, null, null, null);
		}

		BigDecimal feeRate = PostSellArithmetic.feeRateOf(Market.CRYPTO);
		return new Counterfactuals(
			PostSellFeedbackStatus.READY,
			closePrice == null
				? null
				: new CounterfactualScenario(
					closePrice,
					LocalDateTime.of(sellAt.toLocalDate(), DAY_END_LABEL),
					PostSellArithmetic.counterfactualReturnRate(closePrice, quantity, buyBasis, feeRate)),
			extremes.holdHighPrice() == null
				? null
				: new CounterfactualScenario(
					extremes.holdHighPrice(),
					extremes.holdHighAt(),
					PostSellArithmetic.counterfactualReturnRate(
						extremes.holdHighPrice(), quantity, buyBasis, feeRate)),
			scenarioAtFirstMoveAfterBuy(symbol, priceMoves, quantity, buyBasis, feeRate));
	}

	/**
	 * {@code atFirstMoveAfterBuy} — 보유 구간 안 <b>첫 변동 카드</b> 시점의 종가다.
	 *
	 * <p><b>일봉 근사를 쓰지 않는다.</b> 이 값은 카드 시점이라는 <b>특정 분의 종가</b>이므로 일 단위 표본으로
	 * 대체할 대상이 아니다 — 그 분의 1분봉을 못 구하면 {@code null}이다(주식도 같은 이유로 {@code null}이다).
	 * 카드가 최근이면 그 한 봉만 조회하면 되므로 200봉 상한에 걸리지 않는다.
	 *
	 * @return 보유 구간에 카드가 0건이면 {@code null}. 코인 카드는 근거 기사가 없으면 생성되지 않고 수집이 30분
	 *     주기라 <b>0건이 오히려 흔한 경우다</b>
	 */
	private CounterfactualScenario scenarioAtFirstMoveAfterBuy(
		String symbol,
		List<HeldPriceMoveItemResponse> priceMoves,
		BigDecimal quantity,
		long buyBasis,
		BigDecimal feeRate) {
		if (priceMoves.isEmpty()) {
			return null;
		}
		LocalDateTime at = PostSellArithmetic.onMinuteBoundary(priceMoves.get(0).windowEnd());
		return candles(symbol, CandleInterval.ONE_MINUTE, at, at).stream()
			.filter(candle -> PostSellArithmetic.onMinuteBoundary(candle.sourceTime()).equals(at))
			.findFirst()
			.map(candle -> new CounterfactualScenario(
				candle.close(),
				at,
				PostSellArithmetic.counterfactualReturnRate(candle.close(), quantity, buyBasis, feeRate)))
			.orElse(null);
	}

	/**
	 * {@code buyToNewsMinutes = (T0 − 매수시각)} 분. {@code T0}는 보유 구간 카드의 근거 기사 중 가장 이른
	 * 발행시각이다 (§파생 사실 계산).
	 *
	 * <p><b>부호를 뒤집지 않는다</b> — 매수가 기사보다 앞이면 <b>양수</b>다. 부호가 반대면 화면이 "기사를 보고
	 * 매수했다"와 "기사 전에 매수했다"를 정확히 거꾸로 말한다.
	 */
	private static Integer buyToNewsMinutes(LocalDateTime buyAt, List<HeldPriceMoveItemResponse> priceMoves) {
		return priceMoves.stream()
			.flatMap(move -> move.sources().stream())
			.map(NewsItem::publishedAt)
			.min(Comparator.naturalOrder())
			.map(firstNewsAt -> PostSellArithmetic.minutesBetween(buyAt, firstNewsAt))
			.orElse(null);
	}

}
