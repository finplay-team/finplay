# Tasks: 종목과 시세

- [ ] instruments 마이그레이션 + 16+12종 시드 + 엔티티·Repository + 종목 목록·단건 API (+ @DataJpaTest·@WebMvcTest)
- [ ] 주식 1분봉 Fixture 리소스 + StockFixtureReplayer (Clock 기반 장 상태·현재가·공휴일 목록) (+ 단위 테스트)
- [ ] PriceStore (Redis 최신 시세·과거 틱 무시) + UpbitFeedClient 인터페이스·Fake 구현 (+ 단위 테스트)
- [ ] 실제 UpbitFeedClient (WebSocket 수신·재연결·연결상태 기록) + PriceQueryService 유효성 판정 (+ 단위 테스트)
- [ ] 가격·캔들 API + WS /ws/prices 브로드캐스트 (+ @WebMvcTest)
- [ ] 통합 테스트 (Fake Feed 정상·끊김·재연결, 개장·장중·마감) + docs/api-routes.md 갱신
