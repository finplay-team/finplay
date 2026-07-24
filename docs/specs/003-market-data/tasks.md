# Tasks: 종목과 시세

- [ ] instruments 마이그레이션 + 16+12종 시드 + 엔티티·Repository + 종목 목록·단건 API (+ @DataJpaTest·@WebMvcTest)
- [ ] stock_candles 마이그레이션(UNIQUE(instrument_id, trading_date, candle_time), validation_status 컬럼 없음) + 엔티티·Repository
- [ ] stock_replay_sessions 마이그레이션(UNIQUE(service_date), preparation_status는 PREPARING·READY·FAILED 3개만, resolved_at·source_trading_date·failure_reason은 nullable — prepared_at이 아닌 resolved_at 이름 사용) + 엔티티·Repository
- [ ] market_data_imports 마이그레이션(status는 SUCCESS·PARTIAL_SUCCESS·FAILED·SKIPPED_DUPLICATE 4개) + 엔티티·Repository
- [ ] StockReplaySessionScheduler (MarketDataImport·StockCandle 수집 결과 확인 → StockReplaySession 생성, source_trading_date 고정, PREPARING→READY/FAILED만 전환, StockCandle 직접 저장 안 함, 상태별 nullable 규칙을 애플리케이션 검증으로 강제 — DB CHECK 제약 없음) (+ 단위 테스트: PREPARING+resolved_at 존재/PREPARING+failure_reason 존재/READY+source_trading_date 없음/READY+resolved_at 없음/READY+failure_reason 존재는 거부, FAILED+resolved_at 없음/FAILED+failure_reason 없음은 거부, FAILED+source_trading_date NULL·존재는 둘 다 허용. Clock으로 실행시점 제어 — production 실행시각은 Decision Gate)
- [ ] KrxFileImporter (샘플 파일: MVP 16종 확인·필수필드·형식·중복·시간범위·OHLC 대소관계·거래일 일치 등 명백한 오류만 검증 → StockCandle 저장 + MarketDataImport 이력. 전체 파일 오류는 미저장·FAILED, 특정 종목만 구조 오류면 그 종목만 미저장·PARTIAL_SUCCESS, 재실행 멱등, StockReplaySession 직접 수정 안 함) (+ 단위 테스트) — production 파일 포맷 파싱과 분봉 개수 임계치는 KRX 답변 대기, 샘플 데이터로 골격만 구현
- [ ] KrxFileImporter 재수집 정책 (수집 전 market_data_imports에서 해당 source_trading_date의 기존 SUCCESS·PARTIAL_SUCCESS와 file_hash 확인. 같은 해시 재수집은 StockCandle·세션 불변 + SKIPPED_DUPLICATE 이력만 추가, 다른 해시는 거부 + FAILED/SOURCE_DATE_HASH_CONFLICT 기록, FAILED 이력만 있으면 재시도 허용) (+ 단위 테스트: 최초 SUCCESS/PARTIAL_SUCCESS/FAILED, 동일 해시→SKIPPED_DUPLICATE·무변화, 다른 해시→FAILED·무변화, FAILED만 있을 때 재시도 허용)
- [ ] StockCandleCleanupJob (20영업일 초과 삭제, 재생 중 거래일 보존) (+ 단위 테스트)
- [ ] StockReplayService (StockReplaySession 읽기 전용 + Clock으로 OPEN·CLOSED 계산, 첫 분봉 시가/이후 마감 종가, 공휴일 목록, 재생세션 DB 쓰지 않음) (+ 단위 테스트)
- [ ] StockPriceProvider 인터페이스 + KrxReplayPriceProvider(StockReplayService 위임, 공개 기본) + StockFeedConfig (STOCK_FEED_PROVIDER·SERVICE_EXPOSURE·KIS_PUBLIC_DISPLAY_APPROVED로 빈 1개 선택, PUBLIC+KIS_REALTIME+미승인은 기동 실패) (+ 단위 테스트: 허용 조합 3종 Provider 선택, 금지 조합 컨텍스트 로드 실패, KIS 키 없이 KRX_REPLAY 기동 성공)
- [ ] KisRealtimePriceProvider + FakeKisRealtimePriceProvider (KIS 국내주식 WebSocket 체결 틱 수신·재연결·연결상태, 끊김 시 가격 무효·재연결 후 복귀. 키는 KIS_REALTIME일 때만 바인딩) (+ 단위 테스트는 Fake로) — 실제 KIS 연결은 외부 스모크로 구분 보고
- [ ] KisTickAggregator (체결 틱 → 1분 OHLCV 서버 집계, 결과 모델이 캔들 API·KrxReplayPriceProvider와 동일) (+ 단위 테스트: open/high/low/close/volume 산출, 분 경계 전환)
- [ ] PriceStore (Redis 코인 최신 시세·과거 틱 무시, stale 10초) + UpbitFeedClient 인터페이스·Fake 구현 (+ 단위 테스트)
- [ ] 실제 UpbitFeedClient (WebSocket 수신·재연결·연결상태 기록) + PriceQueryService 유효성 판정 (주식은 주입된 StockPriceProvider만 사용 — 구현체를 알지 못함) (+ 단위 테스트: 두 Provider로 같은 시나리오를 돌려 동일 계약 확인)
- [ ] 가격·캔들 API + /api/stocks/stream, /api/cryptos/stream SSE (fetch+Bearer 인증, id는 price 이벤트에만 부여·snapshot/status는 id 없음, snapshot은 주식 16종·코인 12종 전체를 배열로 포함하며 가격 없는 종목도 price/sourceTime=null·status=UNAVAILABLE로 포함, sourceTime·emittedAt·sourceTradingDate 필드, marketStatus(시장 전체)와 종목별 status(AVAILABLE/UNAVAILABLE) 분리, retry 3000, heartbeat 20초, emitter 정리) (+ @WebMvcTest: 401, price에만 id 존재, snapshot 전체종목 포함(가격없음 포함), 장마감 후 마지막 유효가격 유지, 코인 stale 시 marketStatus는 OPEN 유지, 재접속 시 snapshot 재전송, emitter 정리)
- [ ] 통합 테스트 (샘플 KRX 파일 수집·세션 READY·재생, 서버 재시작 시 원본 거래일 유지, DB에 OPEN·CLOSED 미저장 확인, 동일 거래일 다른 해시 재수집 거부 시 기존 READY 세션·StockCandle 불변 확인, Fake Feed 정상·끊김·재연결, 개장·첫분봉·장중·마감, 화면 가격과 모의 체결가격이 같은 Provider에서 나오는지 확인) + docs/api-routes.md 갱신
