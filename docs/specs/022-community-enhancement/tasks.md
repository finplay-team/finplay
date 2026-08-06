# Tasks: 커뮤니티 고도화 (종목 기준 분류 · 대댓글 · 사진 첨부)

각 항목 = 커밋 1개. plan.md의 설계를 그대로 따른다. 이슈별로 절을 나눈다(순서대로 구현, 뒤 항목이 앞 항목의 산출물에 의존).

## COM-004 게시물 종목 태그·필터 (이슈 #246)

- [x] 1. **엔티티·마이그레이션·`InstrumentService` 확장**
  `db/migration/V24__add_instrument_tag_to_community_posts.sql`(plan.md SQL 그대로: `community_posts.instrument_id` nullable FK + `idx_community_posts_instrument_created`). `CommunityPost`에 `instrument`(nullable `ManyToOne`) 필드 추가, `create`/`update` 시그니처에 `Instrument instrument` 파라미터 추가. `InstrumentService`에 `getTradableInstrumentEntity(Long instrumentId)` 신규(존재하지 않거나 `tradable=false`면 `BusinessException(VALIDATION_ERROR)`). `CommunityPostRepository.findById`의 `@EntityGraph`에 `"instrument"` 추가. 단위 테스트(`CommunityPost` 생성·수정 시 `instrument` 반영, `InstrumentServiceTest`에 신규 메서드 케이스: 존재/비활성/정상 3가지) + `@DataJpaTest`(마이그레이션 적용 후 FK·인덱스 확인, Testcontainers).

- [x] 2. **게시물 작성·수정 API에 종목 태그 반영**
  `CommunityPostCreateRequest`·`CommunityPostUpdateRequest`에 `instrumentId`(nullable `Long`) 필드 추가. `CommunityPostResponse`에 `instrumentId`·`instrumentSymbol`·`instrumentName`(모두 nullable) 추가, `from(CommunityPost)`에서 태그 없으면 세 필드 `null`. `CommunityPostService.createPost`/`updatePost`에 `instrumentId` 파라미터 추가 — 값이 있으면 `instrumentService.getTradableInstrumentEntity(instrumentId)` 호출 후 엔티티에 전달, 없으면 `instrument=null`. `CommunityPostController`가 `request.instrumentId()`를 그대로 전달하도록 수정. 서비스 단위 테스트(정상 태그 생성/수정, 미태그 하위 호환, 존재하지 않는/비활성 종목 태그 시 400 전파) + `@WebMvcTest`(요청 JSON `instrumentId` 포함/생략, 응답 JSON 태그 필드 계약, 400 오류 매핑).

- [x] 3. **목록 조회 `instrumentId` 필터**
  `CommunityPostRepositoryCustom`/`CommunityPostRepositoryImpl.findPostsOrderByCreatedAtDesc`을 `(Pageable, Long instrumentId)`로 변경 — `instrumentId != null`이면 `post.instrument.id.eq(instrumentId)` 조건 추가, `instrument`를 `leftJoin().fetchJoin()`으로 함께 로딩. `CommunityPostService.getPosts`에 `instrumentId` 파라미터 추가. `CommunityPostController.getPosts`에 `@RequestParam(required = false) Long instrumentId` 추가. `@DataJpaTest`(instrumentId 지정 시 해당 종목만, `null` 지정 시 전체, N+1 없는지) + `@WebMvcTest`(쿼리 파라미터 전달 검증).

- [ ] 4. **통합 테스트**
  Testcontainers 기반 `@SpringBootTest`로 spec.md "완료 조건 COM-004" 4개 시나리오 구현: (a) 종목 태그 게시물 작성 → 단건 조회 시 태그 필드 포함, (b) `GET ?instrumentId=` 필터링(다른 종목·미태그 게시물 제외 확인), (c) 존재하지 않는/비활성 종목 태그 시도 400(개별 케이스 2개), (d) 미태그 게시물 하위 호환(기존 COM-001 시나리오) 회귀 — 모든 태그 필드 `null`.

- [ ] 5. **문서 동기화 및 최종 빌드**
  `docs/api-routes.md`의 `POST/PATCH/GET /api/community/posts*` 행에 종목 태그·필터 반영 설명 갱신. `docs/api-contracts.md`의 `community` 절에 `instrumentId` 요청 필드·응답 태그 필드·400 `VALIDATION_ERROR`(존재하지 않는/비활성 종목) 계약 추가. `docs/prd.md` §3 구현 현황의 기존 "커뮤니티 고도화 — 종목 기준·대댓글·사진 첨부"(COM-004~006, 190행, 현재 "미착수") 행을 "일부 완료(COM-004)"로 갱신하고 근거에 이 PR 번호를 기입, COM-005·COM-006은 아직 범위 밖임을 명시(CLAUDE.md 규칙10). `docs/specs/022-community-enhancement/spec.md` "완료 조건 COM-004" 체크박스를 구현·테스트 통과 확인 후 `[x]`로 갱신. `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행).

## COM-005 대댓글 (이슈 #247) — 다음 이슈 착수 시 작성

## COM-006 사진 첨부 (이슈 #248) — 다음 이슈 착수 시 작성
