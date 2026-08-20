# Plan: 관심목록 (Watchlist)

## 관련 문서
- Spec: `./spec.md`
- 관련 ADR: ADR-0002(레이어드 아키텍처, 도메인 패키지), ADR-0004(Flyway 마이그레이션), ADR-0003(테스트 전략)
- **명시적으로 대체하지 않는 문서**: `ai/adr/0012-tutorial-state-in-memory.md` — 이 ADR은 `com.finplay.api.favorite`(튜토리얼 전용) 범위만 다루며 이 spec은 그 범위 밖의 새 도메인이다.

## 도메인·라우트 명명 결정

- **패키지**: `com.finplay.api.watchlist` — 기존 `favorite` 패키지와 이름이 겹치면 두 기능(인메모리 튜토리얼 vs DB 영속 실사용)이 코드 리뷰·검색 시 혼동되므로 새 이름을 쓴다. "watchlist"는 관심 종목 목록이라는 의미가 이미 업계 표준 용어라 새 팀원도 바로 이해할 수 있다.
- **테이블/엔티티**: `watchlist_items` / `WatchlistItem` — "관심목록에 담긴 개별 항목"이라는 의미를 명확히 하고, 향후 그룹/폴더 개념이 생겨도(범위 제외 항목) `Watchlist`(그룹)와 `WatchlistItem`(항목)으로 자연스럽게 확장 가능한 이름이다.
- **라우트**: `/api/watchlist-items` — 컨벤션(복수 명사, kebab-case)을 따르고 `/api/favorites`와 경로가 겹치지 않게 한다.

## API 설계
| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/watchlist-items | `WatchlistItemCreateRequest` | `WatchlistItemResponse` (201) | 인증 사용자 본인 관심목록에 종목 등록 |
| GET | /api/watchlist-items?market= | 없음 (쿼리: `market` 선택) | `WatchlistItemListResponse` (200) | 본인 관심목록을 등록 최신순(동시각 id 내림차순)으로 조회, `market` 필터 선택 |
| DELETE | /api/watchlist-items/{instrumentId} | 없음 | 없음 (204) | 본인 관심목록에서 해당 종목 해제 |

## 입력 명세
| 필드 | 필수 | 검증 |
|---|---|---|
| `instrumentId` (POST body) | 필수 | `@NotNull`(객체) + `@Positive` — null·0 이하는 400 `VALIDATION_ERROR`, 존재하지 않는 ID는 404 `NOT_FOUND`(InstrumentService.getInstrumentEntity 재사용) |
| `market` (GET query) | 선택 | 생략 시 전체 조회. 값이 있으면 `Market` enum(`STOCK`\|`CRYPTO`) 리터럴만 허용 — `GET /api/instruments?market=`과 동일한 바인딩 방식(Spring enum 컨버터), 미지원 리터럴은 400 |
| `instrumentId` (DELETE path) | 필수 | `@Positive` — 0 이하는 400. 존재하지 않거나 타인 소유면 404 `WATCHLIST_ITEM_NOT_FOUND` |

## 데이터 모델

### 신규 Flyway migration: `V23__create_watchlist_items.sql`
(V22가 최신이므로 다음 번호 V23을 쓴다. ADR-0004에 따라 기존 파일은 수정하지 않는다.)

```sql
CREATE TABLE watchlist_items (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    instrument_id BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_watchlist_items_user_instrument UNIQUE (user_id, instrument_id),
    CONSTRAINT fk_watchlist_items_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_watchlist_items_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    INDEX idx_watchlist_items_user_created_id (user_id, created_at, id)
);
```

- 과거 V14(`favorites`, 지금은 V19로 DROP됨)와 거의 동일한 설계를 재사용한다 — 이미 검증된 패턴(사용자·종목 unique, FK 2개, 목록 조회용 복합 인덱스)이고 이 spec의 요구사항과 정확히 맞는다.
- `market` 필터 조회는 `instruments.market`을 조인해야 하므로 리스트 조회는 `instrument_id IN (SELECT id FROM instruments WHERE market = ?)` 형태 대신, repository에서 `WatchlistItem`에 연관된 `Instrument`를 `@ManyToOne`으로 조인 조회(JPQL `JOIN FETCH` 또는 파생 쿼리 `findByUserIdAndInstrument_MarketOrderByCreatedAtDescIdDesc`)한다. 목록 크기가 작다는 전제(spec 범위 제외)라 QueryDSL 없이 Spring Data 파생 쿼리로 충분하다(컨벤션의 QueryDSL 기준 — 동적 조건이 "필터 있음/없음" 2분기뿐이라 단순 조회로 판단).

### 엔티티: `WatchlistItem` (`com.finplay.api.watchlist.domain`)
- `id`(PK), `userId`(Long, FK는 컬럼만 두고 User 엔티티 연관관계는 만들지 않음 — 다른 도메인 패턴(`Favorite`, `Order` 등)과 동일하게 ID만 저장), `instrument`(`@ManyToOne` `Instrument`, market 필터·응답용 심볼/이름 조인에 필요), `createdAt`.
- 정적 팩토리 `WatchlistItem.create(userId, instrument, now)`.

### Repository: `WatchlistItemRepository` (`com.finplay.api.watchlist.repository`, `JpaRepository<WatchlistItem, Long>`)
- `findByUserIdOrderByCreatedAtDescIdDesc(Long userId)`
- `findByUserIdAndInstrument_MarketOrderByCreatedAtDescIdDesc(Long userId, Market market)`
- `findByUserIdAndInstrumentId(Long userId, Long instrumentId)` — 해제 대상 조회용
- `existsByUserIdAndInstrumentId(Long userId, Long instrumentId)` — 필요 시 선검사(최종 방어선은 DB unique)

### Service: `WatchlistService` (`com.finplay.api.watchlist.service`)
- `@Transactional`로 등록/해제 트랜잭션 경계를 가진다.
- 등록: `InstrumentService.getInstrumentEntity(instrumentId)`로 존재 확인(다른 도메인 repository 직접 주입 금지, ADR-0002) → `WatchlistItem.create` → `repository.save`. 유니크 제약 위반은 `DataIntegrityViolationException`을 잡아 `BusinessException(ErrorCode.DUPLICATE_RESOURCE)`로 변환한다(`JournalService`/`LimitOrderService`/`OrderService`의 기존 패턴과 동일 — 동시 등록 경합을 DB가 최종 방어).
- 조회: `market` 유무에 따라 두 리포지토리 메서드 중 하나 호출, `WatchlistItemListResponse`로 매핑.
- 해제: `repository.findByUserIdAndInstrumentId`로 조회 후 없으면 `BusinessException(ErrorCode.WATCHLIST_ITEM_NOT_FOUND)`, 있으면 `repository.delete`.

### Controller: `WatchlistController` (`com.finplay.api.watchlist.controller`)
- `FavoriteController`와 동일한 형태(`@AuthenticationPrincipal AuthenticatedUser`, `@Valid @RequestBody`, `@Positive` path/query 검증)로 작성해 컨벤션 일관성을 유지한다.

### DTO
- `dto/request/WatchlistItemCreateRequest(Long instrumentId)` — `FavoriteCreateRequest`와 동일한 검증 애노테이션.
- `dto/response/WatchlistItemResponse(Long watchlistItemId, Long instrumentId, String market, String symbol, String name, LocalDateTime createdAt)` — 정적 팩토리 `from(WatchlistItem)`.
- `dto/response/WatchlistItemListResponse(List<WatchlistItemResponse> content)` — 정적 팩토리 `from(List<WatchlistItem>)`.

### 오류 코드 (`common/ErrorCode`에 추가)
- `WATCHLIST_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "관심목록 항목을 찾을 수 없습니다.")` — 신규 추가(기존 `FAVORITE_NOT_FOUND`는 튜토리얼 전용이라 재사용하지 않는다, 오류 코드가 섞이면 어느 기능의 404인지 클라이언트가 구분할 수 없다).
- 종목 미존재는 기존 `ErrorCode.NOT_FOUND`(InstrumentService.getInstrumentEntity가 이미 던짐)를 그대로 재사용한다.
- 중복 등록은 기존 `ErrorCode.DUPLICATE_RESOURCE`를 재사용한다(다른 도메인들과 동일 관례).

## 동시성 설계
- 인메모리 락(ADR-0012)과 달리 이 기능은 DB 영속이므로 다중 인스턴스 환경에서도 동일하게 동작해야 한다 — MySQL unique 제약(`uk_watchlist_items_user_instrument`)이 인스턴스 경계를 넘는 유일한 직렬화 지점이다.
- 등록 경합: 동시에 같은 (user_id, instrument_id) INSERT 두 건 중 하나만 성공, 나머지는 `DataIntegrityViolationException` → `DUPLICATE_RESOURCE`(409). 애플리케이션 레벨 락(`synchronized`, in-process `ReentrantLock`)은 여러 인스턴스에 걸쳐 무의미하므로 두지 않는다.
- 해제 경합: 동시 삭제 두 건 중 하나는 성공(204), 다른 하나는 이미 삭제된 행이라 `findByUserIdAndInstrumentId`가 empty를 반환해 자연스럽게 404가 된다(멱등하지 않지만 재시도 시 안전한 실패로 취급).

## 테스트 계획
- 단위: `WatchlistServiceTest` — 정상 등록/조회/해제, 중복 등록 시 `DataIntegrityViolationException` → `DUPLICATE_RESOURCE` 변환, 존재하지 않는 종목 등록 시 `NOT_FOUND`, 타인 소유 해제 시도 시 `WATCHLIST_ITEM_NOT_FOUND`(mock 기반, Mockito).
- 슬라이스:
  - `WatchlistItemRepositoryTest`(`@DataJpaTest`) — unique 제약 위반 시 예외 발생 확인, `market` 필터·정렬(`createdAt` desc, `id` desc) 확인.
  - `WatchlistControllerTest`(`@WebMvcTest`) — 요청 검증(`instrumentId` null/음수 400), 응답 직렬화(`jsonPath`로 필드값 확인), 오류 코드별 상태코드 매핑.
- 통합: `WatchlistIntegrationTest`(`@SpringBootTest` + Testcontainers) — 등록 → 조회 → 재기동(같은 컨테이너, 새 트랜잭션/컨텍스트로 재조회) → 해제 → 목록에서 제외 확인까지 핵심 시나리오 1개. 서버 재시작 대신 트랜잭션 밖에서 데이터가 실제 MySQL에 남아있음을 확인하는 것으로 "재시작 내구성"을 검증한다(Testcontainers가 프로세스를 재시작시키진 않으므로, "커밋된 데이터가 별도 조회에서 그대로 보인다"가 이 통합 테스트의 현실적 검증 범위임을 명시).
