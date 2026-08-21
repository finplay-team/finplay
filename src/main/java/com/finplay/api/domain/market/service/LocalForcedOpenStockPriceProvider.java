// 재생세션이 READY이면 장외 시간에도 시장상태를 OPEN으로 강제해 주문까지 시험할 수 있게 하는 StockPriceProvider 데코레이터 (local 프로필 전용)
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// StockReplayService.computeMarketStatus는 재생세션 READY 여부뿐 아니라 벽시계 시각(09:00~15:30)과 영업일 여부까지
// 함께 요구한다 — 데이터를 시드해도 밤이나 주말에는 주문이 MARKET_CLOSED로 막힌다. 그 판정을 프로덕션 클래스 안에서
// 개발 플래그로 분기하는 대신 인터페이스를 감싸는 방식을 택했다: StockReplayService·PriceQueryService·SSE 서비스는
// 아무 변경 없이 그대로 두고, local 프로필에서만 존재하는 이 빈이 시장상태만 덮어쓴다.
//
// 주문은 getCurrentPrice를 통한 getOrderExecutionPrice quote를 사용하고 SSE snapshot도 단건 getCurrentPrice(단건
// getPriceQuote 경유)를 사용한다. 다건 getCurrentPrices는 HoldingValuationService의 평가손익 조회가 유일 소비자이며
// marketStatus는 읽지 않고 가격만 꺼내 쓴다. 단건·다건 quote 모두 같은 강제 OPEN 변환을 적용하며 status 이벤트의
// getMarketStatus와도 일치시킨다 — 화면에 OPEN으로 보이는 동안 주문도 실제로 통과한다.
@Service
@Primary
@Profile("local")
public class LocalForcedOpenStockPriceProvider implements StockPriceProvider {

	// 인터페이스(StockPriceProvider)가 아니라 구체 타입을 주입한다 — 이 빈 자신이 @Primary라 인터페이스로 받으면
	// 자기 자신을 주입해 순환 참조가 된다.
	private final KisHistoricalReplayPriceProvider delegate;
	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final Clock clock;
	private final boolean forceMarketOpen;

	// @Value를 받는 파라미터가 있어 @RequiredArgsConstructor를 쓸 수 없다 — Lombok은 필드의 @Value를 생성자
	// 파라미터로 복사하지 않고(바이트코드로 확인), 그러면 Spring이 boolean 타입 빈을 찾다 기동에 실패한다.
	// forceMarketOpen 기본값은 false — 데이터는 READY인데 시장은 CLOSED인 정직한 상태도 로컬에서 그대로 확인할 수
	// 있어야 하기 때문이다. local 프로필 활성화와 이 플래그, 두 개의 독립 스위치를 모두 켜야 시장상태가 덮어써진다.
	public LocalForcedOpenStockPriceProvider(
		KisHistoricalReplayPriceProvider delegate,
		StockReplaySessionRepository stockReplaySessionRepository,
		Clock clock,
		@Value("${finplay.dev.stock.force-market-open:false}")
		boolean forceMarketOpen) {
		this.delegate = delegate;
		this.stockReplaySessionRepository = stockReplaySessionRepository;
		this.clock = clock;
		this.forceMarketOpen = forceMarketOpen;
	}

	@Override
	@Transactional(readOnly = true)
	public StockMarketStatus getMarketStatus() {
		StockMarketStatus actual = delegate.getMarketStatus();
		if (!forceMarketOpen || actual == StockMarketStatus.OPEN) {
			return actual;
		}
		boolean sessionReady = stockReplaySessionRepository
			.findByServiceDate(LocalDate.now(clock))
			.filter(session -> session.getPreparationStatus() == PreparationStatus.READY)
			.isPresent();
		// 시드하지 않았으면(재생할 데이터가 없으면) 정직하게 실제 상태를 그대로 반환한다.
		return sessionReady ? StockMarketStatus.OPEN : actual;
	}

	@Override
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return forceOpenWhenReady(delegate.getCurrentPrice(instrumentId));
	}

	private StockReplayPriceDto forceOpenWhenReady(StockReplayPriceDto quote) {
		if (!forceMarketOpen || quote.marketStatus() == StockMarketStatus.OPEN || !quote.sessionReady()) {
			return quote;
		}
		return new StockReplayPriceDto(
			quote.sessionReady(), StockMarketStatus.OPEN, quote.sourceTradingDate(), quote.price(), quote.sourceTime(),
			quote.replaySession());
	}

	@Override
	public List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds) {
		return delegate.getCurrentPrices(instrumentIds).stream().map(this::forceOpenWhenReady).toList();
	}

	@Override
	public List<StockCandleDto> getCandles(
		Long instrumentId, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		return delegate.getCandles(instrumentId, interval, from, to);
	}
}
