// StockPriceProvider의 개인 개발·본인 전용 검증(PRIVATE+KIS_REALTIME)용 구현 — KIS Open API 실시간 웹소켓(H0STCNT0)에서 국내주식 체결 틱을 수신한다.
package com.finplay.api.market.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// KIS Open API 국내주식 실시간체결가(tr_id=H0STCNT0) 웹소켓에 접속해 체결 틱을 수신하고, 1분 OHLCV 집계는 KisTickAggregator에 위임한다.
// 프로토콜(approval_key 발급 REST, 접속 URL, 파이프(|) 구분 메시지, PINGPONG 응답)은 한국투자증권 공식 저장소
// (github.com/koreainvestment/open-trading-api, legacy/websocket/python/ws_domestic_stock.py)를 그대로 따른다 — 추측하지 않았다.
// 연결이 끊기면 해당 종목의 가격을 무효화하고, 재연결 후 새 체결을 받으면 그 종목만 복귀한다(MKT-004·MKT-007 원칙).
// 실제 KIS 서버 연결은 자동 테스트 대상이 아니다(spec.md 확정) — 자동 테스트는 FakeKisRealtimePriceProvider로 이 계약을 검증한다.
// 생성자는 Lombok @RequiredArgsConstructor로 생성한다 — SpotBugs EI_EXPOSE_REP2(가변 객체 필드 저장)는 손으로 쓴 생성자에서만
// 잡히고 Lombok이 생성한 생성자에서는 잡히지 않음을 실측 확인했다(docs/agent-mistakes.md 2026-07-29 항목 참고). 그래서
// approvalUri·websocketUri처럼 파생 로직이 필요한 필드는 두지 않고 원본 String을 그대로 보관해 사용 시점에 URI.create()하며,
// RestClient도 완성된 인스턴스를 그대로 주입받는다(타임아웃 적용은 StockFeedConfig가 수행) — 모든 필드가 파라미터 직접 대입이어야
// @RequiredArgsConstructor를 쓸 수 있기 때문이다.
@Slf4j
@RequiredArgsConstructor
public class KisRealtimePriceProvider implements StockPriceProvider {

	private static final String APPROVAL_GRANT_TYPE = "client_credentials";
	private static final String TR_ID_STOCK_EXECUTION = "H0STCNT0";
	private static final String TR_TYPE_SUBSCRIBE = "1";
	private static final String CUSTOMER_TYPE_PERSONAL = "P";
	private static final String PINGPONG_TR_ID = "PINGPONG";
	private static final int FIELDS_PER_EXECUTION_RECORD = 46;
	private static final int FIELD_INDEX_SYMBOL = 0;
	private static final int FIELD_INDEX_TRADE_TIME = 1;
	private static final int FIELD_INDEX_CURRENT_PRICE = 2;
	private static final int FIELD_INDEX_TRADE_VOLUME = 12;
	private static final int FIELD_INDEX_BUSINESS_DATE = 33;
	private static final Duration RECONNECT_DELAY = Duration.ofSeconds(3);
	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
	private static final DateTimeFormatter BUSINESS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter TRADE_TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");
	private static final String HOLIDAYS_RESOURCE_PATH = "/holidays-2026.txt";
	private static final Set<LocalDate> HOLIDAYS_2026 = loadHolidays(HOLIDAYS_RESOURCE_PATH);

	private final InstrumentRepository instrumentRepository;
	private final Clock clock;
	private final ObjectMapper objectMapper;
	// 타임아웃이 적용된 완성된 RestClient를 그대로 주입받는다 — 빌드 로직(SimpleClientHttpRequestFactory 등)은 StockFeedConfig가 담당.
	private final RestClient restClient;
	private final String appKey;
	private final String appSecret;
	private final String approvalUrl;
	private final String websocketUrl;

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final KisTickAggregator tickAggregator = new KisTickAggregator();
	private final Map<Long, TickSnapshot> latestTicks = new ConcurrentHashMap<>();
	private final Map<String, Long> instrumentIdBySymbol = new ConcurrentHashMap<>();
	private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "kis-realtime-reconnect");
		thread.setDaemon(true);
		return thread;
	});

	private volatile WebSocket webSocket;
	private volatile boolean connected;
	private volatile boolean closed;

	// Spring @Bean(initMethod="connect")로 호출된다 — 구독 대상 종목을 적재하고 첫 연결을 시작한다.
	// appKey·appSecret이 비어 있으면(PRIVATE+KIS_REALTIME 오설정) 연결을 시도하지 않고 로그만 남긴다 — 재시도 폭주를 막기 위함.
	public void connect() {
		if (isBlank(appKey) || isBlank(appSecret)) {
			log.error("KIS_APP_KEY·KIS_APP_SECRET이 설정되지 않아 KisRealtimePriceProvider가 연결을 시도하지 않습니다.");
			return;
		}
		loadInstruments();
		connectAsync();
	}

	// Spring @Bean(destroyMethod="close")로 호출된다.
	public void close() {
		closed = true;
		reconnectExecutor.shutdownNow();
		WebSocket ws = this.webSocket;
		if (ws != null) {
			ws.abort();
		}
	}

	@Override
	public StockMarketStatus getMarketStatus() {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		LocalTime time = now.toLocalTime();
		boolean isWeekend = today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY;
		boolean isHoliday = HOLIDAYS_2026.contains(today);
		boolean withinTradingHours = !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(MARKET_CLOSE_TIME);
		return (!isWeekend && !isHoliday && withinTradingHours) ? StockMarketStatus.OPEN : StockMarketStatus.CLOSED;
	}

	@Override
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		StockMarketStatus marketStatus = getMarketStatus();
		TickSnapshot latest = latestTicks.get(instrumentId);
		if (latest == null) {
			return new StockReplayPriceDto(connected, marketStatus, null, null, null);
		}
		return new StockReplayPriceDto(connected, marketStatus, latest.sourceTradingDate(), latest.price(),
			latest.sourceTime());
	}

	@Override
	public List<StockCandleDto> getCandles(Long instrumentId, LocalDateTime from, LocalDateTime to) {
		return tickAggregator.getClosedCandles(instrumentId, from, to);
	}

	private void loadInstruments() {
		instrumentRepository
			.findByMarketOrderByIdAsc(Market.STOCK)
			.forEach(instrument -> instrumentIdBySymbol.put(instrument.getSymbol(), instrument.getId()));
	}

	private void connectAsync() {
		if (closed) {
			return;
		}
		try {
			String approvalKey = issueApprovalKey();
			httpClient
				.newWebSocketBuilder()
				.buildAsync(URI.create(websocketUrl), new KisWebSocketListener(approvalKey))
				.whenComplete((webSocketInstance, error) -> {
					if (error != null) {
						log.warn("KIS 실시간 WebSocket 연결 실패, {}초 후 재시도", RECONNECT_DELAY.toSeconds(), error);
						scheduleReconnect();
					}
				});
		} catch (RuntimeException ex) {
			log.warn("KIS approval_key 발급 실패, {}초 후 재시도", RECONNECT_DELAY.toSeconds(), ex);
			scheduleReconnect();
		}
	}

	private void scheduleReconnect() {
		connected = false;
		latestTicks.clear();
		if (!closed) {
			reconnectExecutor.schedule(this::connectAsync, RECONNECT_DELAY.toSeconds(), TimeUnit.SECONDS);
		}
	}

	// 웹소켓 접속키(approval_key) 발급 — REST, appkey·secretkey만 필요하다(계좌번호 불필요, KIS 공식 샘플 get_approval 확인).
	private String issueApprovalKey() {
		Map<String, String> requestBody = Map.of(
			"grant_type", APPROVAL_GRANT_TYPE,
			"appkey", appKey,
			"secretkey", appSecret);
		ApprovalKeyResponse response = restClient
			.post()
			.uri(URI.create(approvalUrl))
			.contentType(MediaType.APPLICATION_JSON)
			.body(requestBody)
			.retrieve()
			.body(ApprovalKeyResponse.class);
		if (response == null || isBlank(response.approval_key())) {
			throw new IllegalStateException("KIS approval_key 응답이 비어 있습니다.");
		}
		return response.approval_key();
	}

	private void subscribeAllInstruments(WebSocket webSocketInstance, String approvalKey) {
		for (String symbol : instrumentIdBySymbol.keySet()) {
			webSocketInstance.sendText(buildSubscribeMessage(approvalKey, symbol), true);
		}
	}

	private static String buildSubscribeMessage(String approvalKey, String symbol) {
		return "{\"header\":{\"approval_key\":\"" + approvalKey + "\",\"custtype\":\"" + CUSTOMER_TYPE_PERSONAL
			+ "\",\"tr_type\":\"" + TR_TYPE_SUBSCRIBE + "\",\"content-type\":\"utf-8\"},"
			+ "\"body\":{\"input\":{\"tr_id\":\"" + TR_ID_STOCK_EXECUTION + "\",\"tr_key\":\"" + symbol + "\"}}}";
	}

	private void handleMessage(WebSocket webSocketInstance, String message) {
		if (message.isEmpty()) {
			return;
		}
		char firstChar = message.charAt(0);
		if (firstChar == '0' || firstChar == '1') {
			handleRealtimeData(message);
		} else {
			handleControlMessage(webSocketInstance, message);
		}
	}

	// 실시간 데이터: "암호화여부|tr_id|데이터건수|필드1^필드2^...^필드N(레코드 반복)" 형식 (KIS 공식 샘플 확인).
	private void handleRealtimeData(String message) {
		String[] outerParts = message.split("\\|", -1);
		if (outerParts.length < 4) {
			log.warn("KIS 실시간 데이터 형식이 올바르지 않습니다: {}", message);
			return;
		}
		String trId = outerParts[1];
		if (!TR_ID_STOCK_EXECUTION.equals(trId)) {
			return;
		}
		int recordCount;
		try {
			recordCount = Integer.parseInt(outerParts[2]);
		} catch (NumberFormatException ex) {
			log.warn("KIS 실시간 데이터 건수 파싱 실패: {}", message, ex);
			return;
		}
		String[] fields = outerParts[3].split("\\^", -1);
		for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
			int base = recordIndex * FIELDS_PER_EXECUTION_RECORD;
			if (base + FIELDS_PER_EXECUTION_RECORD > fields.length) {
				log.warn("KIS 실시간 체결 레코드 필드 수가 예상과 다릅니다: {}", message);
				break;
			}
			handleExecutionRecord(fields, base);
		}
	}

	private void handleExecutionRecord(String[] fields, int base) {
		String symbol = fields[base + FIELD_INDEX_SYMBOL];
		Long instrumentId = instrumentIdBySymbol.get(symbol);
		if (instrumentId == null) {
			return;
		}
		try {
			LocalDate tradingDate = LocalDate.parse(fields[base + FIELD_INDEX_BUSINESS_DATE], BUSINESS_DATE_FORMAT);
			LocalTime tradeTime = LocalTime.parse(fields[base + FIELD_INDEX_TRADE_TIME], TRADE_TIME_FORMAT);
			LocalDateTime sourceTime = LocalDateTime.of(tradingDate, tradeTime);
			BigDecimal price = new BigDecimal(fields[base + FIELD_INDEX_CURRENT_PRICE]);
			long tickVolume = Long.parseLong(fields[base + FIELD_INDEX_TRADE_VOLUME]);

			latestTicks.put(instrumentId, new TickSnapshot(price, sourceTime, tradingDate));
			tickAggregator.onTick(instrumentId, sourceTime, price, tickVolume);
		} catch (RuntimeException ex) {
			log.warn("KIS 체결 틱 파싱 실패 symbol={}", symbol, ex);
		}
	}

	// 실시간 데이터가 아닌 메시지(JSON) — 구독 응답, PINGPONG 등. PINGPONG은 수신한 원문 그대로를 WebSocket Pong 프레임으로 되돌려 보낸다(KIS 공식 샘플: ws.pong(data)).
	private void handleControlMessage(WebSocket webSocketInstance, String message) {
		try {
			JsonNode root = objectMapper.readTree(message);
			String trId = root.path("header").path("tr_id").asString(null);
			if (PINGPONG_TR_ID.equals(trId)) {
				webSocketInstance.sendPong(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
				return;
			}
			String returnCode = root.path("body").path("rt_cd").asString(null);
			if ("1".equals(returnCode)) {
				log.warn("KIS 실시간 구독 응답 오류: {}", root.path("body").path("msg1").asString(""));
			}
		} catch (RuntimeException ex) {
			log.warn("KIS 실시간 제어 메시지 파싱 실패: {}", message, ex);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	// StockReplayService와 같은 방식(리소스 파일 기반)으로 공휴일을 읽는다 — 별도 공통 유틸로 추출하지 않음(2번째 중복, conventions.md 기준 미달).
	private static Set<LocalDate> loadHolidays(String resourcePath) {
		try (InputStream inputStream = KisRealtimePriceProvider.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				throw new IllegalStateException("공휴일 리소스 파일을 찾을 수 없습니다: " + resourcePath);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
				return reader
					.lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.map(LocalDate::parse)
					.collect(Collectors.toUnmodifiableSet());
			}
		} catch (IOException ex) {
			throw new IllegalStateException("공휴일 리소스 파일을 읽는 중 오류가 발생했습니다: " + resourcePath, ex);
		}
	}

	private record TickSnapshot(BigDecimal price, LocalDateTime sourceTime, LocalDate sourceTradingDate) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ApprovalKeyResponse(String approval_key) {
	}

	private final class KisWebSocketListener implements WebSocket.Listener {

		private final String approvalKey;
		private final StringBuilder messageBuffer = new StringBuilder();

		KisWebSocketListener(String approvalKey) {
			this.approvalKey = approvalKey;
		}

		@Override
		public void onOpen(WebSocket webSocketInstance) {
			KisRealtimePriceProvider.this.webSocket = webSocketInstance;
			connected = true;
			log.info("KIS 실시간 WebSocket 연결 성공, 구독을 시작합니다 (종목 수={})", instrumentIdBySymbol.size());
			subscribeAllInstruments(webSocketInstance, approvalKey);
			WebSocket.Listener.super.onOpen(webSocketInstance);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocketInstance, CharSequence data, boolean last) {
			messageBuffer.append(data);
			webSocketInstance.request(1);
			if (last) {
				String message = messageBuffer.toString();
				messageBuffer.setLength(0);
				handleMessage(webSocketInstance, message);
			}
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocketInstance, int statusCode, String reason) {
			log.warn("KIS 실시간 WebSocket 연결 종료 code={} reason={}", statusCode, reason);
			scheduleReconnect();
			return null;
		}

		@Override
		public void onError(WebSocket webSocketInstance, Throwable error) {
			log.warn("KIS 실시간 WebSocket 오류", error);
			scheduleReconnect();
		}
	}
}
