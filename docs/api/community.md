# API 계약 — community

community 도메인의 API 계약 상세다. 전체 라우트를 한눈에 보는 지도는 `ai/api-routes.md`에 있다.

**controller를 추가/변경하면 `ai/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`ai/context-router.md`).

---

### 커뮤니티 게시물 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts | Access Bearer 필수 | `{"title":"게시물 제목","content":"게시물 본문","instrumentId":1,"imageId":1,"sharedTradeId":1}` (`title` 최대 100자, `content` 최대 5,000자, `instrumentId`·`imageId`·`sharedTradeId`는 선택·nullable) | 201 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자","imageId":1,"imageUrl":"/api/community/posts/images/1/file","likeCount":0,"likedByMe":false,"sharedTrade":{"symbol":"BTC","name":"비트코인","market":"CRYPTO","buyPrice":70000,"sellPrice":68500,"quantity":10,"realizedPnl":-15207,"returnRate":-0.0217}}` | 제목·본문 누락·빈 값·공백·최대 길이 초과, **존재하지 않거나 비활성(`tradable=false`)인 `instrumentId` 태그, 튜토리얼 전용 샘플 종목(`isTutorialSample=true`, 031)은 `tradable` 값과 무관하게 태그 대상에서 제외**, `imageId`와 `sharedTradeId`를 동시에 지정은 400 `VALIDATION_ERROR`("이미지와 매매 카드는 같은 게시물에 함께 첨부할 수 없습니다."). `imageId`는 순서대로 검증: 존재하지 않으면 404 `NOT_FOUND`, 업로더가 인증 사용자와 다르면 403 `FORBIDDEN`("본인이 업로드한 이미지만 사용할 수 있습니다."), 이미 다른 게시물에 연결됐으면 400 `VALIDATION_ERROR`("이미 다른 게시물에 사용된 이미지입니다."). `sharedTradeId`는 존재하지 않으면 404 `NOT_FOUND`, 인증 사용자 소유가 아니면 403 `FORBIDDEN`, 매수 체결(`side != SELL`)이면 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, 022 COM-004·COM-006, 046 TRADESHARE-001·004, Issue #23, Issue #246, Issue #248; 045 LIKE-002 |

작성자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. `instrumentId`를 생략하거나 `null`로 보내면 미태그 게시물로 생성된다(하위 호환) — 이때 응답의 `instrumentId`·`instrumentSymbol`·`instrumentName`은 모두 `null`이다. 값을 보내면 `InstrumentService.getTradableInstrumentEntity`로 존재·`tradable` 여부를 검증하며, 실패 사유(미존재·비활성)는 구분하지 않고 동일한 400 `VALIDATION_ERROR`로 응답한다. 게시물당 태그 가능한 종목은 최대 1개다. `imageId`는 `POST /api/community/posts/images`로 미리 업로드한 이미지의 식별자를 선(先)업로드-후(後)참조 방식으로 연결한다(생략 시 미첨부, 하위 호환) — 응답의 `imageId`·`imageUrl`은 연결 성공 시에만 채워지고 미첨부면 둘 다 `null`이다. 게시물당 첨부 가능한 이미지는 최대 1장이다. `likeCount`·`likedByMe`는 항상 채워지는 필드다(045 LIKE-002) — 방금 생성한 게시물은 좋아요가 있을 수 없으므로 `likeCount:0`·`likedByMe:false`로 고정되지만, 특수 분기 없이 다른 조회 경로(단건·목록 조회)와 동일한 방식으로 계산한 값이다.

`sharedTradeId`는 본인이 실제로 실행한 매도 체결 1건을 네이티브 매매 카드로 첨부한다(spec 046 — 코인·주식 모두 지원, 우선순위는 코인). 소유권·`side=SELL` 검증은 게시물 생성 시 1회만 하고 `PostSellFeedbackService.getTradeShareSummary`가 담당한다 — 이 메서드는 기존 `GET /api/ai/post-sell/{tradeId}`(`docs/api/feedback.md`의 "매도 직후 피드백 조회" 참조)가 이미 계산한 FIFO 가중평균 매수단가·수익률을 그대로 재사용할 뿐 뉴스·서술·반사실·집단 비교는 만들지 않는다(재계산 금지, PRD C-004). 응답의 `sharedTrade`는 연결 성공 시에만 채워지고(`symbol`·`name`·`market`(`STOCK`|`CRYPTO`)·`buyPrice`·`sellPrice`·`quantity`·`realizedPnl`·`returnRate`), 미첨부면 `null`이다. 게시물당 공유 가능한 매매 카드는 최대 1건이며, 생성 뒤에는 수정할 수 없다(`PATCH`가 이 필드를 받지 않는다). 이미지와 매매 카드는 한 게시물에 함께 붙일 수 없다(TRADESHARE-004).

### 커뮤니티 게시물 단건 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId` | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자","imageId":1,"imageUrl":"/api/community/posts/images/1/file","likeCount":3,"likedByMe":true,"sharedTrade":null}` (태그 없는 게시물은 `instrumentId`·`instrumentSymbol`·`instrumentName`이, 첨부 이미지 없는 게시물은 `imageId`·`imageUrl`이, 매매 카드 없는 게시물은 `sharedTrade`가 각각 모두 `null`) | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, 022 COM-004·COM-006, 046 TRADESHARE-002, Issue #25, Issue #246, Issue #248; 045 LIKE-002 |

`likeCount`는 그 게시물이 받은 좋아요 총 개수(`community_posts.like_count`), `likedByMe`는 요청자 본인이 그 게시물에 좋아요를 표시했는지 여부다(045 LIKE-002). 좋아요를 한 번도 받지 않은 게시물은 `likeCount:0`·`likedByMe:false`다.

조회 시점의 `sharedTrade` 계산은 **요청자가 아니라 게시물 작성자**를 소유자로 재사용한다 — 원장은 불변이라 생성 시 이미 검증된 값이 이후에도 계속 유효하므로, 작성자가 아닌 다른 사용자가 조회해도 403 없이 값이 보인다(소유권 재검증은 생성 시 1회뿐, spec 046 "비즈니스 규칙"). `likedByMe`는 반대로 **요청자 본인** 기준이다 — 두 필드의 기준 주체가 다르다는 점에 유의한다.

### 커뮤니티 게시물 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts | Access Bearer 필수 | `page`(기본 0, 0 이상), `size`(기본 10, 1~50), `instrumentId`(선택), `sort`(기본 `latest`, `latest`\|`popular`) | 200 `{"content":[{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자","imageId":1,"imageUrl":"/api/community/posts/images/1/file","likeCount":3,"likedByMe":true,"sharedTrade":null}],"page":0,"size":10,"totalElements":1,"totalPages":1,"hasNext":false}` | `page`·`size` 범위 밖, `sort`가 `latest`·`popular` 외 값이면 각각 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, 022 COM-004·COM-006, 046 TRADESHARE-002, Issue #24, Issue #246, Issue #248; 045 SORT-001, LIKE-002 |

`sort` 생략 시 기존과 동일하게 작성시각(`created_at`) 내림차순, 동시각 `id` 내림차순으로 정렬한다(하위 호환). `sort=popular`는 `like_count` 내림차순이며 좋아요 개수가 같으면 `created_at` 내림차순, 그마저 같으면 `id` 내림차순으로 정렬한다(동률 처리) — 정렬 기준이 바뀔 뿐 페이지네이션·필터 방식은 동일하다. 게시물이 없으면 오류가 아니라 200과 빈 `content`를 반환한다. `instrumentId` 지정 시 그 종목이 태그된 게시물만 반환하며, 존재하지 않는 `instrumentId`를 넘겨도 오류가 아니라 빈 `content`를 반환한다(생성·수정 시의 태그 유효성 검증과 달리 이 필터는 조회 조건일 뿐이다). `sort`와 `instrumentId`는 함께 사용할 수 있다 — 필터링된 결과 안에서 정렬만 바뀐다. 태그가 없는 게시물은 `instrumentId`·`instrumentSymbol`·`instrumentName`이, 첨부 이미지가 없는 게시물은 `imageId`·`imageUrl`이, 매매 카드가 없는 게시물은 `sharedTrade`가 각각 모두 `null`이다.

목록 조회는 QueryDSL `leftJoin().fetchJoin()`으로 `instrument`·`image`를 함께 로딩해 N+1을 방지한다. 각 항목의 `likeCount`·`likedByMe`(045 LIKE-002)는 이 페이지에 실린 게시물 id를 모아 `CommunityPostLikeRepository.findLikedPostIds`로 **한 번에** 조회한 결과로 채운다 — 게시물마다 좋아요 여부를 따로 조회하지 않아 N+1을 방지한다. **`sharedTrade`는 이 배치 조회 대상이 아니다.** 게시물마다 다른 `tradeId`를 가리켜 엔티티 조인·`IN` 배치 조회로 묶을 수 없고, `PostSellFeedbackService.getTradeShareSummary`가 도메인 경계를 넘는 서비스 호출이라 `sharedTradeId`가 있는 게시물 수만큼 개별 호출한다(대부분의 게시물은 `sharedTradeId`가 없어 호출 자체가 없다). 이 반복 호출은 뉴스·LLM을 부르지 않는 순수 DB 계산이라 페이지당 비용은 작지만, 진짜 배치 API가 필요해지면 별도 spec으로 다룬다.

### 커뮤니티 게시물 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId`, 본문 `{"title":"게시물 제목","content":"게시물 본문","instrumentId":1}` (`title` 최대 100자, `content` 최대 5,000자, `instrumentId`는 선택·nullable) | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자","imageId":1,"imageUrl":"/api/community/posts/images/1/file","likeCount":3,"likedByMe":true,"sharedTrade":null}` | 제목·본문 누락·빈 값·공백·최대 길이 초과, **존재하지 않거나 비활성(`tradable=false`)인 `instrumentId` 태그, 튜토리얼 전용 샘플 종목(`isTutorialSample=true`, 031)은 `tradable` 값과 무관하게 태그 대상에서 제외**는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 본인 소유가 아닌 게시물은 403 `FORBIDDEN`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, 022 COM-004, Issue #26, Issue #246; 045 LIKE-002 |

작성자 본인만 수정할 수 있으며 소유자 확인은 요청 본문이 아닌 Access Token의 인증 사용자로 판단한다. `title`·`content`는 매 요청이 전체를 교체한다(필수 필드라 생략 시 400). `instrumentId`는 **JSON Merge Patch 관례**를 따른다(2026-08-10 Issue #276 확정, A안) — 요청 본문에 `instrumentId` **키 자체가 없으면 기존 태그를 그대로 보존**하고, **키를 넣고 값을 `null`로 명시하면 태그를 해제**한다. 값을 넣으면 그 종목으로 교체한다(존재하지 않거나 비활성이면 400 `VALIDATION_ERROR`). 이 엔드포인트는 `imageId`·`sharedTradeId`를 요청으로 받지 않는다 — 첨부 이미지 교체·해제는 이번 그룹(COM-006)의, 매매 카드 수정은 spec 046의 범위 밖이며, 응답의 `imageId`·`imageUrl`·`sharedTrade`는 기존에 연결된 값이 있으면 그대로 유지되어 노출된다. `likeCount`·`likedByMe`(045 LIKE-002)는 수정과 무관하게 그 게시물의 현재 좋아요 상태를 그대로 반영한다.

### 커뮤니티 게시물 삭제

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| DELETE | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId`, 본문 없음 | 204 (본문 없음) | Access 인증 실패는 401 `UNAUTHORIZED`. 본인 소유가 아닌 게시물은 403 `FORBIDDEN`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, 022 COM-006, Issue #27, Issue #248 |

작성자 본인만 삭제할 수 있으며 소유자 확인은 Access Token의 인증 사용자로 판단한다. 삭제 시 해당 게시물에 달린 댓글을 먼저 모두 삭제한 뒤 게시물을 삭제한다. 첨부 이미지가 있으면 댓글 삭제 다음·게시물 삭제 전후로 `community_post_images` DB 행과 물리 파일을 함께 제거한다(고아 파일 방지) — 물리 파일 삭제 실패는 로그만 남기고 게시물 삭제 자체를 막지 않는다.

### 커뮤니티 게시물 이미지 업로드

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts/images | Access Bearer 필수 | `multipart/form-data`, 파트명 `image`(파일 1개, JPEG·PNG·WEBP만 허용, 5MB 이하) | 201 `{"imageId":1,"imageUrl":"/api/community/posts/images/1/file"}` | 파일이 없거나 빈 파일, 허용하지 않는 형식(JPEG·PNG·WEBP 외)은 400 `VALIDATION_ERROR`("허용하지 않는 이미지 형식입니다. JPEG, PNG, WEBP만 첨부할 수 있습니다."). 5MB 초과는 Spring `MaxUploadSizeExceededException`을 `GlobalExceptionHandler`가 400 `VALIDATION_ERROR`로 매핑. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 022 COM-006, Issue #248 |

업로더는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. 이 엔드포인트는 게시물과 아직 연결되지 않은 이미지를 저장만 하는 선(先)업로드다 — 응답의 `imageId`를 `POST /api/community/posts` 생성 요청의 `imageId`로 보내야 게시물에 연결된다. 저장 파일명은 `UUID`로 생성해 원본 파일명과 분리하고, 원본 파일명·`contentType`·크기는 `CommunityPostImage`에 그대로 보관한다. 게시물당 첨부 가능한 이미지는 최대 1장이며, 이미 다른 게시물에 연결된 `imageId`를 재사용하면 게시물 생성 시점에 400 `VALIDATION_ERROR`로 거부된다(위 "커뮤니티 게시물 작성" 참고).

### 커뮤니티 게시물 이미지 다운로드

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/images/{imageId}/file | Access Bearer 필수 | 경로 변수 `imageId` | 200, `Content-Type`은 업로드 시 저장된 `contentType` 그대로, `X-Content-Type-Options: nosniff` 포함, 본문은 이미지 원본 바이트 | 존재하지 않거나(게시물에 아직 연결되지 않은 이미지를 업로더 본인이 아닌 사용자가 요청한 경우 포함) `imageId`는 404 `NOT_FOUND`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 022 COM-006, Issue #248 |

**게시물에 연결된(공개) 이미지는 인증 사용자라면 업로더·게시물 소유자와 무관하게 누구나 조회할 수 있다** — 별도 소유권 검사가 없다(게시물 조회에 포함되는 공개적 성격의 첨부 이미지이므로 COM-003 소유권 규칙 대상이 아니다). **아직 게시물에 연결되지 않은(선업로드 상태) 이미지는 업로더 본인만 조회할 수 있고, 그 외 사용자에게는 존재를 숨겨 404로 응답한다** — 선업로드-후참조 흐름상 게시하지 않고 방치된 이미지가 정상적으로 존재하므로(PR #269 리뷰), `imageId`만 안다고 누구나 받을 수 있게 두지 않는다. 파일은 `LocalFileStorageService`가 `finplay.community.image-storage.base-directory`(기본 `./data/community-images`) 아래 저장한 것을 그대로 읽어 반환한다.

### 커뮤니티 게시물 댓글 목록 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/{postId}/comments | Access Bearer 필수 | 경로 변수 `postId` | 200 `[{"commentId":1,"authorNickname":"finplayer","content":"댓글 본문","createdAt":"2026-07-27T12:00:00","parentCommentId":null,"replies":[{"commentId":2,"authorNickname":"another","content":"대댓글 본문","createdAt":"2026-07-27T12:05:00","parentCommentId":1,"replies":[]}]}]`; 댓글이 없으면 `[]` | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-002, 022 COM-005, Issue #29, Issue #247 |

부모 댓글(`parentCommentId=null`)만 최상위 배열로 `createdAt` 오름차순 반환하며, 생성시각이 같으면 `commentId` 오름차순으로 안정 정렬한다. 각 부모 댓글의 `replies`에는 그 자식 대댓글이 같은 정렬 규칙으로 중첩 포함된다(자식이 없으면 빈 배열). 대댓글 자신의 `replies`는 항상 빈 배열이다(1단계 제한). 작성자는 fetch join으로 함께 조회해 댓글 수에 따른 추가 쿼리를 방지한다.

### 커뮤니티 게시물 댓글 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts/{postId}/comments | Access Bearer 필수 | `{"content":"댓글 본문","parentCommentId":null}` (`content` 필수, 최대 1,000자; `parentCommentId` 선택, 지정 시 같은 게시물의 기존 부모 댓글 ID) | 201 `{"commentId":1,"authorNickname":"finplayer","content":"댓글 본문","createdAt":"2026-07-27T12:00:00","parentCommentId":null,"replies":[]}` | 본문 누락·공백·1,000자 초과는 400 `VALIDATION_ERROR`. 이미 대댓글인 댓글(`parentCommentId`)에 다시 답글 시도 시 400 `VALIDATION_ERROR`("대댓글에는 답글을 남길 수 없습니다"). tombstone된(삭제된) 부모 댓글(`parentCommentId`)에 답글 시도 시 400 `VALIDATION_ERROR`("삭제된 댓글에는 답글을 남길 수 없습니다"). `parentCommentId`가 존재하지 않거나 다른 게시물 소속이면 404 `NOT_FOUND`. Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-002, 022 COM-005, Issue #28, Issue #247, Issue #277 |

작성자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. `parentCommentId`를 생략하면 기존과 동일하게 부모 댓글(0단계)로 생성된다. 대댓글(1단계)은 다시 답글을 받을 수 없다 — depth는 부모·자식 2단계로 고정.

### 커뮤니티 댓글 삭제

| Method | URL | 인증 | 응답 | 오류 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/community/comments/{commentId} | Access Bearer 필수 | 204 본문 없음 | 작성자 불일치는 403 `FORBIDDEN`. 댓글 미존재는 404 `NOT_FOUND`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-002, 022 COM-005, Issue #30, Issue #247, Issue #277 |

댓글 삭제는 소유자만 가능하며 `CommentController`(`/api/community/comments`)로 분리되어 있다. Security 공개 화이트리스트에 포함되지 않은 인증 필요 경로다. 부모 댓글(`parentCommentId=null`, 자식 유무 무관) 삭제는 이슈 #277로 CASCADE 하드 삭제에서 tombstone으로 전환됐다 — 소유자 검증(403) 통과 후 행을 실제로 지우지 않고 `content`를 `"삭제된 댓글입니다"`로, `authorNickname`을 `"(삭제됨)"`으로 치환한다(`GET /api/community/posts/{postId}/comments` 응답에 그대로 반영). `parentCommentId`·`replies`는 영향받지 않으며, 자식 대댓글은 원래 내용 그대로 보존된다. 반대로 대댓글(자식, `parentCommentId != null`) 자신을 삭제하면 기존과 동일하게 하드 삭제되어 부모의 `replies`에서 사라진다. **이미 tombstone된 부모 댓글을 소유자가 다시 삭제 요청해도 404가 아니라 204를 반환한다** — `deletedAt`만 멱등하게 갱신되며 `content`·`authorNickname` 표시는 그대로 유지된다(PR #331 리뷰 확정, 새 오류 코드 없음).

### 커뮤니티 게시물 좋아요 표시

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts/{postId}/likes | Access Bearer 필수 | 경로 변수 `postId`, 본문 없음 | 신규 좋아요 생성 시 201 `{"postId":1,"likeCount":1,"likedByMe":true}`. 이미 좋아요한 상태로 재요청하면 오류 없이 200으로 현재 상태(`likeCount`·`likedByMe` 불변)를 그대로 반환(멱등) | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 045 LIKE-001, LIKE-002 |

본인이 작성한 게시물에도 좋아요를 표시할 수 있다(차단 로직 없음). 회원 1인당 게시물 1개에 좋아요는 최대 1개이며(`community_post_likes` 유니크 제약으로 DB 레벨에서도 강제), 이미 좋아요한 상태에서 다시 호출해도 새 행을 만들거나 오류를 내지 않고 현재 상태만 반환한다. `likeCount`는 `community_posts.like_count` 비정규화 카운터를 원자적 `UPDATE`(`p.likeCount = p.likeCount + 1`)로 증가시킨 값이다. 같은 게시물의 좋아요 표시·취소는 게시물 행 비관적 락(`SELECT ... FOR UPDATE`)을 트랜잭션 첫 문장으로 잡아 직렬화한다 — 동시 요청이 InnoDB 데드락으로 500을 내던 것을 PR #442 리뷰가 실측 재현해 도입했다.

### 커뮤니티 게시물 좋아요 취소

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| DELETE | /api/community/posts/{postId}/likes | Access Bearer 필수 | 경로 변수 `postId`, 본문 없음 | 204 (본문 없음). 좋아요한 적 없는 상태에서 취소해도 오류 없이 204(no-op, 멱등) | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 045 LIKE-001 |

좋아요 취소는 `community_post_likes` 행을 실제로 삭제하고(tombstone 아님 — 좋아요를 눌렀던 이력을 보존할 요구가 없음), `community_posts.like_count`를 원자적 `UPDATE`로 감소시킨다. 좋아요한 적이 없는 사용자가 취소를 요청해도 오류 없이 204를 반환하며 카운터는 변하지 않는다. 표시와 마찬가지로 게시물 행 비관적 락으로 직렬화하며, 감소 쿼리에 `like_count > 0` 하한 가드가 있어 `like_count`는 어떤 경합에서도 0 미만으로 내려가지 않는다(인기순 정렬 SORT-001의 기준값이라 오염되면 정렬을 못 믿게 된다).
