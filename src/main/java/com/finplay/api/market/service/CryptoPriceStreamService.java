// 코인 SSE 스트림(/api/cryptos/stream)의 snapshot 구성과 price(빗썸 틱)·status(연결상태 변경) push를 담당하는 서비스 (ADR-0018, tasks.md 항목 1)
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.MarketPriceEvent;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.market.dto.sse.MarketStatusEvent;
import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.sse.SseEmitterRegistry;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoPriceStreamService {

	// 코인은 분 단위로는 틱을 구분 못 해 주식(yyyyMMddHHmm)보다 초 단위까지 포함한다 (plan.md SSE 계약 — price 이벤트 id).
	private static final DateTimeFormatter EVENT_ID_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	private final InstrumentRepository instrumentRepository;
	private final PriceQueryService priceQueryService;
	private final PriceStore priceStore;
	private final SseEmitterRegistry sseEmitterRegistry;
	private final Clock clock;

	// 직전에 push한 연결상태 — 5초 폴링에서 직전 값과 달라졌을 때만 1회 push하기 위한 비교 기준
	// (StockPriceStreamService.lastKnownMarketStatus와 같은 패턴, 인스턴스 단일 상태).
	private volatile FeedConnectionStatus lastKnownConnectionStatus;

	// 서버 기동 시점의 연결상태를 기준선으로 세팅해, 기동 직후 첫 스케줄 실행에서 "변경"으로 오판해 이벤트를 쏘지 않게 한다.
	@PostConstruct
	public void initializeBaseline() {
		lastKnownConnectionStatus = priceStore.getConnectionStatus();
	}

	// 구독 직후 전송할 snapshot — 코인 전체(가격 없는 종목도 UNAVAILABLE로 포함), id 없음. 코인은
	// sourceTradingDate·marketStatus(주식 개장상태) 개념이 없어 항상 null이며 MarketSnapshotEvent의
	// @JsonInclude(NON_NULL)로 필드 자체가 생략된다(plan.md SSE 계약).
	public MarketSnapshotEvent buildSnapshot() {
		List<Instrument> instruments = getCryptoInstruments();
		List<InstrumentPriceSnapshot> prices = new ArrayList<>();
		for (Instrument instrument : instruments) {
			PriceQuoteDto quote = priceQueryService.getPriceQuote(instrument);
			prices.add(InstrumentPriceSnapshot.of(instrument.getSymbol(), quote.price(), quote.sourceTime(),
				quote.status()));
		}
		return new MarketSnapshotEvent(Market.CRYPTO, null, null, LocalDateTime.now(clock), prices);
	}

	// 구독 직후 컨트롤러가 호출 — snapshot 1건을 해당 emitter에만 전송한다. @Transactional을 buildSnapshot()이
	// 아니라 이 진입점(프록시를 거치는 public 메서드)에 붙이는 이유는 StockPriceStreamService.sendSnapshot()과 같다
	// (self-invocation이면 프록시를 안 거쳐 무시된다, PR #94 리뷰).
	@Transactional(readOnly = true)
	public void sendSnapshot(SseEmitter emitter) {
		send(emitter, SseEmitter.event().name("snapshot").data(buildSnapshot()));
	}

	// 구독용 emitter만 생성한다 — activate() 전까지는 브로드캐스트 대상이 아니다 (StockPriceStreamService.createEmitter()와 동일한 이유, PR #94 후속 리뷰).
	public SseEmitter createEmitter() {
		return sseEmitterRegistry.createEmitter(Market.CRYPTO);
	}

	// createEmitter()로 만든 emitter를 활성 브로드캐스트 집합에 추가한다. 반드시 sendSnapshot() 호출 이후에 호출해야
	// 새 구독자가 snapshot보다 price를 먼저 받는 경합이 생기지 않는다.
	public void activate(SseEmitter emitter) {
		sseEmitterRegistry.activate(Market.CRYPTO, emitter);
	}

	// PriceStore.saveTick()이 이미 발행하는 기존 CryptoPriceUpdatedEvent를 구독해 price 이벤트로 브로드캐스트한다 —
	// 새 이벤트 타입이 아니다(015-limit-order LMT-002 체결 트리거가 이미 소비 중, plan.md SSE 계약).
	@EventListener
	public void onPriceUpdated(CryptoPriceUpdatedEvent event) {
		MarketPriceEvent payload = new MarketPriceEvent(Market.CRYPTO, event.symbol(), event.price(),
			event.receivedAt(), LocalDateTime.now(clock), null, null);
		// id는 observedAt(관측 시각) 기준으로 만든다 — receivedAt(체결 시각)은 REST 폴러(recordObservation)가
		// 절대 갱신하지 않으므로, 웹소켓 체결 없이 REST만으로 서로 다른 가격이 연달아 감지되면 같은 receivedAt을
		// 실은 이벤트 2건이 같은 id를 갖게 되어 프론트의 SSE id dedup에 두 번째 갱신이 조용히 먹힐 수 있다.
		// observedAt은 이벤트가 발행될 때마다(웹소켓·REST 어느 경로든) 항상 "지금"으로 새로 갱신되므로 이 충돌이
		// 없다(034-crypto-price-rest-backup).
		String eventId = "CRYPTO:%s:%s".formatted(event.symbol(), event.observedAt().format(EVENT_ID_TIME_FORMAT));
		for (SseEmitter emitter : sseEmitterRegistry.getEmitters(Market.CRYPTO)) {
			send(emitter, SseEmitter.event().name("price").id(eventId).data(payload));
		}
	}

	// 코인 연결상태 변경을 알리는 기존 이벤트가 없어 5초 주기로 직전 값과 비교해 변경 시에만 1회 push한다
	// (plan.md "폴링 주기" 근거 — PriceStore의 stale 기준 10초의 절반).
	@Scheduled(fixedRate = 5000)
	public void publishConnectionStatusIfChanged() {
		FeedConnectionStatus currentStatus = priceStore.getConnectionStatus();
		if (currentStatus == lastKnownConnectionStatus) {
			return;
		}
		broadcastStatusEvent(currentStatus);
		lastKnownConnectionStatus = currentStatus;
	}

	// FeedConnectionStatus(CONNECTED/DISCONNECTED)를 기존 MarketStatusEvent(수정 불가)의 status(PriceStatus)
	// 필드로 매핑한다 — CONNECTED=AVAILABLE, DISCONNECTED=UNAVAILABLE. symbol·marketStatus는 시장 전체(종목
	// 단위 아님) 상태 변화라 null(생략)이며, reason에 원본 연결상태 이름을 남겨 클라이언트가 원인을 구분할 수 있게 한다.
	private void broadcastStatusEvent(FeedConnectionStatus connectionStatus) {
		PriceStatus status = connectionStatus == FeedConnectionStatus.CONNECTED ? PriceStatus.AVAILABLE
			: PriceStatus.UNAVAILABLE;
		MarketStatusEvent payload = new MarketStatusEvent(Market.CRYPTO, null, null, status, connectionStatus.name(),
			LocalDateTime.now(clock));
		for (SseEmitter emitter : sseEmitterRegistry.getEmitters(Market.CRYPTO)) {
			send(emitter, SseEmitter.event().name("status").data(payload));
		}
	}

	private List<Instrument> getCryptoInstruments() {
		return instrumentRepository.findByMarketOrderByIdAsc(Market.CRYPTO);
	}

	// emitter 하나의 전송 실패가 나머지 구독자의 push를 막지 않게 개별 처리한다 (StockPriceStreamService.send()와 동일 패턴, 이슈 #18 계약 재사용).
	private void send(SseEmitter emitter, SseEmitter.SseEventBuilder eventBuilder) {
		try {
			emitter.send(eventBuilder);
		} catch (IOException | RuntimeException e) {
			log.debug("SSE 이벤트 전송 실패로 emitter 종료", e);
			emitter.completeWithError(e);
		}
	}
}
