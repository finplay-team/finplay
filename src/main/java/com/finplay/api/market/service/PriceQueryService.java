// 주문·화면·평가손익이 공통으로 소비하는 "유효한 최신 가격" 조회 서비스. 주식은 주입된 StockPriceProvider(구현체 불명)에, 코인은 PriceStore(Redis)에 위임한다.
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PriceQueryService {

	private final InstrumentRepository instrumentRepository;
	private final StockPriceProvider stockPriceProvider;
	private final PriceStore priceStore;
	private final TutorialSampleInstrumentPriceService tutorialSampleInstrumentPriceService;

	// 가격이 없으면 PRICE_UNAVAILABLE 예외를 던진다 — 가격 API의 409 계약 (이슈 #16 확정, 동작 변경 없음). getPriceQuote를 감싸 판정 로직을 중복하지 않는다.
	@Transactional(readOnly = true)
	public PriceQuoteDto getPrice(Long instrumentId) {
		return requireAvailable(getPriceQuote(instrumentId));
	}

	// 주문 체결 전용 — 주식의 주문 가능 상태·가격·재생세션을 공급자의 같은 관측 결과로 확정한다.
	@Transactional(readOnly = true)
	public OrderExecutionPriceDto getOrderExecutionPrice(Instrument instrument) {
		// 샘플 종목은 실제 시세 인프라(stockPriceProvider·재생세션)를 완전히 우회한다 — 항상 AVAILABLE·OPEN, replaySession=null (SANDBOX-003)
		if (instrument.isTutorialSample()) {
			return new OrderExecutionPriceDto(tutorialSampleInstrumentPriceService.getPriceQuote(instrument), null);
		}
		if (instrument.getMarket() == Market.CRYPTO) {
			return new OrderExecutionPriceDto(requireAvailable(getCryptoExecutionPriceQuote(instrument)), null);
		}

		StockReplayPriceDto stockQuote = stockPriceProvider.getCurrentPrice(instrument.getId());
		if (stockQuote.marketStatus() == StockMarketStatus.CLOSED) {
			throw new BusinessException(ErrorCode.MARKET_CLOSED);
		}
		if (stockQuote.replaySession() == null) {
			throw new BusinessException(ErrorCode.MARKET_CLOSED);
		}
		PriceQuoteDto priceQuote = stockQuote.isPriceAvailable()
			? new PriceQuoteDto(
				stockQuote.price(), stockQuote.sourceTime(), PriceStatus.AVAILABLE,
				stockQuote.sourceTradingDate())
			: new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, stockQuote.sourceTradingDate());
		return new OrderExecutionPriceDto(requireAvailable(priceQuote), stockQuote.replaySession());
	}

	// 가격이 없어도 예외를 던지지 않고 status=UNAVAILABLE로 표현한다 — SSE snapshot·price처럼 "가격 없음"도 정상 응답인 소비자를 위한 경로 (이슈 #18).
	@Transactional(readOnly = true)
	public PriceQuoteDto getPriceQuote(Long instrumentId) {
		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return getPriceQuote(instrument);
	}

	@Transactional(readOnly = true)
	public PriceQuoteDto getPriceQuote(Instrument instrument) {
		if (instrument.isTutorialSample()) {
			return tutorialSampleInstrumentPriceService.getPriceQuote(instrument);
		}
		return instrument.getMarket() == Market.STOCK ? getStockPriceQuote(instrument)
			: getCryptoDisplayPriceQuote(instrument);
	}

	// 계좌(=market) 단위로 여러 종목의 시세를 한 번에 조회한다 — 호출측(HoldingValuationService 등)이 이미 계좌 단위로 종목을
	// 모아서 넘기므로 instruments는 전부 같은 market이라고 가정한다 (PR #97 리뷰 권장사항, 다음 이슈 #51 착수 전 정리).
	// 반환 순서는 instruments 순서와 일치한다. 이 가정이 깨지면(market 혼재) 호출측 버그이므로 방어적으로 예외를 던진다
	// (#51이 계좌 두 개를 다루기 시작하면 실수로 섞어 넘길 위험이 커지므로 지금 막아둔다).
	@Transactional(readOnly = true)
	public List<PriceQuoteDto> getPriceQuotes(List<Instrument> instruments) {
		if (instruments.isEmpty()) {
			return List.of();
		}
		// 샘플 종목은 분할해 개별 처리하고, market 혼재 방어 검사는 실제 종목 부분집합에만 적용한 뒤 원래 순서로 병합한다
		List<Instrument> realInstruments = instruments.stream().filter(instrument -> !instrument.isTutorialSample())
			.toList();
		Map<Instrument, PriceQuoteDto> realQuotesByInstrument = new IdentityHashMap<>();
		if (!realInstruments.isEmpty()) {
			Market market = realInstruments.get(0).getMarket();
			if (realInstruments.stream().anyMatch(instrument -> instrument.getMarket() != market)) {
				throw new IllegalArgumentException("getPriceQuotes는 서로 다른 market이 섞인 종목 목록을 받을 수 없습니다.");
			}
			List<PriceQuoteDto> realQuotes = market == Market.STOCK ? getStockPriceQuotes(realInstruments)
				: getCryptoDisplayPriceQuotes(realInstruments);
			for (int i = 0; i < realInstruments.size(); i++) {
				realQuotesByInstrument.put(realInstruments.get(i), realQuotes.get(i));
			}
		}
		return instruments.stream()
			.map(instrument -> instrument.isTutorialSample()
				? tutorialSampleInstrumentPriceService.getPriceQuote(instrument)
				: realQuotesByInstrument.get(instrument))
			.toList();
	}

	private List<PriceQuoteDto> getStockPriceQuotes(List<Instrument> instruments) {
		List<Long> instrumentIds = instruments.stream().map(Instrument::getId).toList();
		List<StockReplayPriceDto> quotes = stockPriceProvider.getCurrentPrices(instrumentIds);
		return quotes.stream()
			.map(quote -> quote.isPriceAvailable()
				? new PriceQuoteDto(quote.price(), quote.sourceTime(), PriceStatus.AVAILABLE, quote.sourceTradingDate())
				: new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, quote.sourceTradingDate()))
			.toList();
	}

	// 표시 전용 배치 판정 — 연결상태는 요청당 1회만 조회해 재사용하고(PR #97 리뷰 권장사항), 심볼별 최신가 조회·신선도 판정만
	// 반복한다. 단건 getCryptoDisplayPriceQuote와 동일한 규칙(연결 끊김→UNAVAILABLE, 연결 유지+fresh→AVAILABLE,
	// 연결 유지+stale→STALE, 연결 유지+수신 이력 없음→UNAVAILABLE)이다 (PRICE-STALE-001).
	private List<PriceQuoteDto> getCryptoDisplayPriceQuotes(List<Instrument> instruments) {
		if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return instruments.stream().map(instrument -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null))
				.toList();
		}
		return instruments.stream()
			.map(instrument -> priceStore.getLatestPrice(instrument.getSymbol())
				.map(price -> new PriceQuoteDto(price.price(), price.receivedAt(),
					priceStore.isStale(price.receivedAt()) ? PriceStatus.STALE : PriceStatus.AVAILABLE, null))
				.orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null)))
			.toList();
	}

	private PriceQuoteDto requireAvailable(PriceQuoteDto quote) {
		if (quote.status() == PriceStatus.UNAVAILABLE) {
			throw new BusinessException(ErrorCode.PRICE_UNAVAILABLE);
		}
		return quote;
	}

	// 어느 StockPriceProvider 구현체(MVP는 KisHistoricalReplayPriceProvider 하나뿐)가 동작 중인지 알지 못한 채 인터페이스로만 위임한다.
	private PriceQuoteDto getStockPriceQuote(Instrument instrument) {
		StockReplayPriceDto quote = stockPriceProvider.getCurrentPrice(instrument.getId());
		if (!quote.isPriceAvailable()) {
			return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, quote.sourceTradingDate());
		}
		return new PriceQuoteDto(quote.price(), quote.sourceTime(), PriceStatus.AVAILABLE, quote.sourceTradingDate());
	}

	// 주문 체결 전용 판정 — 표시 경로(getCryptoDisplayPriceQuote)가 생기기 전의 원본 로직 그대로다(이름만 변경, PRICE-STALE-003).
	// AVAILABLE·UNAVAILABLE만 반환하며 stale은 UNAVAILABLE로 fail-closed 처리한다(MKT-004 무변경).
	private PriceQuoteDto getCryptoExecutionPriceQuote(Instrument instrument) {
		String symbol = instrument.getSymbol();
		if (!priceStore.isPriceAvailable(symbol)) {
			return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null);
		}
		return priceStore
			.getLatestPrice(symbol)
			.map(latestPrice -> new PriceQuoteDto(latestPrice.price(), latestPrice.receivedAt(), PriceStatus.AVAILABLE,
				null))
			.orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));
	}

	// 표시 전용 판정 — 연결 유지 + 수신 이력 있음이면 stale이어도 마지막 가격을 STALE로 보여준다(PRICE-STALE-001).
	// 연결 끊김이거나 수신 이력이 아예 없으면 지금처럼 UNAVAILABLE이다(완화 대상 아님).
	private PriceQuoteDto getCryptoDisplayPriceQuote(Instrument instrument) {
		String symbol = instrument.getSymbol();
		if (priceStore.isPriceAvailable(symbol)) {
			// 연결 유지 + fresh — 기존 AVAILABLE 경로와 완전히 동일한 조회 순서·race 가드
			return priceStore.getLatestPrice(symbol)
				.map(p -> new PriceQuoteDto(p.price(), p.receivedAt(), PriceStatus.AVAILABLE, null))
				.orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));
		}
		if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null); // 연결 끊김 — 완화 대상 아님
		}
		// 연결은 유지되지만 isPriceAvailable()이 false였다 → 신선도만 초과(stale)였거나, 애초에 시세를 받은 적이 없다.
		return priceStore.getLatestPrice(symbol)
			.map(p -> new PriceQuoteDto(p.price(), p.receivedAt(), PriceStatus.STALE, null))
			.orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null)); // 받은 적 없음 — 완화 대상 아님
	}
}
