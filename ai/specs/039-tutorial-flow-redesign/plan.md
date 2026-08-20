# Plan: 튜토리얼 흐름 재설계 — 영속 attempt·자동 위험 스냅샷·단일 라이브 차트

## 관련 문서

- Spec: `./spec.md`
- Issue: #378
- Delta 정본: `../026-market-order-practice-tutorial`, `../030-coin-practice-price-runtime`,
  `../031-tutorial-sandbox-instruments`, `../033-exclude-tutorial-sandbox-data`
- 관련 ADR: ADR-0002(도메인 기준 3계층), ADR-0003(MySQL Testcontainers), ADR-0004(Flyway),
  ADR-0012(기존 favorite·intention 인메모리 경계)

새 ADR은 필요 없다. 새 상태는 reload·재기동 정합성과 주문 귀속을 수용 기준으로 가지므로 DB에 두며,
ADR-0012가 명시적으로 인메모리로 정한 favorite·legacy intention의 저장 결정을 바꾸지 않는다.

## 도메인 경계

- `education.marketpractice`가 attempt, 실행 상태, 위험 snapshot, 진행/replay orchestration을 소유한다.
- `education`은 `order`·`account`·`market`의 공개 service만 호출하며 다른 도메인 repository를 직접 주입하지
  않는다. `order`는 attempt ID/run number scalar 귀속과 기존 취소·예약 반환·체결 원장 규칙을 소유한다.
- canonical tutorial price와 29+1 candle 생성기는 `market`의 샘플 가격 service가 소유한다. chart,
  주문 체결, 관찰, 보상 SELL이 모두 이 service의 같은 run-scoped quote를 사용한다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| PUT | `/api/education/practice/attempts/{market}` | path `STOCK|CRYPTO`, body 없음 | 200 `PracticeAttemptResponse` | 없으면 생성하고 기존 attempt는 무변경 ensure/read; 완료면 `REPLAY` |
| POST | `/api/education/practice/attempts/{market}/restart` | path, body 없음 | 200 `PracticeAttemptResponse` | 미완료 current run을 원자 정리·증가; 완료면 무변경 `REPLAY` |
| PUT | `/api/education/practice/attempts/{market}/instrument` | `PracticeAttemptInstrumentUpdateRequest` | 200 `PracticeAttemptResponse` | 현재 run의 거래 가능한 시장별 샘플 종목 선택 및 clock 시작 |
| GET | `/api/education/practice/attempts/{market}/chart` | path | 200 `PracticeTutorialChartResponse` | 현재 run의 결정적 29+1 일봉 순수 조회; settlement·체결 없음 |
| POST | `/api/education/practice/attempts/{market}/tick` | path, body 없음 | 200 `PracticeTutorialChartResponse` | 요청 시점 가상 분 canonical price 정산·pending 교육 주문 체결 판정·live chart 반환 |
| GET | `/api/education/practice?market=` | 기존 query | 200 `InvestmentPracticeResponse` 확장 | attempt mode/run/status와 자동 risk evidence를 포함한 진행/replay 조회 |

모든 경로는 Access Bearer 인증이 필요하다. 기존 `POST /api/orders`, 코인 교육 지정가 API, holding 관찰·복기
API는 URL과 요청 형식을 유지한다. 선택된 샘플 종목 주문을 서버가 현재 attempt/run에 귀속하므로 클라이언트가
attempt ID나 run number를 주문 요청으로 위조할 수 없다.

`PracticeAttemptResponse`는 `attemptId`, `market`, `runNumber`, `mode(ACTIVE|REPLAY)`,
`status(SELECTING_INSTRUMENT|IN_PROGRESS|EXPIRED|COMPLETED)`, nullable `instrumentId`, nullable `anchorAt`,
nullable `tutorialDate`, nullable `riskSnapshot`, nullable `completedAt`을 반환한다. `riskSnapshot`은
`entryPrice`, `stopLossPrice`, `takeProfitPrice`, `buyTradeId`, `createdAt`이다. seed는 노출하지 않는다.

`PracticeTutorialChartResponse`는 `attemptId`, `runNumber`, `instrumentId`, `virtualDateTime`,
`secondsPerVirtualMinute=3`, `candles`를 반환한다. candle은 `date`, `open`, `high`, `low`, `close`,
`current`를 가진다. 배열 길이는 항상 30이고 마지막 항목만 `current=true`다.

## 입력·오류 계약

| 조건 | HTTP / 코드 |
|---|---|
| market 누락·미지원, instrumentId 누락·0 이하 | 400 `VALIDATION_ERROR` |
| 종목 없음 | 404 `NOT_FOUND` |
| 선택 종목이 다른 시장·실제 종목·`tradable=false` | 409 `INSTRUMENT_NOT_TRADABLE` |
| 종목 선택 전 chart·tick·샘플 주문, 현재 run과 종목 불일치 | 409 `PRACTICE_STEP_LOCKED` |
| 완료 attempt에서 종목 선택·주문·관찰·복기 쓰기 | 409 `PRACTICE_ALREADY_COMPLETED` |
| 재시작 정리 수량과 현재 holding 정합성 불일치 | 409 `PRACTICE_EVIDENCE_MISSING`(전체 rollback) |
| 인증 실패 | 401 `UNAUTHORIZED` |

동일 사용자의 같은 시장 ensure/restart 경합은 attempt 행 비관 잠금으로 직렬화한다. 기존 ACTIVE attempt의
`PUT`은 현재 상태를 읽을 뿐 run·주문·선택을 바꾸지 않는다. 완료 행은 `PUT`과 `POST .../restart` 모두
200 `REPLAY`를 돌려주므로 클라이언트가 별도 오류 분기를 만들지 않지만 어떤 write도 수행하지 않는다.

## 데이터 모델

- `practice_attempts`: `id`, `user_id`, `market`, `run_number`, `status`, nullable `instrument_id`, nullable
  `anchor_at`, nullable `tutorial_date`, `price_seed`, `generator_version`, `created_at`, `updated_at`, nullable
  `completed_at`; `UNIQUE(user_id, market)`. 재시작은 같은 ID에서 `run_number += 1` 후 선택·clock 필드를 비운다.
- `practice_risk_snapshots`: `id`, `attempt_id`, `run_number`, `buy_trade_id`, `entry_price`,
  `stop_loss_price`, `take_profit_price`, `created_at`; `UNIQUE(attempt_id, run_number)`. 최초 FILLED BUY와 같은
  트랜잭션에서 insert하며 업데이트하지 않는다.
- `orders`: nullable `practice_attempt_id`, nullable `practice_attempt_run_number`를 추가한다. 둘은 함께 null
  이거나 함께 non-null이어야 한다. 일반 주문과 기존 행은 null이다. trade는 기존 order 연결로 attempt/run을
  역추적하며 불변 원장을 중복 수정하지 않는다.
- 기존 `practice_progresses`, `practice_completions`, observation/reflection, account/holding 원장은 유지한다.
  새 Flyway 번호는 구현 직전 `origin/dev`와 진행 PR을 재확인한다. 추가형 migration만 사용해 구버전 앱이
  nullable 컬럼과 새 테이블을 무시할 수 있게 한다.

## 상태·트랜잭션 설계

1. `PUT attempts/{market}`는 `(user,market)` 행을 insert-if-absent하고 최초에만 `SELECTING_INSTRUMENT`를
   만든다. 행이 이미 있으면 현재 ACTIVE/REPLAY 상태를 그대로 반환하며 restart 정리를 호출하지 않는다.
2. 종목 선택은 attempt를 잠그고 sample/tradable/market을 검증한 뒤 anchor/tutorialDate/seed/version을 저장해
   `IN_PROGRESS`로 전이한다.
3. 샘플 주문 생성·체결은 attempt를 먼저 잠그고 owner/market/instrument/current run을 검증해 order에
   귀속한다. 최초 BUY fill은 같은 트랜잭션에서 risk snapshot을 저장한다.
4. 명시적 `POST attempts/{market}/restart`의 미완료 restart는 attempt → 현재 run pending orders(ID ASC)
   → account → holding 순으로 잠근다. pending을
   기존 order service로 취소·예약 반환하고, 현재 run FILLED BUY-SELL을 order/trade service로 집계한다.
   양수 순수량은 restart 순간 generator quote로 기존 SELL 체결 service를 호출해 새 보상 원장을 남긴다.
   전부 성공한 뒤에만 run number를 증가시키고 선택·clock·현재 progress evidence를 초기화한다.
5. 완료 attempt는 `practice_completions`를 정본으로 `REPLAY`를 반환한다. attempt/completion/progress/reward와
   주문·체결은 쓰지 않는다.

## 가격·차트 설계

- generator 입력은 `generatorVersion`, `priceSeed`, `instrumentId`, `runNumber`, candle index/minute index다.
  출력은 scale 8 `HALF_UP` 양수 가격이며 구현 시 순수 함수와 golden vector를 함께 고정한다.
- 과거 index 0..28은 완결된 29개 virtual date OHLC를 생성한다. current index 29는 `tutorialDate`이며
  virtual 12:00의 minute 0부터 조회 시 공개 가능한 minute까지만 fold해 OHLC를 만든다.
- 공개 minute는 `max(0, floor(Duration.between(anchorAt, now)/3 seconds))`다. 같은 minute 내 재조회 값은
  동일하다. clock 역행은 minute 0으로 clamp한다.
- canonical tutorial price는 current candle에 포함된 마지막 minute close다. 기존 epoch 기반 사인파를
  attempt/run 기반 생성기로 대체하되 실제 종목 가격 경로는 변경하지 않는다.
- `GET .../chart`는 위 순수 계산 결과만 반환하며 attempt·order·account·holding을 쓰거나 pending 주문을
  정산하지 않는다. 조회 횟수는 체결 결과에 영향을 주지 않는다.
- `POST .../tick`은 attempt를 owner/market/current run 범위로 잠그고 요청 시점 공개 minute와 canonical
  close를 계산한 뒤, 그 가격으로 현재 run의 pending 교육 주문만 체결 판정한다. 같은 minute 재호출과
  네트워크 재시도는 이미 terminal이 된 주문을 다시 체결하지 않으며, 다른 사용자·시장·run·일반 주문은
  대상이 아니다. 응답 chart는 정산에 사용한 동일 시각·동일 생성 결과다.
- 5분 만료는 기존처럼 실제 `anchor buyTrade.executedAt + 5분` evidence 규칙을 유지한다. virtual clock은
  시각화·가격 생성용이며 만료 시간을 100 virtual minute로 바꾸어 표현할 뿐 별도 timer를 만들지 않는다.

## 프론트엔드 companion

- 진입·reload 시 `PUT attempts/{market}`로 attempt를 ensure/read한다. 결과가 `ACTIVE`면 현재 run의 종목
  선택 또는 진행 화면, `REPLAY`면 완료 evidence를 읽기 전용으로 표시한다. 이 호출은 기존 ACTIVE run을
  재시작하지 않는다. replay에서 주문·관찰·복기 mutation CTA를 숨긴다.
- 사전 의도 폼과 `POST /api/education/practice/intentions` 호출을 제거한다. BUY 체결 뒤 응답/진행 조회의
  자동 `riskSnapshot`(-3%, +5%)을 설명 카드로 표시한다.
- 차트는 하나만 렌더링한다. 진입·reload의 최초 표시는 순수 `GET .../chart`로 읽고, 진행 중 live update는
  `POST .../tick`을 3초마다 polling해 응답의 29개 history + 마지막 current candle로 갱신한다. GET을
  polling settlement 용도로 사용하지 않으며 reload 후 새 local seed/timer를 만들지 않고 서버 응답만
  정본으로 쓴다.
- restart CTA는 미완료/만료에서만 보이고 확인 후 `POST attempts/{market}/restart`를 호출한다. 성공 응답이
  `SELECTING_INSTRUMENT`가 된 뒤 로컬 주문·차트 선택 상태를 비운다.

## 테스트 계획

- 단위: -3%/+5% scale 8 계산, generator golden vector·29+1 OHLC·3초 경계, net filled quantity 집계,
  상태 전이와 completed replay 무변경.
- 슬라이스: attempt/risk unique와 비관 잠금(`@DataJpaTest`), 6개 mapping의 validation·JSON·오류
  (`@WebMvcTest`), nullable order 귀속과 일반 주문 회귀.
- 통합: STOCK·CRYPTO 각각 선택→BUY fill→snapshot→chart GET→tick settlement→관찰→SELL→복기→완료;
  GET 반복이 주문·원장을 바꾸지 않는지, 같은 가상 분 tick 재시도가 중복 체결하지 않는지, reload와 Spring
  Context 재생성 후 동일 candle/price인지 검증한다. pending 예약과 부분 매수/매도의 restart 정리, 두
  restart 경합, 다른 사용자·시장·run·일반 주문 격리, completed restart가 ledger/reward row count를 바꾸지
  않는지도 Testcontainers로 검증한다.
- 회귀: `026` completion/reward, `030` 교육 지정가, `031` 5분 만료, `033` 포트폴리오·일기·랭킹 제외와
  실제 종목 가격·주문 테스트를 유지한다.

## 문서 동기화

controller 구현과 같은 커밋에서 실제 mapping만 `docs/api-routes.md`에 반영하고 이 절의 요청·응답·오류를
`docs/api-contracts.md`의 036 절에 동기화한다. 새 기능 제공으로 요구사항 상태가 바뀌므로 구현 완료 커밋은
`docs/prd.md` §3에 TUTORIAL-FLOW-001~012 행과 PR 번호를 추가한다. 현재 PRD에는 이 신규 ID 행이 없으므로
계획 단계에서는 완료 상태를 선반영하지 않는다.
