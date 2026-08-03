# Tasks: 012 AI 피드백 — 이슈 #160 (V13 마이그레이션·엔티티·리포지토리)

> **이 문서는 이슈 #160의 범위만 담는다.** spec 012 전체(이슈 8개)의 분할은 `./plan.md`가 정본이고, 값·규칙은 `./spec.md` §확정값이 정본이다. 여기에 값을 다시 적지 않는다 — 테이블 구성·유니크·인덱스는 §데이터 모델, 컬럼 타입·NULL·FK는 §C-8, 코인의 시각 컬럼은 §C-9, 패키지 배치는 §C-6이다.
>
> 항목 하나 = implementer 1회 투입 = 커밋 1개. 테스트 레벨은 ADR-0003을 따른다 — **이 이슈에는 컨트롤러도 서비스 로직도 없다.** 검증 대상이 스키마 제약·엔티티 매핑뿐이라 **`@DataJpaTest`가 중심**이고, `ddl-auto=validate` 기동은 기존 `@SpringBootTest`가 확인한다. 순수 단위 테스트로 검증할 것이 없다.

## 이 이슈 전체에 걸리는 제약

- **여기서 값을 새로 정하지 않는다.** 컬럼 하나라도 §C-8과 어긋나면 `validate`가 기동을 막는다. 표에 없는 컬럼이 필요해 보이면 코드가 아니라 `spec.md`를 먼저 고친다 (CLAUDE.md 규칙 1·2).
- **마이그레이션은 `V13__create_ai_feedback_tables.sql` 하나다** (ADR-0004). 일곱 테이블이 FK로 물려 있어 파일을 쪼개면 중간 번호에서 `validate`가 깨진다. 머지된 뒤에는 수정하지 않고 새 번호로 낸다.
- **원장 테이블을 건드리지 않는다.** V13에는 `CREATE TABLE`과 그 인덱스·FK만 있고 기존 원장 테이블에 대한 `ALTER`·`DROP`이 없다. 8개 이슈 공통 조건인 "원장 불변"이 이 이슈에서 취하는 형태다.
- **enum을 새로 정의하지 않는다.** `NarrativeSource`·`NewsSummaryScope`는 #147이 만든 것을 옮겨 `@Enumerated(EnumType.STRING)`으로 재사용한다(§C-8). 같은 뜻의 enum을 하나 더 만들면 저장된 문자열과 코드가 조용히 갈린다.
- **리포지토리에 지금 필요 없는 조회를 만들지 않는다.** `JpaRepository` 상속을 기본으로 두고 **아래 완료 조건을 검증하는 데 필요한 메서드만** 더한다. #3~#8이 쓸 조회는 각자의 이슈에서 그 이슈의 조건과 함께 추가한다 — 지금 추측으로 만들면 시그니처가 어긋난 채 굳는다.
- 엔티티 매핑은 V10 관례를 따른다 — `@ManyToOne(fetch = LAZY, optional = false)` + `@JoinColumn`, `@NoArgsConstructor(access = PROTECTED)`, setter 없음 (`order/domain/Trade` 선례).
- `market` 컬럼은 **`market/domain/Market`을 쓴다.** 이 컬럼들은 계좌가 아니라 종목·시장 축을 가리킨다 (`account/domain/Market`과 값 이름이 같아 저장 문자열은 동일하지만, 참조를 섞으면 어느 쪽이 정본인지 알 수 없게 된다).
- **컨트롤러·응답 DTO·수집·탐지·배치를 만들지 않는다.** 이슈 본문 §제외 범위가 목록을 갖고 있다.
- **설정 블록을 만들지 않는다.** 이 이슈는 스키마·엔티티·리포지토리만 다뤄 §C-7에 더할 값이 없다. 필요해지면 §C-7의 확정 방침(yml + `@DefaultValue` 양쪽 + 드리프트 테스트)을 따른다.

## 작업 항목

- [x] **1. `NarrativeSource`·`NewsSummaryScope`를 `feedback/domain/`으로 이동**

  두 enum을 `feedback/service/` → `feedback/domain/`으로 옮기고, 참조하는 기존 파일(`NarrativePromptBuilder`·`NarrativeResultDto`·`NarrativeTemplateBuilder`·`NewsSummaryPromptDto`와 각 테스트)의 import를 함께 고친다. **이 항목이 `feedback/domain/` 패키지를 처음 만든다.**
  - **두 개를 한 번에 옮긴다.** 하나만 옮기면 같은 성격의 타입이 두 패키지로 갈린다 (이슈 본문).
  - **enum 본문(값 이름·`promptText`)은 손대지 않는다.** 값 이름이 그대로 `@Enumerated(STRING)` 저장 문자열이 된다.
  - 두 파일의 클래스 주석에 있는 "#2가 재사용한다"는 문장은 이 이슈가 그 #2이므로 현재형으로 다듬는다. 그 외 주석은 건드리지 않는다.
  - 검증 — 새 테스트를 만들지 않는다. `./gradlew build`로 컴파일과 **기존 `NarrativePromptBuilderTest`·`NarrativeServiceTest`·`NarrativeValidatorTest`가 그대로 통과**하는지만 본다. 동작이 바뀌지 않았음을 보이는 것이 이 커밋의 전부다.

  > **왜 독립 항목인가.** 옮기기만 한 커밋이어야 리뷰어가 diff에서 "본문은 안 바뀌었다"를 확인할 수 있다. 엔티티 항목에 합치면 새 엔티티 수백 줄 사이에 enum 이동이 섞여 그 확인이 불가능해지고, 이동이 잘못됐을 때 되돌릴 단위도 사라진다. 뒤 항목 3~6이 전부 이 결과를 import 하므로 순서상으로도 맨 앞이다.

- [x] **2. `V13__create_ai_feedback_tables.sql` — 일곱 테이블**

  §데이터 모델의 일곱 테이블을 한 파일로 만든다. 컬럼 타입·NULL은 §C-8, 코인 관련 컬럼의 NULL 허용은 §C-9, 유니크·인덱스는 §데이터 모델의 블록에 적힌 그대로다. FK는 V10 관례를 따른다.
  - `url`은 **접두 길이 없이 전체 컬럼에 유니크**를 건다 — 이유가 §C-8에 있고, 3번의 완료 조건이 이걸 직접 검증한다.
  - `price_move_events`의 유니크에 `event_type`이 들어간다(§데이터 모델). 그 유니크가 실제로 충돌을 막는지 확인하는 `@DataJpaTest`는 **#4 소유**이므로 여기서 그 케이스를 만들지 않는다.
  - **엔티티는 이 항목에서 만들지 않는다.** 테이블만 있고 매핑이 없는 상태는 `validate`가 문제 삼지 않으므로, 3~6번이 엔티티를 하나씩 붙이는 동안 매 커밋이 초록으로 유지된다.
  - 검증 — `./gradlew test`. Testcontainers를 쓰는 기존 `@DataJpaTest`·`@SpringBootTest`가 **Flyway가 V13까지 적용한 스키마 위에서** 전부 통과하는지 본다. SQL 문법 오류, FK 참조 순서 오류, 인덱스 키 길이 초과가 여기서 잡힌다.
  - 검증 — **완료 조건 "원장 불변"**. ① V13 파일에 기존 원장 테이블(`orders`·`trades`·`accounts`·`balances`·`holdings`·손익)을 대상으로 하는 `ALTER`·`DROP`이 한 줄도 없다 ② 주문·체결·계좌·잔액·보유·손익의 기존 테스트가 그대로 통과한다.

- [x] **3. `MarketNewsItem` 엔티티·리포지토리 — URL 유니크 제약 검증**

  뉴스·공시 통합 테이블을 매핑한다. `type`은 §C-8의 `VARCHAR(20)` + `@Enumerated(STRING)`이고, 이 enum(`NEWS`·`DISCLOSURE`)은 아직 없으므로 **여기서 `feedback/domain/`에 정의한다** (1번이 옮긴 두 개와 같은 자리).
  - 본문 컬럼을 만들지 않는다 — 저작권 때문에 §데이터 모델이 명시적으로 뺐다.
  - `created_at`은 발행 시각이 아니라 **수집 시각**이다(§데이터 모델). 두 컬럼의 의미가 다르므로 하나로 합치지 않는다.
  - 검증 — **완료 조건 (`@DataJpaTest`) 2건이 이 항목 소유다.**
    ① **같은 기사 URL이 두 종목에 각각 저장된다** — 종목 2건 픽스처에 같은 `url`로 저장해 둘 다 남는지 단정한다. `url` 단독 유니크였다면 실패한다.
    ② **앞 191자가 같고 쿼리 파라미터만 다른 URL 2건이 모두 저장된다** — 접두 유니크였다면 실패한다(§C-8).
    두 조건 모두 실제 MySQL 제약이 대상이라 mock으로는 검증할 수 없다. 반대 방향(같은 종목 + 완전히 같은 `url`이면 유니크 위반)도 함께 단정해 제약이 실제로 걸려 있음을 보인다.

- [x] **4. `PriceMoveEvent`·`PriceMoveEventSource` 엔티티·리포지토리**

  변동 구간 원장과 근거 연결(N:M)을 매핑한다. `event_type`·`market`·`narrative_source`는 `@Enumerated(STRING)`이고, `narrative_source`는 1번이 옮긴 `NarrativeSource`를 **재사용한다**.
  - **주식과 코인이 서로 다른 컬럼을 채운다** — 주식은 `window_start`/`window_end`(TIME)를 채우고 `occurred_at`이 `NULL`, 코인은 반대다(§C-9). 두 형태를 한 테이블이 담으므로 해당 컬럼은 nullable이다.
  - `event_type`이 아직 enum으로 없으므로 `feedback/domain/`에 정의한다(`INTRADAY`·`OPENING_GAP`).
  - 탐지·게이트 판정 로직을 엔티티에 넣지 않는다 — `PriceMoveDetector`는 #4 소유이고 순수 계산이다(§C-6).
  - 검증 — `@DataJpaTest`. ① 주식 형태 행(TIME 채움 + `occurred_at` NULL)과 코인 형태 행(그 반대)이 **둘 다 저장되고 다시 읽었을 때 값이 같다** — `LocalTime`/`LocalDateTime` 매핑이 어긋나면 여기서 깨진다 ② 근거 연결이 이벤트·기사 양쪽 FK로 저장되고 같은 쌍을 두 번 넣으면 유니크에 걸린다.

- [x] **5. `InstrumentNewsSummary`·`MarketBriefing` 엔티티·리포지토리**

  종목별 요약과 시장별 브리핑을 매핑한다. `scope`는 1번이 옮긴 `NewsSummaryScope`를 **재사용한다**.
  - **`origin_trade_date`는 두 테이블 다 `NOT NULL`이고 유니크 키의 일부다** — 코인 행도 채운다. 이유는 §C-9에 있다(NULL이면 MySQL 유니크가 중복을 허용해 UPSERT가 성립하지 않는다).
  - 요약·브리핑에는 템플릿이 없어 `narrative_source`가 `NONE`이 될 수 있고, 그때 `summary`가 `NULL`이다(§C-8). 컬럼을 `NOT NULL`로 조이지 않는다.
  - `items`를 저장하지 않는다 — 조회 시 다시 질의한다(§데이터 모델). 저장할 컬럼을 만들지 않는다.
  - UPSERT·재생성 판정 로직은 배치 소유(#5)다. 여기서는 유니크가 그 UPSERT를 성립시키는지까지만 본다.
  - 검증 — `@DataJpaTest`. ① 같은 종목·거래일에 `scope`만 다른 행 2건이 공존한다 ② 같은 `(종목, 거래일, scope)` 2건째는 유니크에 걸린다 ③ 브리핑은 같은 `(시장, 거래일)` 2건째가 유니크에 걸린다 ④ `summary`가 `NULL`이고 `narrative_source`가 `NONE`인 행이 저장된다.

- [ ] **6. `TradeFeedback`·`PriceMovePeerStat` 엔티티·리포지토리 — V13 `validate` 최종 확인**

  매도 직후 서술(회원별)과 카드별 집단 집계를 매핑한다. 일곱 테이블 중 마지막 둘이다.
  - `narrative_finalized`는 기본 `FALSE`, `regeneration_attempts`는 기본 `0`이고 **체결 1건당 누적**이다 — 날짜로 리셋하지 않는다(§데이터 모델·§C-8). 리셋 로직을 여기에 만들지 않는다.
  - `price_move_peer_stats`의 유니크는 `(price_move_event_id, service_date)`다. `service_date`를 빼면 재재생 시 첫날 집계가 덮어써지는 이유가 §C-9에 있다.
  - **회원 식별자를 담지 않는다** — 집계 결과만 저장한다(§데이터 모델).
  - `trade_id`는 `order`의 `trades(id)`를 참조하되 **읽기만 한다.** 이 엔티티가 원장을 쓰는 경로를 만들지 않는다.
  - 검증 — `@DataJpaTest`. ① 체결 1건에 피드백 2건을 넣으면 유니크에 걸린다 ② 같은 카드라도 `service_date`가 다르면 집계 2행이 공존하고, 같으면 유니크에 걸린다 ③ `median_minutes_to_sell`이 `NULL`인 행(전원 미매도)이 저장된다.
  - 검증 — **완료 조건 "V13이 `ddl-auto=validate`를 통과한다"의 최종 확인이 이 항목이다.** 3~5번도 각자 붙인 엔티티에 대해 부분적으로 통과시키지만, **일곱 테이블 전부에 매핑이 생기는 시점은 여기**다. `./gradlew build`로 기존 `@SpringBootTest`가 기동하는지 확인하고, §C-8 타입표를 일곱 엔티티와 한 번 대조한다.

## 완료 조건 소유

이슈 #160에 배정된 4건이 어디서 검증되는지다 (`plan.md` §완료 조건 배정).

| 완료 조건 | 항목 |
|---|---|
| (`@DataJpaTest`) 같은 기사 URL이 두 종목에 각각 저장된다 | **3** |
| (`@DataJpaTest`) 앞 191자가 같고 쿼리 파라미터만 다른 URL 2건이 모두 저장된다 | **3** |
| V13이 `ddl-auto=validate`를 통과한다 | 3·4·5에서 부분 확인, **6에서 최종 확인** |
| (공통) 원장이 변하지 않는다 — V13이 기존 원장 테이블 스키마를 바꾸지 않는다 | **2** |

## 이 이슈에서 하지 않는 것

- 뉴스·공시 **수집 로직**과 수집 절의 나머지 완료 조건 4건, `.env.example`·`compose.deploy.yaml` 환경변수 (#3)
- 변동 구간 **탐지**·개장 전 배치·카드 조회 API (#4). 유니크에 `event_type`이 들어가는지 확인하는 `@DataJpaTest`도 #4 소유다
- 종목 뉴스 요약·개장 전 브리핑 조회 API와 UPSERT·재생성 판정 (#5)
- 매도 직후 피드백 조회 API와 재생성 게이트 (#6)
- 집단 비교 **집계 로직**과 장 마감 배치 (#7). 이 이슈는 집계를 담을 테이블만 만든다
- 코인 실시간 감시와 가격 스냅샷 (#8)
- 컨트롤러·응답 DTO 일체 — 각 조회 이슈가 만든다
- **머지된 마이그레이션 수정** (ADR-0004). V13 이후 스키마가 바뀌면 새 번호로 낸다
