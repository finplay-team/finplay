# API 계약 — journal

journal 도메인의 API 계약 상세다. 전체 라우트를 한눈에 보는 지도는 `ai/api-routes.md`에 있다.

**controller를 추가/변경하면 `ai/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`ai/context-router.md`).

---

### 투자일기 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/journal | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `cursor`(선택, `{createdAt}_{tradeId}` 형식 문자열, 생략 시 첫 페이지), `limit`(선택, 기본 20, 1~100) | 200 `{"content":[{"journalType":"SELL","buyTradeId":null,"sellTradeId":34,"content":"목표가 도달해서 전량 매도.","createdAt":"2026-08-04T15:20:41","updatedAt":"2026-08-04T15:20:41"},{"journalType":"BUY","buyTradeId":12,"sellTradeId":null,"content":"실적 발표 전 분할 매수.","createdAt":"2026-08-04T10:12:33","updatedAt":"2026-08-05T09:03:12"}],"nextCursor":"2026-08-04T10:12:33_12","hasNext":true}` (`JournalListResponse`); 회고가 없으면 200 `{"content":[],"nextCursor":null,"hasNext":false}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`), `limit`이 1~100 범위 밖(클램핑 없음), `cursor`가 `{ISO_LOCAL_DATE_TIME}_{id}` 형식으로 파싱 실패(구분자 없음·날짜 파싱 실패·id 파싱 실패)는 모두 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 요청 시장의 계좌가 없으면 404 `NOT_FOUND` 공통 오류 형식 | 007 JOUR-006, Issue #203 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌(`AccountService.getAccountFor`로 소유권+시장 스코프 검증)가 쓴 매수 회고(`buy_trade_journals`)와 매도 회고(`sell_trade_journals`)를 **한 목록에 섞어** 반환한다 — 두 종류를 따로 조회하는 엔드포인트는 없다. 정렬 기준은 **회고를 처음 쓴 시점(`createdAt`) 내림차순**이며 동시각은 체결 ID 내림차순으로 끊는다(`updatedAt` 기준 정렬 아님 — 방금 수정한 오래된 회고가 목록 맨 위로 튀지 않도록). 커서는 "이전 페이지 마지막 행보다 이 시각 이전이거나(동시각이면 이 체결 ID보다 작은)" 조건으로 다음 페이지를 이어받아 페이지 경계에서 중복·누락이 없다.

응답 항목 필드는 `journalType`(`"BUY"`\|`"SELL"`)·`buyTradeId`·`sellTradeId`·`content`·`createdAt`·`updatedAt` 6개로 고정이다. 매수 항목은 `sellTradeId`가, 매도 항목은 `buyTradeId`가 `null`이다. **통합 `journalId`는 노출하지 않는다** — `buy_trade_journals.id`·`sell_trade_journals.id`는 서로 다른 AUTO_INCREMENT 시퀀스라 같은 값이 두 테이블에 겹칠 수 있어 식별자로 쓸 수 없다. 종목·가격·수량·실현손익 등 체결 정보는 포함하지 않는다(`GET /api/trades`가 정본). wrapper는 형제 API(`GET /api/trades`·`GET /api/orders`)와 같은 `content`·`nextCursor`·`hasNext` 3필드다.

### 매수 체결 투자일기 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/trades/{buyTradeId}/journal | Access Bearer 필수 | 경로 변수 `buyTradeId`(숫자) + 본문 `{"content":"실적 발표 전 분할 매수. 5% 빠지면 손절 계획."}`(`BuyJournalCreateRequest`, `content`는 `@NotBlank` + `@Size(max=5000)`) | 201 `{"journalId":1,"buyTradeId":12,"content":"실적 발표 전 분할 매수. 5% 빠지면 손절 계획.","createdAt":"2026-08-04T10:12:33"}` (`BuyJournalResponse`, 4개 필드 고정) | `content` 누락·공백·5000자 초과, `buyTradeId` 타입 불일치(숫자 파싱 실패), 대상 체결의 `side`가 `BUY`가 아님(매도 체결)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `buyTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 해당 매수 체결에 투자일기가 이미 존재(선제 조회 또는 유니크 위반)하면 409 `DUPLICATE_RESOURCE` 공통 오류 형식 | 007 JOUR-001, Issue #159 |

작성자는 요청 본문이 아니라 Access Token의 인증 사용자(`AuthenticatedUser#userId`)로 결정한다. 응답 필드는 `journalId`·`buyTradeId`·`content`·`createdAt` 4개로 고정이며, 종목·가격·수량 등 체결 정보는 포함하지 않는다(`GET /api/trades`가 이미 제공한다). 목표가·손절가·예상보유기간 등 구조화 필드는 이번 범위가 아니다. `Location` 헤더는 포함하지 않는다.

**본문 검증이 경로 검증보다 먼저 일어난다.** `@Valid`는 컨트롤러 메서드 진입 전에 평가되므로, 없는 체결 + 공백 본문 요청은 404가 아니라 **400**이다.

**검증 순서는 `존재(404) → 소유(403) → 매수 여부(400) → 중복(409)`으로 고정한다.** 타인의 매도 체결이면 403이 먼저다 — 소유하지 않은 체결의 속성(매수/매도)을 오류 코드로 흘리지 않기 위해서다.

`content`는 앞뒤 공백을 트림하지 않고 원문 그대로 저장한다(검증만 `@NotBlank`). 대상 매수 체결에 이미 투자일기가 있으면(`buy_trade_journals.buy_trade_id` `UNIQUE`) 애플리케이션 선제 조회로 대부분 409를 반환하고, 동시 요청 경합으로 유니크 제약을 직접 위반해도 같은 409 `DUPLICATE_RESOURCE`로 변환한다.

### 매도 체결 투자일기(매도 회고) 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/trades/{sellTradeId}/sell-journal | Access Bearer 필수 | 경로 변수 `sellTradeId`(숫자) + 본문 `{"content":"목표가 도달해서 전량 매도. 다음엔 분할 매도 시도."}`(`SellJournalCreateRequest`, `content`는 `@NotBlank` + `@Size(max=5000)`) | 201 `{"journalId":1,"sellTradeId":34,"content":"목표가 도달해서 전량 매도. 다음엔 분할 매도 시도.","createdAt":"2026-08-04T15:20:41"}` (`SellJournalResponse`, 4개 필드 고정) | `content` 누락·공백·5000자 초과, `sellTradeId` 타입 불일치(숫자 파싱 실패), 대상 체결의 `side`가 `SELL`이 아님(매수 체결)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `sellTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 해당 매도 체결에 매도 회고가 이미 존재(선제 조회 또는 유니크 위반)하면 409 `DUPLICATE_RESOURCE` 공통 오류 형식 | 007 JOUR-003, Issue #183 |

매수 회고(위 절)와 유스케이스가 대칭이다 — 인증 사용자 결정, 201·`Location` 헤더 미포함, 본문 검증이 경로 검증보다 먼저인 점(없는 체결 + 공백 본문 = 400), 검증 순서(`존재(404) → 소유(403) → 매도 여부(400) → 중복(409)`, 타인의 매수 체결이면 403이 먼저), `content` 트림 없이 원문 저장, 선제 조회 + 유니크 위반(`sell_trade_journals.sell_trade_id` UNIQUE) 변환은 모두 동일하다. **차이는 경로(`/sell-journal`), 대상 체결 구분 검증(`side != SELL`), 응답의 체결 ID 필드명(`sellTradeId`) 세 가지뿐이다.** 매도 회고 응답에는 실현손익·배분된 매수 lot 등 매도 결과 정보를 포함하지 않는다(`GET /api/trades`가 이미 제공한다).

### 매도 체결 투자일기(매도 회고) 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/trades/{sellTradeId}/sell-journal | Access Bearer 필수 | 경로 변수 `sellTradeId`(숫자) + 본문 `{"content":"돌아보니 목표가 도달 전에 일부 익절했어야 했다."}`(`SellJournalUpdateRequest`, `content`는 작성과 같은 `@NotBlank` + `@Size(max=5000)`) | 200 `{"journalId":1,"sellTradeId":34,"content":"돌아보니 목표가 도달 전에 일부 익절했어야 했다.","createdAt":"2026-08-04T15:20:41","updatedAt":"2026-08-05T09:03:12"}` (`SellJournalUpdateResponse`, 5개 필드 고정) | `content` 누락·공백·5000자 초과, `sellTradeId` 타입 불일치는 400 `VALIDATION_ERROR`. 대상 체결의 `side`가 `SELL`이 아님(매수 체결)도 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `sellTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 체결은 있으나 매도 회고가 아직 없으면(upsert 아님) 404 `NOT_FOUND` | 007 JOUR-004, Issue #190 |

작성(위 절)과 같은 리소스 경로를 PATCH로 재사용한다. **차이는 상태 200(생성이 아니므로 201 아님), 응답이 `updatedAt`을 더한 5필드(`SellJournalUpdateResponse`, 작성 응답 `SellJournalResponse`는 4필드 그대로 유지), 회고가 아직 없으면 404로 거부(새 회고를 만들지 않음, upsert 아님)라는 점, 검증 순서 마지막 단계가 `중복(409)`에서 `회고 존재(404)`로 바뀌어 **이 엔드포인트에는 409가 없다**는 점이다. 검증 순서는 `체결 존재(404) → 소유(403) → 매도 여부(400) → 회고 존재(404)`로 고정이며, 타인의 매수 체결이면 회고 존재 여부를 확인하기 전에 403이 먼저다. `sellTradeId`·`journalId`·`createdAt`은 요청으로 지정할 수 없고 응답에서도 원본 값 그대로다 — 수정은 본문 교체다. 수정 횟수 제한이나 잠금 조건은 없다(연속 수정 모두 허용, 마지막 본문만 남고 이력은 보관하지 않는다).

### 매수 체결 투자일기 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/trades/{buyTradeId}/journal | Access Bearer 필수 | 경로 변수 `buyTradeId`(숫자) + 본문 `{"content":"돌아보니 실적 발표 전 매수 타이밍이 조금 일렀다."}`(`BuyJournalUpdateRequest`, `content`는 작성과 같은 `@NotBlank` + `@Size(max=5000)`) | 200 `{"journalId":1,"buyTradeId":12,"content":"돌아보니 실적 발표 전 매수 타이밍이 조금 일렀다.","createdAt":"2026-08-04T10:12:33","updatedAt":"2026-08-05T09:03:12"}` (`BuyJournalUpdateResponse`, 5개 필드 고정) | `content` 누락·공백·5000자 초과, `buyTradeId` 타입 불일치는 400 `VALIDATION_ERROR`. 대상 체결의 `side`가 `BUY`가 아님(매도 체결)도 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `buyTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 체결은 있으나 투자일기가 아직 없으면(upsert 아님) 404 `NOT_FOUND` | 007 JOUR-002, Issue #197 |

매도 회고 수정(위 절)과 유스케이스가 대칭이다 — 상태 200, 응답이 `updatedAt`을 더한 5필드(`BuyJournalUpdateResponse`, 작성 응답 `BuyJournalResponse`는 4필드 그대로 유지), 일기가 아직 없으면 404로 거부(upsert 아님), 이 엔드포인트에는 409가 없다는 점, `buyTradeId`·`journalId`·`createdAt`은 요청으로 지정할 수 없고 응답에서도 원본 값 그대로라는 점(수정은 본문 교체)이 모두 동일하다. **검증 순서는 `체결 존재(404) → 소유(403) → 매수 여부(400) → 회고 존재(404)`로 고정**이며, 타인의 매도 체결이면 일기 존재 여부를 확인하기 전에 403이 먼저다. **잠금 없음** — 해당 매수 체결의 일부 또는 전부가 이미 매도되어 `trade_allocations`에 배분이 생겼든, 전량 매도됐든 관계없이 항상 수정할 수 있다. 매도 배분·`holding_lots` 여부를 판정에 쓰지 않는다(2026-08-04 이슈 #197 확정 — `005-order-sell` spec.md가 규정했던 "첫 매도 배분 발생 시 잠금"은 이 결정으로 대체됐다). 수정 횟수 제한이나 수정 이력 보관도 없다(연속 수정 모두 허용, 마지막 본문만 남는다).
