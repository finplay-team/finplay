# API 계약 — ranking

ranking 도메인의 API 계약 상세다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

---

### 전체 랭킹 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/rankings | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `limit`(선택, 기본 10, 상한 50 — 범위 밖이어도 오류 없이 클램핑) | 200 `{"market":"STOCK","status":"READY","content":[{"rank":1,"nickname":"투자왕","realizedPnl":500000},{"rank":1,"nickname":"차트요정","realizedPnl":500000},{"rank":3,"nickname":"존버맨","realizedPnl":120000}]}` (`RankingListResponse`); 매도 체결 이력이 있는 회원이 한 명도 없으면 200 `{"market":"STOCK","status":"READY","content":[]}`; 집계가 유실돼 재구성 전이면 200 `{"market":"STOCK","status":"REBUILDING","content":[]}`; Redis 연결 장애면 200 `{"market":"STOCK","status":"UNAVAILABLE","content":[]}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. `limit`이 정수로 파싱 불가능한 값(예: `abc`)이면 값 범위와 무관하게 400 `VALIDATION_ERROR`(클램핑은 파싱된 정수에만 적용). Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 014 RANK-001, Issue #187·#279·#288 |

**`limit`은 이 API에서만 400이 아니라 클램핑된다 — `GET /api/trades`·`GET /api/orders`와 의도적으로 다른 정책이다.** `limit`이 생략되거나 0 이하면 컨트롤러가 거부하지 않고 그대로 `RankingService`로 전달되어 서비스가 10으로 클램핑하고, 51 이상이면 50으로 클램핑한다. `market`만 컨트롤러 검증(누락·미지원 리터럴 400) 대상이다.

**Redis 장애 시(이슈 #288)**: 조회 경로(`RankingStore.topN`/`findAllAtScore`/`countStrictlyGreater`/`score`)가 `RankingStoreUnavailableException`을 던지면 `RankingService`가 이를 잡아 500 대신 200 `{"status":"UNAVAILABLE"}`으로 응답한다. 쓰기 경로(랭킹 갱신)는 여전히 재시도 후 실패를 삼킨다 — 읽기·쓰기의 태도가 다른 것은 의도한 비대칭이다(PR #196 리뷰가 남긴 과거 제약은 이 이슈로 해소됨).

조회 대상은 요청에서 받지 않고 `market` 쿼리로 지정한 시장 전체 회원 중 **매도 체결 이력이 한 번도 없는 회원은 제외**한다(실현손익이 정확히 0이어도 매도 이력이 있으면 포함). 정렬은 실현손익 내림차순이며, `nickname`은 마스킹 없이 전체 노출한다. `userId`는 응답에 포함하지 않는다 — 응답은 인증 사용자로 스코프되지 않는 전체 랭킹이다(다른 회원의 항목도 그대로 보인다).

**동점자는 공동 순위를 받고, 다음 순위는 동점자 수만큼 건너뛴다.** 위 예시처럼 공동 1위가 2명이면 다음 회원은 2위가 아니라 3위다(`rank = 해당 score보다 엄격히 큰 회원 수 + 1`). Redis ZSET 기본 순위 커맨드는 동점이어도 멤버 문자열 사전순으로 순차 배정해 이 규칙과 다르게 동작하므로, 애플리케이션 계층(`RankingService`)에서 별도로 보정한다.

응답 2단 구조: wrapper(`RankingListResponse`)에 `market`(요청한 시장, 항목마다 반복하지 않음) · `status` · `content`(항목 배열). 항목(`RankingListItemResponse`)은 `rank`·`nickname`·`realizedPnl` 3개 필드로 고정이다 — `status`는 응답 전체의 성질이라 항목이 아니라 wrapper에만 있다.

**`status`(`READY`\|`REBUILDING`\|`UNAVAILABLE`) — 빈 `content`의 의미를 구별해주는 필드다 (Issue #279·#288).** `READY`면 ZSET이 원장을 반영하고 있다는 뜻이라 `content: []`는 "실제로 매도 이력이 있는 회원이 없다"는 정상 상태다. `REBUILDING`이면 Redis ZSET이 유실돼 재구성(기동 훅 또는 매일 04:20 KST 배치) 전이라는 뜻이고, 지금 보이는 값은 신뢰할 수 없다. `UNAVAILABLE`이면 Redis에 연결 자체가 되지 않아 ZSET을 읽지 못했다는 뜻이다 — `REBUILDING`과 달리 재구성으로 해결되지 않고, Redis 연결이 복구되면 별도 조치 없이 다음 조회부터 정상화된다. 판정은 **ZSET에 멤버가 하나도 없으면서 그 시장에 매도 체결 이력 계좌가 존재할 때만** `REBUILDING`이고, Redis 연결 장애 자체(조회 중 예외 발생)면 `UNAVAILABLE`이며, 그 외에는 전부 `READY`다. 유실·장애 상태여도 오류가 아니라 200이며, 기존 필드(`market`·`content`)는 그대로라 이 필드를 모르는 클라이언트는 영향을 받지 않는다(필드 추가만, 하위 호환).

**목록은 부분 유실을 감지하지 않는다 — `GET /api/rankings/me`와 판정 기준이 의도적으로 다르다.** ZSET에 일부 회원만 남아 있는 상태는 `READY`로 나간다(매 조회마다 전체 집계 비교가 필요하고, 대부분이 정확한 목록에 "준비 중" 경고를 띄우는 것이 과한 신호이기 때문이다). 매일 배치가 교정한다. 또 `content`가 비어 있어도 그 원인이 Redis/DB 불일치(ZSET에는 있는데 DB에 없는 계좌를 걸러낸 결과)라면 `READY`를 유지한다 — 그건 ZSET 유실이 아닌 다른 상황이다.

**score의 정본은 MySQL이다.** 실현손익 값은 매 조회마다 `trades`를 재집계하지 않고, 매도 체결로 `accounts.realized_pnl`이 갱신된 뒤 **커밋 이후(after-commit)**에만 Redis ZSET에 반영된 값을 그대로 읽는다 — 커밋 전 갱신·롤백 시 Redis 오염이 없다. Redis 갱신이 재시도 후에도 실패하면 로그만 남기고 매도 체결 자체(주문·체결·계좌 갱신)에는 영향이 없다.

### 내 랭킹 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/rankings/me | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용) | 200 `{"market":"STOCK","status":"READY","rank":3,"nickname":"존버맨","realizedPnl":120000}` (`MyRankingResponse`); 매도 체결 이력이 없으면 200 `{"market":"STOCK","status":"READY","rank":null,"nickname":"투자왕","realizedPnl":0}`(오류 아님); 매도 이력이 있는데 집계가 유실됐으면 200 `{"market":"STOCK","status":"REBUILDING","rank":null,"nickname":"존버맨","realizedPnl":0}`; Redis 연결 장애면 200 `{"market":"STOCK","status":"UNAVAILABLE","rank":null,"nickname":"존버맨","realizedPnl":0}`(닉네임은 DB 조회라 장애와 무관하게 정상 값) | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 014 RANK-002, Issue #233·#279·#288 |

대상은 항상 인증 토큰의 본인이다 — 요청 파라미터로 다른 사용자의 accountId·userId를 지정하는 기능은 없다(구조적으로 타인 조회 불가, 별도 소유권 검증 로직 불필요). 상위 노출 구간(`GET /api/rankings`의 `limit`)에 들지 않아도 본인의 정확한 보정 순위를 반환한다 — 목록 노출 여부와 무관하게 항상 계산된다.

**순위 계산은 RANK-001과 동일한 ZSET 상태·보정 공식(`countStrictlyGreater(score) + 1`)을 재사용한다** — 별도의 새 보정 공식을 만들지 않는다. 매도 이력 유무 판정도 DB `accounts.realized_pnl`이 아니라 항상 Redis ZSET(`RankingStore.score`)을 기준으로 한다 — 두 엔드포인트가 서로 다른 순간의 데이터를 봐서 순위가 불일치하는 상황을 원천적으로 없앤다. **응답의 `realizedPnl`도 이 `score`를 그대로 노출한다** — DB `accounts.realized_pnl`을 별도로 재조회하지 않는다. `rank`와 `realizedPnl`을 서로 다른 저장소에서 읽으면(after-commit 반영 지연·Redis 재시도 소진 등으로 두 값이 순간적으로 어긋날 때) 한 응답 안에서 "이 손익, 이 순위"가 서로 대응하지 않게 되기 때문이다(PR #234 리뷰 반영). `score`가 없으면 `realizedPnl`은 DB 값과 무관하게 0이다. `status`가 `READY`면(매도 이력 자체가 없음) DB `accounts.realized_pnl`도 0이라 결과가 같지만, `REBUILDING`이면(매도 이력은 있는데 ZSET에서 유실됨) DB 값은 0이 아닐 수 있다 — 그래도 DB 값을 대신 싣지 않는다. `rank`가 `null`인 응답에 손익만 실제 값을 채우면 "이 손익, 이 순위"의 대응이 다시 깨지기 때문이다. **`REBUILDING`일 때의 `realizedPnl: 0`은 "손익이 0"이 아니라 "아직 신뢰할 수 없음"을 뜻한다** — 이 구별이 `status`의 존재 이유다(이슈 #279).

**매도 체결 이력이 없는 사용자는 `rank`만 `null`이다.** 오류 코드를 새로 만들지 않는다 — `nickname`·`realizedPnl`(0 포함)은 매도 이력과 무관하게 항상 정상 값으로 채워진다("랭킹 목록 대상에서 제외됨"(RANK-001)과 "본인 조회 응답에 참고 정보가 없음"은 다른 개념이다). `nickname`은 RANK-001과 동일하게 마스킹 없이 노출한다.

**`rank: null`의 의미는 `status`로 갈린다 (Issue #279).** 필드 조합이 같아 보여도 두 응답의 뜻이 다르다.

| `rank` | `status` | 의미 | 클라이언트 안내 |
|---|---|---|---|
| `null` | `READY` | 매도 체결 이력이 없다(정상) | "아직 매도 기록이 없어 순위가 없습니다" |
| `null` | `REBUILDING` | 매도 이력은 있는데 집계가 유실돼 재구성 전이다 | "순위를 집계하는 중입니다" |
| `null` | `UNAVAILABLE` | Redis에 연결할 수 없어 순위를 읽지 못했다(이슈 #288) | "일시적으로 순위를 불러올 수 없습니다" |
| 숫자 | `READY` | 정상 순위다 | 순위 그대로 표시 |

이슈 #279 이전에는 이 두 경우가 응답에서 구별되지 않아 프론트엔드가 두 가능성을 함께 안내하며 판단을 사용자에게 전가해야 했다. `status` 필드 하나가 그 구별을 담당한다. 기존 필드(`market`·`rank`·`nickname`·`realizedPnl`)는 그대로라 하위 호환이 유지된다.

**내 랭킹은 목록과 달리 부분 유실까지 감지한다 — 의도적인 비대칭이다.** 판정은 **내 `score`가 ZSET에 없으면서 내 계좌에 매도 체결 이력이 있을 때** `REBUILDING`이고, `score`가 있으면 항상 `READY`다(다른 사람 점수가 유실됐는지까지는 판정하지 않는다 — 그 판정은 전체 비교와 같은 비용이다). 내 것 1건만 확인하는 인덱스 조회라 싸고, 이 상황에서 당사자는 "매도했는데 매도 이력이 없다"는 잘못된 안내를 100% 받게 되므로 잡을 가치가 있다. 매도 이력 조회는 `score`가 없을 때만 일어나 정상 경로에는 추가 왕복이 없다.

**`status`는 유실과 장애를 모두 표현한다(이슈 #288로 갱신됨).** `REBUILDING`은 "ZSET을 읽을 수 있는데 내용이 비어 있다"이고, `UNAVAILABLE`은 "Redis 자체에 연결할 수 없다"이다 — 두 엔드포인트 모두 어느 쪽이든 500이 아니라 200을 유지한다.
