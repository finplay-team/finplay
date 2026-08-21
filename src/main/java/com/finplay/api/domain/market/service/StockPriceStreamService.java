// 주식 SSE 스트림(/api/stocks/stream)의 snapshot 구성과 매분 price·status 이벤트 push를 담당하는 서비스 (이슈 #19)
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.domain.market.dto.sse.MarketStatusEvent;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceStreamService {

	private static final DateTimeFormatter EVENT_ID_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

	private final InstrumentRepository instrumentRepository;
	private final PriceQueryService priceQueryService;
	private final StockPriceProvider stockPriceProvider;
	private final SseEmitterRegistry sseEmitterRegistry;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	// 심볼별 마지막으로 전송한 가격 상태 — 매분 새로 공개된 가격만 골라 push하기 위한 비교 기준 (인스턴스 단일, 회원별 복사본 아님).
	private final Map<String, PriceQuoteDto> lastKnownQuotes = new ConcurrentHashMap<>();
	private volatile StockMarketStatus lastKnownMarketStatus;

	// 서버 기동 시점의 현재 상태를 기준선으로 세팅해, 기동 직후 첫 스케줄 실행에서 "변경"으로 오판해 이벤트를 쏘지 않게 한다.
	// @PostConstruct는 프록시 생성 이전 타깃 인스턴스에서 실행돼 @Transactional을 붙여도 적용되지 않는다 — 대신
	// priceQueryService.getPriceQuote(Instrument)가 그 자체로 자기 완결적 트랜잭션을 여닫으므로(별도 빈 호출이라
	// self-invocation이 아님) 여기서는 트랜잭션이 필요 없다 (PR #94 리뷰).
	@PostConstruct
	public void initializeBaseline() {
		for (Instrument instrument : getStockInstruments()) {
			lastKnownQuotes.put(instrument.getSymbol(), priceQueryService.getPriceQuote(instrument));
		}
		lastKnownMarketStatus = stockPriceProvider.getMarketStatus();
	}

	// 구독 직후 전송할 snapshot — 주식 16종 전체(가격 없는 종목도 UNAVAILABLE로 포함), id 없음.
	public MarketSnapshotEvent buildSnapshot() {
		List<Instrument> instruments = getStockInstruments();
		StockMarketStatus marketStatus = stockPriceProvider.getMarketStatus();
		LocalDate sourceTradingDate = null;
		List<InstrumentPriceSnapshot> prices = new ArrayList<>();
		for (Instrument instrument : instruments) {
			PriceQuoteDto quote = priceQueryService.getPriceQuote(instrument);
			if (sourceTradingDate == null) {
				sourceTradingDate = quote.sourceTradingDate();
			}
			prices.add(InstrumentPriceSnapshot.of(instrument.getSymbol(), quote.price(), quote.sourceTime(),
				quote.status()));
		}
		return new MarketSnapshotEvent(Market.STOCK, sourceTradingDate, marketStatus, LocalDateTime.now(clock),
			prices);
	}

	// 구독 직후 컨트롤러가 호출 — snapshot 1건을 해당 emitter에만 전송한다 (재접속 시에도 이 경로로 재전송된다).
	// @Transactional을 buildSnapshot()이 아니라 여기(실제 호출 진입점)에 붙여야 한다 — 같은 클래스 내부 호출은
	// 프록시를 경유하지 않아(self-invocation) buildSnapshot()에 애노테이션이 있어도 무시되고, 그 안의 16회 조회가
	// 각각 별도 트랜잭션(별도 커넥션)으로 나갔다 (PR #94 리뷰). 이 메서드는 반드시 컨트롤러처럼 이 빈 바깥에서
	// (프록시를 거쳐) 호출해야 한다 — 이 클래스 내부에서 this.sendSnapshot(...)으로 호출하면 같은 self-invocation
	// 문제가 재발한다 (PR #94 후속 리뷰).
	@Transactional(readOnly = true)
	public void sendSnapshot(SseEmitter emitter) {
		send(emitter, SseEmitter.event().name("snapshot").data(buildSnapshot()));
	}

	// 구독용 emitter만 생성한다 — 아직 SseEmitterRegistry의 활성 브로드캐스트 집합에는 들어가지 않는다. 호출자
	// (컨트롤러)가 이 emitter로 sendSnapshot()을 호출해 초기 데이터를 보낸 뒤 activate()를 호출해야 매분 브로드캐스트
	// 대상이 된다. 순서를 이렇게 나누면 등록과 broadcast 사이의 경합 자체가 사라져 락이 필요 없다 (PR #94 후속 리뷰 —
	// 원래 리뷰가 제안한 "등록과 스냅샷 전송의 순서를 바꿔라"를 그대로 적용).
	public SseEmitter createEmitter() {
		return sseEmitterRegistry.createEmitter(Market.STOCK);
	}

	// createEmitter()로 만든 emitter를 활성 브로드캐스트 집합에 추가한다. 반드시 sendSnapshot() 호출 이후에
	// 호출해야 새 구독자가 snapshot보다 price를 먼저 받는 경합이 생기지 않는다.
	public void activate(SseEmitter emitter) {
		sseEmitterRegistry.activate(Market.STOCK, emitter);
	}

	// 매분 정각 실행 — 새로 공개된 가격만 price 이벤트로 push, 개장·마감 전환 시 status 이벤트를 1회만 push한다.
	// DB 조회(collectScheduledUpdate)는 transactionTemplate으로 단일 트랜잭션에 묶어 emitter 전송(블로킹 I/O)과
	// 분리한다 — DB 커넥션이 SSE 전송까지 붙들려 있지 않게 한다 (PR #94 리뷰). emitter 전송 구간에는 더 이상 락이
	// 없다 — createEmitter()/activate()가 이 broadcast 대상 집합에 아직 없는 emitter를 건드리지 않으므로 상호배제가
	// 필요 없고, 락이 있었다면 전송이 느린 클라이언트 하나가 이 broadcast 루프에서 멈출 때 새 구독 등록까지 함께
	// 막혔을 것이다 (PR #94 후속 리뷰).
	@Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
	public void publishScheduledUpdates() {
		ScheduledUpdate update = transactionTemplate.execute(status -> collectScheduledUpdate());
		for (InstrumentPriceUpdate priceUpdate : update.priceUpdates()) {
			broadcastPriceEvent(priceUpdate.symbol(), priceUpdate.quote(), update.marketStatus());
		}
		if (update.marketStatusChanged()) {
			broadcastStatusEvent(update.marketStatus());
			lastKnownMarketStatus = update.marketStatus();
		}
	}

	// 매분 갱신의 DB 조회 부분만 담당 — 16종의 가격을 한 트랜잭션에서 조회하고 새로 공개된 가격 목록·시장상태 변경
	// 여부를 값으로 반환한다. emitter 전송은 이 메서드가 끝난 뒤(트랜잭션 밖) 호출자가 수행한다.
	private ScheduledUpdate collectScheduledUpdate() {
		StockMarketStatus currentMarketStatus = stockPriceProvider.getMarketStatus();
		List<InstrumentPriceUpdate> priceUpdates = new ArrayList<>();
		for (Instrument instrument : getStockInstruments()) {
			String symbol = instrument.getSymbol();
			PriceQuoteDto currentQuote = priceQueryService.getPriceQuote(instrument);
			PriceQuoteDto previousQuote = lastKnownQuotes.get(symbol);
			if (isNewlyRevealedPrice(previousQuote, currentQuote)) {
				priceUpdates.add(new InstrumentPriceUpdate(symbol, currentQuote));
			}
			lastKnownQuotes.put(symbol, currentQuote);
		}
		boolean marketStatusChanged = currentMarketStatus != lastKnownMarketStatus;
		return new ScheduledUpdate(priceUpdates, currentMarketStatus, marketStatusChanged);
	}

	private record InstrumentPriceUpdate(String symbol, PriceQuoteDto quote) {
	}

	private record ScheduledUpdate(List<InstrumentPriceUpdate> priceUpdates, StockMarketStatus marketStatus,
		boolean marketStatusChanged) {
	}

	private boolean isNewlyRevealedPrice(PriceQuoteDto previous, PriceQuoteDto current) {
		if (current.status() != PriceStatus.AVAILABLE) {
			return false;
		}
		return previous == null || previous.status() != PriceStatus.AVAILABLE
			|| !Objects.equals(previous.sourceTime(), current.sourceTime());
	}

	private void broadcastPriceEvent(String symbol, PriceQuoteDto quote, StockMarketStatus marketStatus) {
		MarketPriceEvent payload = new MarketPriceEvent(Market.STOCK, symbol, quote.price(), quote.sourceTime(),
			LocalDateTime.now(clock), quote.sourceTradingDate(), marketStatus);
		String eventId = "STOCK:%s:%s".formatted(symbol, quote.sourceTime().format(EVENT_ID_TIME_FORMAT));
		for (SseEmitter emitter : sseEmitterRegistry.getEmitters(Market.STOCK)) {
			send(emitter, SseEmitter.event().name("price").id(eventId).data(payload));
		}
	}

	private void broadcastStatusEvent(StockMarketStatus marketStatus) {
		MarketStatusEvent payload = new MarketStatusEvent(Market.STOCK, null, marketStatus, null, null,
			LocalDateTime.now(clock));
		for (SseEmitter emitter : sseEmitterRegistry.getEmitters(Market.STOCK)) {
			send(emitter, SseEmitter.event().name("status").data(payload));
		}
	}

	private List<Instrument> getStockInstruments() {
		return instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK);
	}

	// emitter 하나의 전송 실패가 나머지 구독자의 push를 막지 않게 개별 처리한다. 실패한 emitter는 completeWithError로
	// 종료시켜 SseEmitterRegistry에 배선된 onError 콜백이 집합에서 제거하도록 위임한다 (이슈 #18 계약 재사용).
	private void send(SseEmitter emitter, SseEmitter.SseEventBuilder eventBuilder) {
		try {
			emitter.send(eventBuilder);
		} catch (IOException | RuntimeException e) {
			log.debug("SSE 이벤트 전송 실패로 emitter 종료", e);
			emitter.completeWithError(e);
		}
	}
}
