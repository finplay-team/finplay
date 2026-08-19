# API 라우트 지도

전체 엔드포인트를 한눈에 보는 레지스트리다. **controller를 추가/변경하면 이 문서의 라우트 목록과 `docs/api-contracts.md`의 해당 절을 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

- 엔드포인트별 요청·응답·오류 계약은 `docs/api-contracts.md`에 있다.
- 실행 중인 앱의 자동 생성 문서는 Swagger UI에서 확인한다 — `http://localhost:8080/swagger-ui.html`

## 라우트 목록

| Method | URL | 도메인 | 요약 | Spec |
|---|---|---|---|---|
| POST | /api/auth/email-verifications | auth | 인증번호 발송 (202, 본문 없음). 발송 제한·중복 이메일 검사 | 002 AUTH-004 |
| POST | /api/auth/email-verifications/confirm | auth | 인증번호 확인 후 `signupVerificationToken` 발급 | 002 AUTH-004 |
| POST | /api/auth/password-resets | auth | 비밀번호 재설정 인증번호 발송 (202, 본문 없음). 비인증 공개 경로, 발송 제한·가입 여부·소셜 전용 계정 검사 | 002 AUTH-006, Issue #115 |
| POST | /api/auth/password-resets/confirm | auth | 인증번호 + 새 비밀번호를 한 요청으로 받아 비밀번호 재설정 (204, 본문 없음). 비인증 공개 경로, 기존 Refresh Token 전체 폐기 + **새 토큰 미발급**(전 기기 로그아웃) | 002 AUTH-006, Issue #116 |
| POST | /api/auth/signup | auth | 가입 인증 토큰을 소비하는 이메일 회원가입 (201, 토큰 발급) | 002 Issue #4 |
| POST | /api/auth/login | auth | 이메일·비밀번호 검증 후 Access·Refresh 토큰 발급 | 002 AUTH-002 |
| POST | /api/auth/refresh | auth | 유효한 Refresh Token을 회전하고 새 Access·Refresh 토큰 발급 | 002 AUTH-002 |
| POST | /api/auth/logout | auth | 본인의 Refresh Token을 폐기하고 로그아웃 (204, 본문 없음) | 002 AUTH-002 |
| GET | /api/auth/me | auth | 인증 사용자 본인의 id·email·nickname·가입 방식 조회 | 002 AUTH-005, Issue #8 |
| PATCH | /api/auth/me/nickname | auth | 재인증(현재 비밀번호 또는 reauthToken) 후 본인 닉네임 변경 | 002 AUTH-005, Issue #54 |
| PATCH | /api/auth/me/password | auth | 현재 비밀번호 확인 후 본인 비밀번호 변경. 기존 Refresh Token 전체 폐기 + 요청 기기용 새 토큰 쌍 발급(200 TokenResponse) | 002 AUTH-005, Issue #114 |
| POST | /api/auth/email-changes | auth | 인증 사용자의 새 이메일 변경 인증번호 발송 (202, 본문 없음). 재인증 증명·중복 이메일·발송 제한 검사 | 002 AUTH-005, Issue #55 |
| POST | /api/auth/email-changes/confirm | auth | 새 이메일 인증번호 확인 후 users.email 원자적 변경, 성공 시 기존 Refresh Token 전체 폐기 | 002 AUTH-005, Issue #56 |
| GET | /api/auth/oauth/{provider}/authorize | auth | 카카오·네이버 OAuth 인가 시작. `purpose` 생략/`login`은 공개 302, `purpose=reauth`는 인증 필요 200 JSON | 002 AUTH-003, Issue #9, Issue #53 |
| GET | /api/auth/oauth/{provider}/callback | auth | OAuth 콜백. LOGIN purpose는 프론트 `oauth.login-redirect-uri`로 302 리다이렉트(쿼리 `code`에 1회용 교환 코드만 실림, 토큰 본문 없음). REAUTH purpose도 이제 200 JSON이 아니라 프론트 `oauth.reauth-redirect-uri`로 302 리다이렉트하며 `reauthToken` 원문 없이 1회용 교환 코드만 싣는다(039). REAUTH는 `oauth_state` 쿠키 이중제출 없이도 통과한다(039 OAUTH-REAUTH-002) | 002 AUTH-003, Issue #10, Issue #53, 039 OAUTH-REAUTH-002/003 |
| POST | /api/auth/oauth/login-exchange | auth | 위 LOGIN 리다이렉트의 교환 코드를 로그인 토큰으로 교환(공개, 1회용·TTL 60초) | 002 AUTH-003, Issue #10 |
| POST | /api/auth/oauth/reauth-exchange | auth | 위 REAUTH 리다이렉트의 교환 코드를 `reauthToken`으로 교환(공개, 1회용·TTL 60초) | 039 OAUTH-REAUTH-004 |
| GET | /api/instruments?market= | market | 인증 사용자의 종목 목록 조회 (market 선택: STOCK·CRYPTO, 생략 시 전체). 응답 `InstrumentResponse`에 `isTutorialSample` 필드 추가(031, 튜토리얼 전용 샘플 종목 6행 포함) | 003 MKT-001, Issue #14, 031 SANDBOX-001 |
| GET | /api/instruments/{instrumentId}/price | market | 인증 사용자의 종목 현재가 조회. 주식은 StockPriceProvider(재생/실시간 공급자 불문), 코인은 PriceStore(Redis)에서 유효한 최신 가격을 반환 — 연결 유지 + 수신 이력 있음이면 마지막 관측 시각이 얼마나 오래됐든 경과 시간과 무관하게 항상 `status: "AVAILABLE"`(체결 판정은 별도) | 003 MKT-002/MKT-003/MKT-004, 032, 036, Issue #16, Issue #355 |
| GET | /api/instruments/{instrumentId}/candles?interval=1m\|1d\|1w\|1M&from=&to=&cursor= | market | 인증 사용자의 캔들 조회. `interval`은 대소문자 구분 4값(`1m`·`1d`·`1w`·`1M`), 응답은 `CandleListResponse` 봉투(`content`·`nextCursor`·`hasNext`)이며 `content`는 최대 200개. **`cursor`(선택, ISO-8601 `LocalDateTime` 문자열)로 과거 방향 페이지네이션**을 한다 — 직전 페이지 `content[0].sourceTime`을 그대로 넣으면 그보다 과거의 다음 페이지를 받는다(배타 상한, 주식 `1m`은 예외적으로 커서를 조회에 적용하지 않고 `hasNext`를 항상 `false`로 고정). 주식 `1m`은 `stock_candles` 과거 거래일 재생(미마감 분봉 제외), 주식 `1d`·`1w`·`1M`은 같은 1분봉을 거래일·주(월요일 시작)·월(1일 시작) 단위로 **집계**(공개 상한 적용, 진행 중 버킷 포함, 재생세션 미준비 시 모든 interval 공통 200 빈 `content`). 코인은 4개 interval 모두 빗썸 공개 캔들 REST(`minutes/1`·`days`·`weeks`·`months`)에 **위임**(저장 없음, 진행 중 분봉/봉 포함, 조회 실패 시 502) | 003 MKT-002·MKT-008, 013 MKT-009, 048 CANDLE-PAGE-001~012·025·026, Issue #17, Issue #20, Issue #143, Issue #473 |
| GET | /api/stocks/stream | market | 주식 전용 SSE 구독. 구독 직후 16종 전체를 snapshot 1건으로 전송, 이후 매분 새로 공개된 가격을 price로, 개장·마감 전환을 status로 push | 003 MKT-002, Issue #19 |
| POST | /api/dev/stock-replay-imports | market | **(local 프로필 전용)** 08:10 수집 배치와 08:40 세션 확정 배치를 즉시 한 번 실행해 실제 KIS 분봉을 채운다. 그 시각을 기다리지 않거나 앱이 꺼져 있어 건너뛴 날의 보정용. 요청 본문 없음. `local` 프로필이 아니면 컨트롤러 빈 자체가 없어 404 | 003 MKT-005 (개발 도구, 이슈 없음) |
| POST | /api/community/posts | community | 인증 사용자의 텍스트 게시물 작성. `instrumentId`(선택)로 종목을 하나 태그할 수 있다 — 존재하지 않거나 비활성(`tradable=false`) 종목은 400 `VALIDATION_ERROR`. `imageId`(선택)로 `POST /api/community/posts/images`에서 미리 업로드한 이미지 하나를 선(先)업로드-후(後)참조로 연결한다 — 존재하지 않으면 404, 타인 소유면 403, 이미 다른 게시물에 연결됐으면 400. `sharedTradeId`(선택)로 본인 매도 체결 1건을 네이티브 매매 카드로 첨부한다(코인·주식 모두 지원) — 존재하지 않으면 404, 타인 소유면 403, 매수 체결이면 400. `imageId`와 `sharedTradeId`를 동시에 지정하면 400. 응답에 `likeCount`·`likedByMe`(생성 직후는 항상 0·false) 포함 | 008 COM-001, 022 COM-004·COM-006, 046 TRADESHARE-001·004, Issue #23, Issue #246, Issue #248; 045 LIKE-002 |
| GET | /api/community/posts?page=&size=&instrumentId=&sort= | community | 인증 사용자의 게시물 목록을 페이지네이션으로 조회. `sort`는 `latest`(기본값, 생략 시 기존과 동일한 최신순)\|`popular`(좋아요 내림차순, 동률은 최신순) — 그 외 값은 400 `VALIDATION_ERROR`. `instrumentId` 지정 시 그 종목이 태그된 게시물만 반환(미지정 시 전체, `sort`와 함께 사용 가능). 응답에 태그 종목·첨부 이미지·공유 매매 카드(`sharedTrade`) 정보, 각 게시물의 `likeCount`·`likedByMe`(배치 조회로 N+1 방지) 포함(없으면 각각 `null`) | 008 COM-001, 022 COM-004·COM-006, 046 TRADESHARE-002, Issue #24, Issue #246, Issue #248; 045 SORT-001, LIKE-002 |
| GET | /api/community/posts/{postId} | community | 인증 사용자의 커뮤니티 게시물 단건 조회. 응답에 태그 종목·첨부 이미지·공유 매매 카드(`sharedTrade`) 정보, `likeCount`·`likedByMe` 포함(없으면 각각 `null`) | 008 COM-001, 022 COM-004·COM-006, 046 TRADESHARE-002, Issue #25, Issue #246, Issue #248; 045 LIKE-002 |
| PATCH | /api/community/posts/{postId} | community | 본인 소유 커뮤니티 게시물의 제목·본문 전체 교체 수정. `instrumentId`(선택)는 JSON Merge Patch 관례 — 키 부재는 태그 보존, 명시 `null`은 태그 해제(Issue #276). 존재하지 않거나 비활성 종목 태그는 400 `VALIDATION_ERROR`. 첨부 이미지 교체·해제는 지원하지 않는다(COM-006 범위 제외). 응답에 `likeCount`·`likedByMe` 포함 | 008 COM-001, 022 COM-004, Issue #26, Issue #246, Issue #276; 045 LIKE-002 |
| DELETE | /api/community/posts/{postId} | community | 본인 소유 커뮤니티 게시물 삭제 (204, 본문 없음, 댓글도 함께 삭제, 첨부 이미지가 있으면 DB 행·물리 파일도 함께 제거) | 008 COM-001, 022 COM-006, Issue #27, Issue #248 |
| POST | /api/community/posts/images | community | 인증 사용자의 이미지 파일(멀티파트 파트명 `image`) 업로드 (201, `imageId`·`imageUrl` 반환). 게시물과 아직 연결되지 않은 상태로 저장하는 선(先)업로드 — `POST /api/community/posts` 생성 시 `imageId`로 참조해 연결한다. JPEG·PNG·WEBP만 허용, 5MB 초과·빈 파일·허용하지 않는 형식은 400 `VALIDATION_ERROR` | 022 COM-006, Issue #248 |
| GET | /api/community/posts/images/{imageId}/file | community | 이미지 원본 바이트 다운로드(`Content-Type`은 업로드 시 형식 그대로). 인증 사용자라면 누구나 조회 가능(업로드자·게시물 소유자 제한 없음), 존재하지 않는 `imageId`는 404 `NOT_FOUND` | 022 COM-006, Issue #248 |
| GET | /api/community/posts/{postId}/comments | community | 인증 사용자가 게시물의 댓글을 조회. 부모 댓글을 오래된 순으로 나열하고 각 부모 밑에 그 대댓글(`replies`)을 오래된 순으로 중첩 포함 | 008 COM-002, 022 COM-005, Issue #29, Issue #247 |
| POST | /api/community/posts/{postId}/comments | community | 인증 사용자의 댓글 작성. `parentCommentId`(선택)로 기존 댓글에 대댓글을 남길 수 있다 — 부모가 이미 대댓글이면 400 `VALIDATION_ERROR`, 존재하지 않거나 다른 게시물 소속이면 404 `NOT_FOUND` | 008 COM-002, 022 COM-005, Issue #28, Issue #247 |
| DELETE | /api/community/comments/{commentId} | community | 본인 소유 댓글 삭제 (204, 본문 없음). 부모 댓글을 삭제하면 실제로 삭제되지 않고 내용·작성자 표시가 치환되며, 자식 대댓글은 그대로 보존된다(tombstone) | 008 COM-002, 022 COM-005, Issue #30, Issue #247, Issue #277 |
| POST | /api/community/posts/{postId}/likes | community | 인증 사용자의 게시물 좋아요 표시(멱등) — 신규 생성 시 201, 이미 좋아요한 상태로 재요청하면 오류 없이 현재 상태 그대로 200. 본인 게시물에도 표시 가능(차단 없음). 존재하지 않는 게시물은 404 `NOT_FOUND` | 045 LIKE-001 |
| DELETE | /api/community/posts/{postId}/likes | community | 인증 사용자의 게시물 좋아요 취소(204, 본문 없음, 멱등) — 좋아요한 적 없는 상태에서 취소해도 오류 없이 처리. 존재하지 않는 게시물은 404 `NOT_FOUND` | 045 LIKE-001 |
| POST | /api/orders | order | 인증 사용자의 시장가 매수·매도 주문을 검증·즉시 전량 체결하고 주문+체결 결과 반환 (201, 매도는 FIFO lot 배분·실현손익 포함). `Idempotency-Key` 헤더 필수 — 동일 키+동일 본문 재요청은 최초 응답 재현(재체결 없음), 동일 키+다른 본문 또는 재현 실패 시 409 `IDEMPOTENCY_CONFLICT` | 004 ORD-001~004·006, 005 ORD-001~006(매도), Issue #13, Issue #41, Issue #22 |
| POST | /api/orders/limit | order | 인증 사용자의 코인(CRYPTO) 전용 지정가 매수·매도 주문을 검증·예약(매수는 현금, 매도는 수량)하고 `PENDING` 상태로 생성 (201). 즉시체결 조건을 충족해도 생성 시점에 거부하지 않음 — 체결은 LMT-002 가격 갱신 트리거에서만 발생. 선택된 튜토리얼 샘플 주문은 서버가 잠근 현재 attempt/run에 귀속. `Idempotency-Key` 헤더 필수, 재요청·경합 처리는 `POST /api/orders`와 동일 패턴 | 015 LMT-001, Issue #210; 039 TUTORIAL-FLOW-003, Issue #378 |
| DELETE | /api/orders/{orderId} | order | 인증 사용자 본인 소유의 `PENDING` 코인 지정가 주문을 취소하고 예약 현금(매수)·예약 수량(매도)을 반환 (204, 본문 없음). 검증 순서는 존재(404)→소유(403)→상태(409) 고정. `Idempotency-Key` 헤더 불필요 | 015 LMT-003, Issue #218 |
| PATCH | /api/orders/{orderId} | order | 인증 사용자 본인 소유의 `PENDING` 코인 지정가 주문의 `limitPrice`·`quantity`를 부분/전체 갱신 (200, `LimitOrderResponse`). 변경 전 예약을 해제하고 변경 후 값으로 재예약(매수는 현금, 매도는 수량), 부족하면 거부하고 변경 전 상태 유지. 검증 순서는 존재(404)→소유(403)→상태(409) 고정(LMT-003과 동일), 신규 오류 코드 없음. `Idempotency-Key` 헤더 불필요(자연 멱등) | 015 LMT-005, Issue #239 |
| GET | /api/orders?market=&cursor=&limit= | order | 인증 사용자 본인의 시장별(`STOCK`\|`CRYPTO`) 주문 목록을 최신순으로 조회. nullable `practiceAttemptId`·`practiceAttemptRunNumber`로 튜토리얼 귀속을 노출하며 체결 전용 필드는 노출하지 않음 | 006 PORT-003, 018 PORT-003(1차 고도화), Issue #21, Issue #182; 039 TUTORIAL-FLOW-003·007, Issue #378 |
| GET | /api/orders/pending?market=&cursor=&limit= | order | 인증 사용자 본인의 시장별 `PENDING` 지정가 주문을 최신순으로 조회. nullable attempt ID/run으로 현재 실행 주문을 일반·과거 실행 주문과 구분하며 체결·취소된 주문은 이후 제외 | 015 LMT-004, Issue #235; 039 TUTORIAL-FLOW-003·007, Issue #378 |
| POST | /api/exit-plans | order | 코인 holding에 대한 **일반 경로**(`intentionId` 생략) 손절·익절 OCO 예약 생성 (201). `holdingId`·`quantity`·`exitPriceType` 필수, `Idempotency-Key` 헤더 필수. `intentionId`를 지정하는 교육 경로는 아직 지원하지 않음(400) | 021 일반 리스크관리 OCO, Issue #348 |
| DELETE | /api/exit-plans/{exitPlanId} | order | 인증 사용자 본인 소유의 `PENDING` OCO 예약을 취소하고 예약 수량을 반환 (204, 본문 없음). 경로(일반/교육) 공통 | 021 일반 리스크관리 OCO, Issue #348 |
| GET | /api/exit-plans?status= | order | 인증 사용자 본인의 OCO 예약 목록을 `id` 내림차순으로 순수 조회. 선택 `status` 쿼리 파라미터(`PENDING`\|`FILLED_TAKE_PROFIT`\|`FILLED_STOP_LOSS`\|`CANCELLED`) 생략 시 `PENDING` 기본값, 허용 값 밖은 400 `VALIDATION_ERROR`. 경로(일반/교육) 무관, 응답은 생성·취소와 같은 `ExitPlanResponse`를 재사용해 `holdingId`는 항상 non-null, `intentionId`·`buyTradeId`는 일반 경로에서 null | 021 일반 리스크관리 OCO, Issue #348 |
| GET | /api/accounts/summary?market= | account | 인증 사용자 본인의 시장별(`STOCK`\|`CRYPTO`) 계좌 요약(현금잔고·예약현금·보유평가액·총평가액·실현손익·미실현손익·수익률) 조회. `market` 쿼리 파라미터 필수 | 006 ACCT-002, Issue #81, 015 LMT-004(reservedCash), Issue #235 |
| GET | /api/holdings?market= | portfolio | 인증 사용자 본인의 시장별(`STOCK`\|`CRYPTO`) 활성 보유 종목 목록(holding PK·수량·예약수량·평균단가·현재가·평가금액·미실현손익·수익률·시세 상태 + 종목 표시 정보) 조회. `market` 쿼리 파라미터 필수, 전량 매도 종목은 목록에서 제외 | 006 PORT-001, Issue #52, 015 LMT-004(reservedQuantity), Issue #235, Issue #444(holdingId) |
| GET | /api/trades?market=&cursor=&limit= | order | 인증 사용자 본인의 시장별(`STOCK`\|`CRYPTO`) 체결 내역을 `executedAt` 내림차순(동시각 `id` 내림차순)으로 커서 페이지네이션 조회. `market` 쿼리 파라미터 필수, `cursor`·`limit`(기본 20, 1~100) 선택. 매도 건은 실현손익 포함 | 006 PORT-002, Issue #82 |
| GET | /api/journal?market=&cursor=&limit= | journal | 인증 사용자 본인의 매수·매도 회고를 한 목록으로 병합해 `createdAt` 내림차순(동시각 체결 ID 내림차순)으로 커서 페이지네이션 조회. `market` 쿼리 파라미터 필수, `cursor`·`limit`(기본 20, 1~100) 선택. 통합 `journalId`는 노출하지 않고 `journalType`+원래 체결 ID로 식별 | 007 JOUR-006, Issue #203 |
| POST | /api/trades/{buyTradeId}/journal | journal | 본인 소유 매수 체결 1건에 투자일기(자유 텍스트 `content`) 1건 작성 (201). 매수 체결이 아니거나 이미 일기가 있으면 실패 | 007 JOUR-001, Issue #159 |
| POST | /api/trades/{sellTradeId}/sell-journal | journal | 본인 소유 매도 체결 1건에 매도 회고(자유 텍스트 `content`) 1건 작성 (201). 매도 체결이 아니거나 이미 회고가 있으면 실패 | 007 JOUR-003, Issue #183 |
| PATCH | /api/trades/{sellTradeId}/sell-journal | journal | 본인 소유 매도 체결 1건에 이미 작성된 매도 회고의 본문을 교체 (200). 매도 체결이 아니거나 회고가 아직 없으면 실패, 잠금 없음(횟수 제한 없이 수정 가능) | 007 JOUR-004, Issue #190 |
| PATCH | /api/trades/{buyTradeId}/journal | journal | 본인 소유 매수 체결 1건에 이미 작성된 투자일기의 본문을 교체 (200). 매수 체결이 아니거나 일기가 아직 없으면 실패, 잠금 없음(매도 배분 여부와 무관하게 항상 수정 가능) | 007 JOUR-002, Issue #197 |
| POST | /api/favorites | education | 거래 가능한 종목을 본인 즐겨찾기에 등록 | 016 EDU-PRACTICE-002, candidate 1, Issue #163 |
| GET | /api/favorites | education | 본인 즐겨찾기를 등록 최신순으로 순수 조회 | 016 EDU-PRACTICE-002, candidate 2, Issue #168 |
| DELETE | /api/favorites/{instrumentId} | education | 본인 즐겨찾기 해제 | 016 EDU-PRACTICE-002, candidate 3, Issue #172 |
| POST | /api/education/practice/intentions | education | favorite로 선행 확인한 종목의 매수 전 수량·손절가·익절가 기록 | 016 EDU-PRACTICE-003·013, candidate 4, Issue #175 |
| PUT | /api/education/practice/attempts/{market} | education | insert→attempt→progress 잠금으로 legacy 완료와 직렬화. 새 빈 행만 원장 쓰기 없이 결정적 완료 `REPLAY`로 lazy 전환하고 기존 완료는 무변경, 기존 미완료+completion 충돌은 정합성 오류 | 039 TUTORIAL-FLOW-001·002·005, Issue #378 |
| POST | /api/education/practice/attempts/{market}/restart | education | 미완료 현재 실행의 pending 예약과 순보유를 원자 정리한 뒤 run을 증가시키고 종목 선택 상태로 초기화. 완료 attempt는 무변경 `REPLAY` | 039 TUTORIAL-FLOW-003·004·005, Issue #378 |
| GET | /api/education/practice/attempts/{market}/chart | education | attempt seed·version·anchor로 재현한 29+1 일봉을 side effect 없이 순수 조회. ACTIVE는 3초마다 가상 1분 진행하고 완료 `REPLAY`는 `completedAt`에 고정되어 이후 변하지 않음 | 039 TUTORIAL-FLOW-005·009·010·011·012, Issue #378 |
| POST | /api/education/practice/attempts/{market}/tick | education | 현재 attempt를 먼저 잠그고 현재 canonical 가격으로 같은 run의 지정가 주문 조건을 판정·체결한 뒤 같은 관측 시각의 29+1 차트 반환. **생성기 버전 2(대본)는 대본 커서를 전진시키며 건너뛴 가상 분마다 순차 판정한다** | 039 TUTORIAL-FLOW-010·011·012, Issue #378; 041 SCENARIO-013, Issue #472 |
| PUT | /api/education/practice/attempts/{market}/exit-preset | education | 현재 실행 세대의 손절·익절 프리셋(`CAUTIOUS`\|`BALANCED`\|`RELAXED`) 선택. **보유 중이 아닐 때만** 허용하며 응답은 `PracticeAttemptResponse` | 042 EXITPRESET-003, Issue #477 |
| PUT | /api/education/practice/attempts/{market}/instrument | education | 현재 attempt에 같은 시장의 거래 가능한 튜토리얼 샘플 종목을 선택하고 실행 anchor·date·seed·generator version을 영속 | 039 TUTORIAL-FLOW-006, Issue #378 |
| GET | /api/education/practice/attempts/{market}/orders | education | 현재 attempt의 현재 run에 귀속된 주문을 상태(PENDING·FILLED·CANCELLED) 무관 `id` 오름차순 순수 조회. `GET /api/orders`·`/pending`의 샌드박스 제외 필터를 우회하는 별도 경로. attempt 없으면 빈 배열 | 043, Issue #435 |
| GET | /api/education/practice/synthetic-prices/{instrumentId} | education | 튜토리얼 전용 서버 생성 랜덤워크 시계열(제목·틱초·가격 100틱) 조회. 실제 시세·evidence와 무관, 저장소 없음. 거래 불가 종목도 허용하고 시세가 없으면 fallback 가격으로 생성해 `PRICE_UNAVAILABLE`이 발생하지 않는다. `instrumentId` 0 이하는 400 `VALIDATION_ERROR`, 미존재 종목은 404 `NOT_FOUND` | 016 EDU-PRACTICE-003(#193), Issue #193 tasks 항목4 |
| POST | /api/education/practice/holding-observations | education | 영속 `PracticeAttempt`가 있는 샘플 종목은 현재 run의 자동 risk snapshot BUY와 holding을 재해석해 canonical 가격 관찰을 append-only 저장하며 favorite·intention을 요구하지 않음. attempt가 없는 기존 샘플·실제 종목은 026 chain 호환 유지 | 026 MKT-PRACTICE-004·005·007, Issue #300; 039 TUTORIAL-FLOW-008·011, Issue #378 |
| POST | /api/education/practice/holding-reflections | education | 영속 `PracticeAttempt`가 있는 샘플 종목은 attempt 선잠금 후 현재 run snapshot·관찰·5분 내 SELL을 재검증하고 completion·progress·attempt `COMPLETED`·시장별 최초 보상을 원자 확정. attempt가 없는 기존 샘플·실제 종목은 026 chain/progress 계약 유지 | 026 MKT-PRACTICE-004·005·007·009, Issue #303; 031 SANDBOX-006·007·008, Issue #339; 039 TUTORIAL-FLOW-005·008·012, Issue #378 |
| GET | /api/education/practice?market= | education | 영속 `PracticeAttempt`가 있으면 현재 run의 risk snapshot·귀속 BUY/SELL·관찰·reflection으로 4단계 진행 또는 불변 `REPLAY`를 순수 조회하며 stale run과 favorite·intention을 배제. attempt가 없는 기존 샘플·실제 종목은 026 chain과 OCO 분리를 호환 유지 | 026 MKT-PRACTICE-008, Issue #305; 031 SANDBOX-005·007, Issue #339; 039 TUTORIAL-FLOW-001·005·007·008·012, Issue #378 |
| POST | /api/education/practice/price-sessions | education | 사용자·코인 종목별 ACTIVE 가상 가격 세션 생성. 종목이 `market=CRYPTO`·`tradable=true`가 아니면 409 `INSTRUMENT_NOT_TRADABLE`, 동일 사용자·종목 ACTIVE 중복은 409 `PRACTICE_PRICE_SESSION_ALREADY_ACTIVE`(동시 생성의 unique 위반도 동일 매핑). 시작가는 실제 유효 현재가 anchor, 없으면 `10000.00000000` | 030 COIN-PRICE-RUNTIME-001~003, Issue #318 |
| GET | /api/education/practice/price-sessions/{sessionId} | education | 본인 세션의 cursor·현재가·상태 조회. 없거나 타인 소유는 404 `NOT_FOUND`(존재 은닉) | 030 COIN-PRICE-RUNTIME-001·002, Issue #318 |
| POST | /api/education/practice/price-sessions/{sessionId}/ticks | education | `expectedTick`(정확히 `currentTick+1`)으로 세션을 한 tick 진행. 세션 없음·타인 소유는 404 `NOT_FOUND`, COMPLETED 세션 진행은 409 `PRACTICE_PRICE_SESSION_CLOSED`, tick 불일치는 409 `PRACTICE_PRICE_TICK_CONFLICT`. tick 99 도달 시 세션이 `COMPLETED`로 전이하며, 같은 트랜잭션에서 세션 전용 이벤트가 같은 세션의 PENDING 교육 지정가 주문을 체결·취소·예약 반환한다 | 030 COIN-PRICE-RUNTIME-004·005·007·008, Issue #319·#320 |
| POST | /api/education/practice/limit-orders | education | ACTIVE 가격 세션에 귀속된 코인 지정가 BUY 생성(side는 서버 고정). 세션·종목 없음·타인 소유는 404 `NOT_FOUND`, 거래 불가 종목은 409 `INSTRUMENT_NOT_TRADABLE`, COMPLETED 세션은 409 `PRACTICE_PRICE_SESSION_CLOSED`, 요청 종목이 세션과 불일치는 409 `PRACTICE_PRICE_SESSION_MISMATCH`, 세션에 PENDING 교육 주문이 이미 있으면 409 `PRACTICE_LIMIT_ORDER_ALREADY_PENDING` | 030 COIN-PRICE-RUNTIME-006, Issue #320 |
| GET | /api/instruments/{instrumentId}/price-moves | feedback | 종목의 변동 원인 카드 목록 조회. 주식은 현재 재생세션 원본 거래일 중 `revealTime`이 지난 카드만(스포일러 차단) `windowStart` 오름차순, 각 카드의 근거는 발행시각 내림차순. 카드 0건·재생세션 미준비 모두 200(후자는 `originTradeDate=null`). `status`(`READY`·`EMPTY`·`NOT_YET`)로 둘을 구별한다 | 012 FEED-006, Issue #180, Issue #280 |
| GET | /api/instruments/{instrumentId}/news | feedback | 종목의 뉴스·공시 목록과 AI 요약 순수 조회. 주식은 09:00 이후에만 열리고 발행시각이 재생 시각을 지난 것만 노출하며, `summaryScope`가 15:30 전후로 `PRE_MARKET`→`FULL`로 바뀐다. 목록은 발행시각 내림차순 + `id` 내림차순이고 상한 초과 시 공시를 먼저 채운다. 코인은 재생세션·개장 게이트와 무관하게 조회 시각 기준 최근 24시간 뉴스와 `ROLLING_24H` 요약 `generated_at` 최신 1행을 돌려주고 `originTradeDate`는 `null`이다. 개장 전·재생세션 미준비·기사 0건·요약 행 없음·서술 실패가 전부 200(상태값은 spec §C-4) | 012 FEED-008, Issue #188 |
| GET | /api/market/briefing?market= | feedback | 시장 단위 개장 전 브리핑 순수 조회. 주식은 **spec §C-2의 `전장` 구간 기사·공시만**(장중 기사 절대 미포함)이고 Part C와 09:00 하한이 같다. `items`는 저장하지 않고 조회 시 같은 구간 질의로 다시 만들며 상한은 `max-items-per-briefing`. 재생세션 미준비는 `EMPTY`·`originTradeDate=null`, 개장 전은 `NOT_YET`(Part C와 의도된 차이, spec §C-4). 코인은 재생세션과 무관하게 최근 24시간 코인 뉴스와 `generated_at` 최신 1행을 돌려주며 `originTradeDate=null`이고 `NOT_YET`이 되지 않는다. `market` 누락·허용 값 밖은 400 | 012 FEED-009, Issue #188 |
| GET | /api/ai/post-sell/{tradeId} | feedback | 본인 매도 체결 1건의 매도 직후 피드백. 원장의 FIFO 수치(배분 가중평균 매수단가·매도가·수량·수수료·실현손익·수익률·보유기간) + 보유 구간 변동 원인 카드 + 관찰형 서술. `buyAt`은 배분된 lot 중 가장 이른 체결 시각이다. **주식**은 `buyAt`·`sellAt`이 원본 거래일 축이고, 같은 원본 거래일 안에서 완결된 매매만(`sameSessionCompleted=true`) 카드·극값·반사실·집단 비교를 포함하며, 매도 후 흐름·반사실이 **그 체결의 서비스 날짜 15:30** 이후에만 열린다(spec §C-5). **코인 체결도 200이다**(이슈 #275) — 시각이 전부 실제 절대 시각이라 `sameSessionCompleted`가 항상 `true`이고, 게이트가 15:30이 아니라 **그 체결 날짜의 다음 KST 자정**이며, 집단 비교는 매일 00:05 배치가 확정한다. 응답 필드는 시장에 따라 달라지지 않고 `holdHighBasis`(`MINUTE`\|`DAILY`)가 보유 구간 극값의 정밀도를 알린다 — 계약 상세는 `docs/api-contracts.md`의 "코인 체결의 차이" 소절(spec §FEED-012). **투자일기에 의존하지 않는다** | 012 FEED-007, Issue #208 |
| GET | /api/rankings?market=&limit= | ranking | 시장별(`STOCK`\|`CRYPTO`) 실현손익 상위 랭킹 조회. `market` 쿼리 파라미터 필수(누락·미지원 리터럴은 400 `VALIDATION_ERROR`). `limit`은 선택이며 **컨트롤러가 거부하지 않고** 서비스가 클램핑(생략·0 이하→10, 51 이상→50) — `GET /api/trades`·`GET /api/orders`의 범위 밖 400과 의도적으로 다름. 매도 체결 이력이 없는 회원은 제외, 동점자는 공동 순위. 응답에 `status`(`READY`/`REBUILDING`/`UNAVAILABLE`)를 함께 실어 빈 `content`가 "랭킹 대상자 없음"·"ZSET 유실로 집계 준비 중"·"Redis 연결 장애"를 구별하게 한다 — Redis 장애 시에도 500이 아니라 200이다 | 014 RANK-001, Issue #187·#279·#288 |
| GET | /api/rankings/me?market= | ranking | 인증 사용자 본인의 시장별 실현손익 순위 단건 조회. 대상은 인증 토큰의 본인으로 고정(다른 사용자 지정 불가). `market` 필수(누락·미지원 리터럴은 400 `VALIDATION_ERROR`). 상위 노출 구간(`GET /api/rankings`의 limit)과 무관하게 항상 정확한 보정 순위를 반환하고, 매도 체결 이력이 없으면 `rank`만 `null`(오류 아님). `rank: null`은 `status`(`READY`=매도 이력 없음 / `REBUILDING`=ZSET 유실로 집계 준비 중 / `UNAVAILABLE`=Redis 연결 장애)와 함께 읽는다 | 014 RANK-002, Issue #233·#279·#288 |
| POST | /api/watchlist-items | watchlist | 인증 사용자가 존재하는 종목을 본인 관심목록에 등록 (201). 거래 가능 여부(`tradable`)는 검사하지 않는다 — `education`/`favorite`(튜토리얼 전용, 인메모리)와 별개의 실제 서비스 기능. 이미 등록된 종목은 409 `DUPLICATE_RESOURCE`, 존재하지 않는 종목 ID는 404 `NOT_FOUND` | 023 WATCH-001 |
| GET | /api/watchlist-items?market= | watchlist | 인증 사용자 본인 관심목록을 등록 최신순(동시각 `id` 내림차순)으로 조회. `market`(`STOCK`\|`CRYPTO`) 선택, 생략 시 전체. 빈 목록도 200 | 023 WATCH-002 |
| DELETE | /api/watchlist-items/{instrumentId} | watchlist | 인증 사용자 본인 관심목록에서 종목 해제 (204, 본문 없음). 존재하지 않거나 타인 소유면 404 `WATCHLIST_ITEM_NOT_FOUND`(소유 여부 비노출) | 023 WATCH-003 |

## 투자 실습 계획 라우트 (아직 구현하지 않음)

`016` candidate 1~4와 표시 전용 합성 시세, `026`의 관찰·복기·진행 조회, `030`의 세션 생성·조회·next-tick·교육 지정가 4개, 그리고 `021`(Issue #348)의 일반 경로 `POST`·`DELETE /api/exit-plans`와 `GET /api/exit-plans?status=`는 위 실제 라우트다. 아래 표는 controller가 없는 계획 계약이며 블랙박스 QA 근거가 아니다.

표의 4개는 전부 OCO 계열 **3차 MVP 착수분**이다(2026-08-06 확정). OCO 진행조회는 holding 기반 조회와 URL·완료 key를 공유하지 않는다. `GET /api/exit-plans?status=`는 021이 목록·응답 계약을 전환해 위 실제 라우트로 옮겼다(경로 무관 공통 조회) — `POST`·`DELETE`·`GET` 셋 다 일반 경로가 production에 존재하고, 교육 경로(`intentionId` 지정)는 이 표의 나머지 항목과 함께 여전히 미착수다.

| Method | URL | 도메인 | 요약 | Spec |
|---|---|---|---|---|
| GET | /api/education/practice/oco?market= | education | `market=STOCK|CRYPTO` 필수. `exitPlanId` 기반 3차 OCO 실습 진행 상태 순수 조회. `INVESTMENT_OCO_PRACTICE_V1|COIN_OCO_PRACTICE_V1` 별도 완료 key 사용 | 016 candidate 12, Issue #308 |
| POST | /api/education/practice/oco/intentions | education | OCO 전용 사전 의도 기록. 종목 market에 따라 OCO 전용 progress를 생성·잠그고 intention에 내부 tutorial key를 귀속. holding 기반 intention과 상호 대체 불가 | 016 candidate 4 확장, Issue #308 |
| POST | /api/education/practice/observations | education | PENDING plan의 서버 현재가 관찰 기록 | 016 EDU-PRACTICE-012, candidate 13 |
| POST | /api/education/practice/reflections | education | 관찰 증거 이후 자유 복기 저장과 최초 불변 완료 | 016 EDU-PRACTICE-007·011·013, candidate 14 |

**투자 실습 관련 경로는 구현·계획을 막론하고 전부** 공개 경로에 추가하지 않으며 Access Bearer 인증을 요구한다 — 즐겨찾기 3개, 기존 사전 의도, 합성 시세, `026`의 관찰·복기 2개, 위 실제 라우트로 옮긴 일반 경로 OCO 생성·취소·목록 3개, 그리고 위 계획 4개 모두 해당한다. `POST /api/orders` 시장가 매수는 이미 제공 중인 기존 API를 그대로 사용하므로 계획 라우트에 중복 기재하지 않는다.

#199의 PRICE/PERCENT intention 확장은 아직 실제 라우트 계약이 아니다. 구현 시 기존 타입 생략+가격 요청을 PRICE로 호환하고, OCO 계획 라우트는 가격·rate를 다시 받지 않고 intention 정본에서 확정한다. 상세 계약은 `docs/specs/019-exit-price-policy`를 따른다.

## 2차 라우트 (구현 완료)

`docs/specs/012-ai-feedback`의 네 경로 — `GET /api/instruments/{instrumentId}/price-moves`(FEED-006)·`GET /api/instruments/{instrumentId}/news`(FEED-008)·`GET /api/market/briefing`(FEED-009)·`GET /api/ai/post-sell/{tradeId}`(FEED-007) — 는 **전부 구현되어 위 실제 라우트 목록에 반영했다. 계획 라우트로 남은 2차 경로는 없고 네 경로 모두 블랙박스 QA 근거다.** spec 012의 남은 이슈(`docs/specs/012-ai-feedback/plan.md` 7·8번 — 반사실 수익률·집단 비교, 코인 변동 감시)는 **이 네 경로의 응답 필드와 분기를 채우며 새 엔드포인트를 만들지 않는다** — 이 절을 계획 라우트 표로 되돌리지 않는다.

네 경로 모두 `SecurityConfig` 공개 목록에 추가하지 않는다 — `anyRequest().authenticated()`로 떨어져 Access Bearer 토큰을 요구한다.

**Notion 명세와의 차이 (팀 동기화 필요)**

| 항목 | Notion api 명세서 | 이 레포 | 사유 |
|---|---|---|---|
| Base URL | `/api/v1` | `/api` | 버저닝 미사용 (2026-07-23 확정, `docs/conventions.md`) |
| 매도 직후 피드백 | `GET /ai/post-sell/{id}` | `GET /api/ai/post-sell/{tradeId}` | Base URL 규칙만 적용, 경로는 동일 |
| `post-sell` 내용 | 계획 대비 실제 대조 (2단계) | 원장 수치 + 뉴스 변동 원인 | 계획 대조에는 목표가·손절가 등 구조화 필드가 필요하다. `007-journal`(다른 팀원 범위)은 JOUR-001(자유 텍스트 `content` 작성 API)만 구현됐고, 그 구조화 필드(`plan`·`planOutcome`)는 아직 없다. 생기면 같은 응답에 **추가**하면 되므로 계약이 깨지지 않는다 |
| AI 엔드포인트 수 | 6개 (`pre-order`·`post-sell`·`d7`·`weekly-report`·`basis-stats`·`similar`) | `post-sell` 1개만 | 나머지 5개는 2차 범위 밖 (`docs/specs/012-ai-feedback` 범위 제외) |
| 변동 원인 카드 | 없음 | `GET /api/instruments/{id}/price-moves` | 신규 — Notion 명세 DB에 행 추가 필요 |
| 종목 뉴스 목록·요약 | 없음 | `GET /api/instruments/{id}/news` | 신규 — Notion 1차 고도화 "뉴스 요약" 항목에 대응 |
| 개장 전 브리핑 | 없음 | `GET /api/market/briefing?market=` | 신규 — 변동 원인 카드는 가격이 움직인 **뒤**를 설명하므로 매매 판단에 쓸 수 없다. 브리핑이 그 공백을 채운다 |

## 시스템 엔드포인트

| Method | URL | 요약 |
|---|---|---|
| GET | /actuator/health | 헬스체크 |
| GET | /swagger-ui.html | API 문서 (springdoc) |
| GET | /v3/api-docs | OpenAPI JSON |

## 인증 규칙

Spring Security는 세션을 만들지 않는 Bearer 인증을 사용한다. 현재 `SecurityConfig`의 공개 경로는 다음과 같고, 여기에 없는 모든 요청은 기본적으로 인증이 필요하다.

| 구분 | Method | 경로 |
|---|---|---|
| 공개 | POST | `/api/auth/signup` |
| 공개 | POST | `/api/auth/login` |
| 공개 | POST | `/api/auth/refresh` |
| 공개 | POST | `/api/auth/email-verifications` |
| 공개 | POST | `/api/auth/email-verifications/confirm` |
| 공개 | POST | `/api/auth/password-resets` |
| 공개 | POST | `/api/auth/password-resets/confirm` |
| 조건부 공개 | GET | `/api/auth/oauth/*/authorize` — `purpose`가 없거나 공백이거나 대소문자 무관 `login`일 때만 공개. 그 밖의 값(`reauth` 포함)은 `anyRequest().authenticated()`로 떨어져 인증 필요 |
| 공개 | GET | `/api/auth/oauth/*/callback` |
| 공개 | POST | `/api/auth/oauth/login-exchange` |
| 공개 | POST | `/api/auth/oauth/reauth-exchange` |
| 공개 | GET | `/actuator/health` |
| 공개 | GET | `/swagger-ui.html`, `/swagger-ui/**` |
| 공개 | GET | `/v3/api-docs/**` |
| 보호 | POST | `/api/auth/logout` |
| 보호 | GET | `/api/auth/me` |
| 보호 | PATCH | `/api/auth/me/nickname` |
| 보호 | PATCH | `/api/auth/me/password` |
| 보호 | POST | `/api/auth/email-changes` |
| 보호 | POST | `/api/auth/email-changes/confirm` |
| 보호 | 모든 Method | 위 공개 목록을 제외한 모든 경로 (`anyRequest().authenticated()`) |

- 보호 경로는 `Authorization: Bearer <accessToken>`을 요구한다. 헤더가 없거나 Access Token이 만료·변조됐거나 Refresh Token이면 401 `UNAUTHORIZED`다.
- 인증된 사용자가 권한 없는 리소스에 접근하면 403 `FORBIDDEN`이다.
- 401·403은 모두 `{"error":{"code":"<UNAUTHORIZED|FORBIDDEN>","message":"<기본 메시지>","requestId":"<요청 ID>"}}` 공통 형식이며 `X-Request-Id` 응답 헤더를 포함한다.
- `/api/auth/me`는 `SecurityConfig` 공개 목록에 추가하지 않고 기본 보호(`anyRequest().authenticated()`)만으로 인증을 요구한다.
- `/api/dev/**`(local 전용 개발 엔드포인트)도 `SecurityConfig`에 항목을 추가하지 않는다 — `anyRequest().authenticated()`로 떨어져 평소 쓰는 Access Bearer 토큰으로 호출된다. 개발용 경로를 프로덕션 설정 파일에 `permitAll()`로 적어 두지 않는다.
- authorize의 조건부 공개는 `AndRequestMatcher`(경로+메서드 매처 + `purpose` 파라미터 람다)로 구현한다. 모르는 `purpose` 값은 공개 대상이 아니므로 기본적으로 인증을 요구한다(안전한 기본값). Spring Security 7에는 `AntPathRequestMatcher`가 없어 `PathPatternRequestMatcher`를 쓴다.
