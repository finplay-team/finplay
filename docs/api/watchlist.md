# API 계약 — watchlist

`docs/api-contracts.md`의 "023 관심목록 (watchlist)" 절을 옮겨 정리한 문서다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

---

`docs/specs/023-watchlist`의 신규 계약 3건이다. MySQL(`watchlist_items` 테이블)에 영속화하는 실제 서비스 기능이며, `docs/specs/016-investment-education-policy`의 인메모리 튜토리얼 즐겨찾기(`/api/favorites`, ADR-0012, `docs/api/education.md`)와 완전히 별개다 — 오류 코드도 `WATCHLIST_ITEM_NOT_FOUND`로 분리해 `FAVORITE_NOT_FOUND`와 섞이지 않는다. 모든 경로는 Access Bearer 인증과 공통 오류 body를 사용하며 JSON POST는 `Content-Type: application/json`이다.

### 관심목록 등록

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/watchlist-items | `{"instrumentId":1}` (`WatchlistItemCreateRequest`) | 201 `{"watchlistItemId":1,"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","createdAt":"2026-08-06T10:00:00"}` (`WatchlistItemResponse`) | 400 `VALIDATION_ERROR`; 404 `NOT_FOUND`(종목); 409 `DUPLICATE_RESOURCE` | 023 WATCH-001 |

같은 사용자의 `(userId, instrumentId)`는 유일하다(`uk_watchlist_items_user_instrument`). 종목의 `tradable` 여부는 검사하지 않는다 — 존재 여부만 확인한다(즐겨찾기의 `INSTRUMENT_NOT_TRADABLE` 거부와 의도적으로 다름). 동시 등록 경합은 DB unique 제약이 최종 방어선이며 한쪽만 201, 다른 쪽은 409다.

### 관심목록 목록 조회

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/watchlist-items?market= | `market`(선택, `STOCK`\|`CRYPTO` 리터럴만 허용, 생략 시 전체) | 200 `{"content":[WatchlistItemResponse...]}`; 없으면 빈 배열 | `market`이 허용 리터럴 밖이면 400 `VALIDATION_ERROR`; 인증 공통 오류 | 023 WATCH-002 |

`createdAt DESC, watchlistItemId DESC` 순으로 본인이 등록한 항목만 반환한다. MySQL 영속화라 서버 재시작·다중 인스턴스에도 유실되지 않는다(즐겨찾기와 다른 점).

### 관심목록 해제

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/watchlist-items/{instrumentId} | 양의 `instrumentId` path | 204, 본문 없음 | 400 `VALIDATION_ERROR`; 404 `WATCHLIST_ITEM_NOT_FOUND` | 023 WATCH-003 |

존재하지 않거나 타인 소유인 항목은 동일하게 404로 처리해 소유 여부를 노출하지 않는다. 동시 삭제 경합은 한쪽만 204, 다른 쪽은 이미 삭제된 행이라 자연스럽게 404가 된다.
