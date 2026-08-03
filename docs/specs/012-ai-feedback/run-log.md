# Run Log: 012-ai-feedback

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 11:40 | reviewer(리뷰) | `git log/diff origin/dev`(문서 6종) + `src/main/java`·`db/migration` 대조 | conventions.md, ADR-0002·0003·0004·0011, prd.md C-004, api-contracts/routes |
| 12:20 | reviewer(리뷰) | `git show 523ffb1 --stat` + spec.md Part D(FEED-009·004)·api-contracts 개장 전 브리핑·prd.md 대조, `StockReplaySessionScheduler`·`StockReplayService`·`application.yml`·`Dockerfile`·`compose.deploy.yaml` 코드 확인 | spec.md, api-contracts.md, prd.md, conventions.md, ADR-0002·0003·0004·0011 |
| 12:35 | reviewer(리뷰) | `git show 523ffb1` + spec.md Part B(FEED-007·010·011, 파생 사실·반사실·집단 비교·장 마감 재방문·`trade_feedbacks`/`price_move_peer_stats`)·api-contracts 매도 직후 피드백 대조, `OrderExecutionService`·`PortfolioSellService`·`StockReplayService`·`Trade`/`HoldingLot`/`TradeAllocation`/`Holding`·V9·V10 코드 확인 + 계약 예시 수치 직접 재계산 | spec.md, api-contracts.md, api-routes.md, prd.md C-003·C-004·§5 오류표, conventions.md, ADR-0002·0003·0004 |
| 12:35 | reviewer(리뷰) | `git show 523ffb1 --stat` + spec.md Part A(FEED-001~006·탐지 알고리즘·코인 수익률 이력·노출 판정·뉴스 매칭 범위·데이터 모델)·api-contracts 변동 원인 카드 대조, `StockReplayService`·`PriceStore`·`BithumbRestCandleProvider`·`StockReplaySessionScheduler`·`StockCandleRepository`·V7~V12·`application.yml` 코드 확인 | spec.md, api-contracts.md, api-routes.md, 003-market-data spec/plan, conventions.md, ADR-0002·0004 |
| 12:10 | reviewer(리뷰) | `git show 523ffb1`(Part C 한정) + `StockReplayService`·`StockReplaySessionScheduler`·`PriceStore`·`V7`·`V10`·`SecurityConfig` 대조 | spec.md FEED-008·노출판정·프롬프트·후검증·템플릿·데이터모델, api-contracts.md 종목 뉴스 목록·요약, ADR-0011, conventions.md, ADR-0002·0004 |

## 모니터링 (사람용 요약)
- 11:40 — 문서 리뷰 완료, 차단 9건(노출 판정 전장 기사 역전, UNIQUE(url) 잔존 모순, 코인 경로 미정의, 장마감 배치 부재, 배치용 전일치 분봉 조회 경로 부재, PRD 수집주기 모순 등) / 권장 12건.
- 12:20 — Part D(개장 전 브리핑) 정밀 리뷰: 차단 8건(08:40 잔존 모순, 크론 zone 누락, 스케줄러 풀 미증설, 09:00 전 배치 미완료, 브리핑 행 부재 상태 미정의, NOT_YET/EMPTY 우선순위 충돌, 재생세션 조회 경로 부재, 공시 00:00:00 구간 역전) / 권장 9건 — 머지 불가.
- 12:35 — Part B(매도 직후 피드백·반사실·집단 비교) 정밀 리뷰: 차단 9건(장 마감 게이트의 기준 날짜 미정의, peerComparison 기준 카드·카드 0건 미정의, 15:35 배치와 15:30 게이트·재생성 1회 충돌, 서비스 날짜↔원본 거래일 변환 경로 부재, 특정 시점 보유자 집계 근거 부재(holdings에 이력 없음), LLM 실패 시 서술 처리 spec↔계약 모순, 재생성 프롬프트의 반사실 포함 모순, 보유 구간 극값 고가/종가 미정, 예시 수치 불일치) / 권장 7건 / 참고 5건 — 머지 불가.
- 12:35 — Part A(변동 원인 카드) 정밀 리뷰: 차단 8건(09:00 windowStart 유니크 충돌, 09:00/15:30 분봉 존재 전제(003 Decision Gate 미확정), W 구간 정의·결측 분봉 미정, 코인 이력이 가격 아닌 수익률이라 p_past 불가, 이력 타임스탬프 부재, 크론 zone 누락, 스케줄러 pool 미증설, revealAt 잔존 문서 불일치) / 권장 10건 — 머지 불가.
- 12:10 — Part C(종목 뉴스 목록·요약) 정밀 리뷰: 차단 5건(items 범위와 신규 회귀조건 충돌, 공시 00:00 클램프로 장중 접수 공시 09:00 유출, 요약 배치 08:40/08:45 불일치, 코인 온디맨드 동시성·NONE 캐시 미정, 재생성 프롬프트가 적발 표현 미전달) / 권장 13건 / 참고 5건.
