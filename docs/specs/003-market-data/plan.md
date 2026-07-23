# Plan: 종목과 시세

## 관련 문서
- Spec: `./spec.md`
- 관련 ADR: ADR-0002 (도메인 패키지), ADR-0003 (테스트 전략), ADR-0004 (Flyway)
- PRD: §5 종목·시세 API, §6 데이터 모델·Redis 키 책임
- 선행: `001-foundation` (Redis·Clock), `002-auth-account` (인증 — 조회 API는 인증 필요)

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/instruments?market= | 쿼리 market(선택) | `InstrumentResponse[]` | 종목 목록 |
| GET | /api/instruments/{instrumentId} | - | `InstrumentResponse` | 종목 단건 |
| GET | /api/instruments/{instrumentId}/price | - | `PriceResponse` (price, asOf, status) | 최신 가격. 없으면 409 PRICE_UNAVAILABLE |
| GET | /api/instruments/{instrumentId}/candles?interval=1m&from=&to= | 쿼리 | `CandleResponse[]` | 주식 1분봉 (Fixture 기반) |
| WS | /ws/prices | 구독 심볼 | 시세 이벤트 | 실시간 브로드캐스트 |

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| market (쿼리) | 선택 | STOCK·CRYPTO만. 그 외 400 VALIDATION_ERROR |
| instrumentId | 필수 | 미존재 시 404 NOT_FOUND |
| interval | 필수(candles) | 1차는 `1m`만. 그 외 400 VALIDATION_ERROR |
| from·to | 선택 | ISO-8601. from > to면 400 VALIDATION_ERROR |

## 구성 요소 설계

### market 패키지 (`com.finplay.api.market`)

| 구성 요소 | 역할 |
|---|---|
| `Instrument` 엔티티 + Repository | 종목 정본 (MySQL) |
| `InstrumentController` / `InstrumentService` | 목록·단건·가격·캔들 조회 |
| `StockFixtureReplayer` | 리소스의 1분봉 Fixture를 Clock 기준 현재 분에 매핑해 현재가 제공. 장 상태(OPEN·CLOSED) 판정 |
| `UpbitFeedClient` | 업비트 WebSocket 수신 → `PriceStore` 저장. 재연결 처리. 인터페이스로 추상화해 테스트는 Fake 구현 사용 |
| `PriceStore` | Redis 읽기/쓰기 단일 창구. 과거 틱 무시(수신 timestamp 비교 후 최신만 저장) |
| `PriceQueryService` | 주문 도메인이 소비할 "유효한 최신 가격" 계약 — 주식은 장중 Fixture 가격, 코인은 stale 검사 통과한 Redis 가격. 유효하지 않으면 PRICE_UNAVAILABLE·MARKET_CLOSED 판정 근거 반환 |
| WS 브로드캐스트 | Spring WebSocket — 수신 틱·재생 틱을 구독자에게 push |

- 공휴일 판정은 리소스 파일의 공휴일 목록(당해 연도)으로 단순 관리한다. 외부 캘린더 API 미사용.
- stale 기준시간은 설정값(프로퍼티)으로 두되 기본값을 정해 커밋한다 (예: 10초 — 구현 시 확정하고 여기 갱신).

### Redis 키 설계 (PRD §6 책임 준수)

| 키 | 값 | 용도 |
|---|---|---|
| `price:{market}:{symbol}` | price, receivedAt | 최신 시세 |
| `feed:crypto:status` | CONNECTED·DISCONNECTED | 업비트 연결상태 |

## 데이터 모델

마이그레이션 `V{next}__create_instruments.sql` + 시드 INSERT (16+12종).

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| instruments | id PK, market(STOCK·CRYPTO), symbol, name, tick_size DECIMAL(18,8), min_order_amount BIGINT, tradable BOOLEAN, created_at | UNIQUE(symbol) |

- 종목 시드는 마이그레이션에 포함한다 (기준 데이터 — 코드·환경 간 동일 보장, ADR-0004).
- 주식 1분봉 Fixture는 `src/main/resources` 리소스 파일 (레포 포함 — PRD §10).

## 테스트 계획

- 단위: StockFixtureReplayer (고정 Clock으로 개장 전·장중 분 매핑·마감 후), PriceStore 과거 틱 무시, PriceQueryService 유효성 판정 (stale·끊김·장외).
- 슬라이스: `@DataJpaTest` — 종목 시드·UNIQUE(symbol). `@WebMvcTest` — 목록·가격·캔들 계약, 404·400·409 매핑.
- 통합 (Testcontainers MySQL+Redis): Fake Feed 정상 수신→가격 조회, 끊김→PRICE_UNAVAILABLE, 재연결 새 틱→복귀 시나리오.
