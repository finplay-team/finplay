# Tasks: 주식 일봉 3년 아카이브를 캔들 조회 API에 연결

프로덕션 반영 긴급도 때문에 항목을 나누지 않고 한 커밋으로 진행했다(경량 경로, CLAUDE.md "파일 1~2개 규모").

- [x] `StockReplayService`에 `StockDailyCandleRepository` 주입, `pastCandlesPreferringArchive`/`toArchiveDto` 추가.
- [x] `narrowRangeStart`가 아카이브·1분봉 거래일을 합쳐서 계산하도록 수정(부수 발견 버그).
- [x] 기존 생성자 호출부 4곳(`StockReplayServiceTest`·`StockReplayServiceFullDayQueryTest`·`CandleQueryServiceIntegrationTest`·`PeerStatsBatchServiceIntegrationTest`) 컴파일 수정.
- [x] `CandleQueryServiceIntegrationTest`에 아카이브·1분봉 혼재 통합 테스트 추가.
- [x] `ai/prd.md` §3 MKT-011 행 갱신(수집·저장 → 수집·저장+조회 연결 완료로), §4 절에 "구현 완료 — 조회 연결" 추가.
- [x] `docs/api/market.md`의 오래된 문단 갱신.
- [x] `ai/context-router.md`·`ai/specs/050-stock-daily-archive/spec.md` 범위 제외 문구에 후속 완료 표시.
- [ ] `./gradlew build` 전체 통과 확인.
- [ ] PR 생성, 이슈 #506 완료 조건 갱신.
