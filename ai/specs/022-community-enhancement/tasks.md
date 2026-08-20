# Tasks: 커뮤니티 고도화 (종목 기준 분류 · 대댓글 · 사진 첨부)

각 항목 = 커밋 1개. plan.md의 설계를 그대로 따른다. 이슈별로 절을 나눈다(순서대로 구현, 뒤 항목이 앞 항목의 산출물에 의존).

## COM-004 게시물 종목 태그·필터 (이슈 #246)

- [x] 1. **엔티티·마이그레이션·`InstrumentService` 확장**
  `db/migration/V24__add_instrument_tag_to_community_posts.sql`(plan.md SQL 그대로: `community_posts.instrument_id` nullable FK + `idx_community_posts_instrument_created`). `CommunityPost`에 `instrument`(nullable `ManyToOne`) 필드 추가, `create`/`update` 시그니처에 `Instrument instrument` 파라미터 추가. `InstrumentService`에 `getTradableInstrumentEntity(Long instrumentId)` 신규(존재하지 않거나 `tradable=false`면 `BusinessException(VALIDATION_ERROR)`). `CommunityPostRepository.findById`의 `@EntityGraph`에 `"instrument"` 추가. 단위 테스트(`CommunityPost` 생성·수정 시 `instrument` 반영, `InstrumentServiceTest`에 신규 메서드 케이스: 존재/비활성/정상 3가지) + `@DataJpaTest`(마이그레이션 적용 후 FK·인덱스 확인, Testcontainers).

- [x] 2. **게시물 작성·수정 API에 종목 태그 반영**
  `CommunityPostCreateRequest`·`CommunityPostUpdateRequest`에 `instrumentId`(nullable `Long`) 필드 추가. `CommunityPostResponse`에 `instrumentId`·`instrumentSymbol`·`instrumentName`(모두 nullable) 추가, `from(CommunityPost)`에서 태그 없으면 세 필드 `null`. `CommunityPostService.createPost`/`updatePost`에 `instrumentId` 파라미터 추가 — 값이 있으면 `instrumentService.getTradableInstrumentEntity(instrumentId)` 호출 후 엔티티에 전달, 없으면 `instrument=null`. `CommunityPostController`가 `request.instrumentId()`를 그대로 전달하도록 수정. 서비스 단위 테스트(정상 태그 생성/수정, 미태그 하위 호환, 존재하지 않는/비활성 종목 태그 시 400 전파) + `@WebMvcTest`(요청 JSON `instrumentId` 포함/생략, 응답 JSON 태그 필드 계약, 400 오류 매핑).

- [x] 3. **목록 조회 `instrumentId` 필터**
  `CommunityPostRepositoryCustom`/`CommunityPostRepositoryImpl.findPostsOrderByCreatedAtDesc`을 `(Pageable, Long instrumentId)`로 변경 — `instrumentId != null`이면 `post.instrument.id.eq(instrumentId)` 조건 추가, `instrument`를 `leftJoin().fetchJoin()`으로 함께 로딩. `CommunityPostService.getPosts`에 `instrumentId` 파라미터 추가. `CommunityPostController.getPosts`에 `@RequestParam(required = false) Long instrumentId` 추가. `@DataJpaTest`(instrumentId 지정 시 해당 종목만, `null` 지정 시 전체, N+1 없는지) + `@WebMvcTest`(쿼리 파라미터 전달 검증).

- [x] 4. **통합 테스트**
  Testcontainers 기반 `@SpringBootTest`로 spec.md "완료 조건 COM-004" 4개 시나리오 구현: (a) 종목 태그 게시물 작성 → 단건 조회 시 태그 필드 포함, (b) `GET ?instrumentId=` 필터링(다른 종목·미태그 게시물 제외 확인), (c) 존재하지 않는/비활성 종목 태그 시도 400(개별 케이스 2개), (d) 미태그 게시물 하위 호환(기존 COM-001 시나리오) 회귀 — 모든 태그 필드 `null`.

- [x] 5. **문서 동기화 및 최종 빌드**
  `docs/api-routes.md`의 `POST/PATCH/GET /api/community/posts*` 행에 종목 태그·필터 반영 설명 갱신. `docs/api-contracts.md`의 `community` 절에 `instrumentId` 요청 필드·응답 태그 필드·400 `VALIDATION_ERROR`(존재하지 않는/비활성 종목) 계약 추가. `docs/prd.md` §3 구현 현황의 기존 "커뮤니티 고도화 — 종목 기준·대댓글·사진 첨부"(COM-004~006, 190행, 현재 "미착수") 행을 "일부 완료(COM-004)"로 갱신하고 근거에 이 PR 번호를 기입, COM-005·COM-006은 아직 범위 밖임을 명시(CLAUDE.md 규칙10). `docs/specs/022-community-enhancement/spec.md` "완료 조건 COM-004" 체크박스를 구현·테스트 통과 확인 후 `[x]`로 갱신. `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행).

## COM-005 대댓글 (이슈 #247)

- [x] 1. **엔티티·마이그레이션**
  `db/migration/V25__add_parent_comment_to_post_comments.sql`(plan.md SQL 그대로: `post_comments.parent_comment_id` nullable self-referencing FK + `ON DELETE CASCADE` + `idx_post_comments_parent`). `PostComment`에 `parentComment`(nullable self-referencing `ManyToOne`) 필드와 `isReply()` 추가, `create` 시그니처에 `PostComment parentComment` 파라미터 추가. 단위 테스트(`PostComment` 생성 시 `parentComment` 반영, `isReply()` 참/거짓) + `@DataJpaTest`(마이그레이션 적용 후 FK·인덱스 확인, 부모 댓글 삭제 시 자식이 CASCADE로 함께 삭제되는지 리포지토리 레벨 검증, Testcontainers).

- [x] 2. **댓글 작성 API에 대댓글 반영**
  `PostCommentCreateRequest`에 `parentCommentId`(nullable `Long`) 필드 추가. `PostCommentResponse`에 `parentCommentId`(nullable)·`replies`(`List<PostCommentResponse>`, 기본 빈 리스트) 필드 추가, 팩토리를 `from(PostComment comment)`(단건, `replies` 빈 리스트)와 `from(PostComment comment, List<PostCommentResponse> replies)`(중첩용) 둘로 분리. `PostCommentService.createComment`에 `parentCommentId` 파라미터 추가 — 값이 있으면 `postCommentRepository.findById(parentCommentId)`로 조회해 `post.id` 일치 확인(불일치·없음 시 404 `NOT_FOUND`), 조회된 부모가 이미 자식(`isReply()==true`)이면 400 `VALIDATION_ERROR`("대댓글에는 답글을 남길 수 없습니다."). `PostCommentController.createComment`가 `request.parentCommentId()`를 그대로 전달하도록 수정. 서비스 단위 테스트(부모 댓글 생성 하위 호환, 정상 대댓글 생성, 존재하지 않는/다른 게시물 소속 부모 404, 이미 자식인 댓글에 답글 시도 400) + `@WebMvcTest`(요청 JSON `parentCommentId` 포함/생략, 응답 JSON 필드 계약, 400/404 매핑).

- [x] 3. **댓글 목록 조회 중첩 응답**
  `PostCommentService.getComments`를 재구성 — 기존 `findAllByPostIdOrderByCreatedAtAscIdAsc`로 부모·자식 전체를 한 번에 조회한 뒤, `parentComment == null`인 댓글만 최상위로 추리고 나머지는 `parentComment.getId()` 기준으로 그룹핑해 각 최상위 댓글에 자식 리스트(오래된 순 유지)를 붙여 `PostCommentResponse.from(comment, replies)`로 변환. 서비스 단위 테스트(부모 여러 개·자식 여러 개 섞인 케이스에서 그룹핑·순서 검증, 자식 없는 부모는 `replies` 빈 리스트) + `@WebMvcTest`(응답 JSON 중첩 구조 계약).

- [x] 4. **통합 테스트**
  Testcontainers 기반 `@SpringBootTest`로 spec.md "완료 조건 COM-005" 3개 시나리오 구현: (a) 부모 댓글 작성 → 대댓글 작성 → 게시물 상세 댓글 목록 조회 시 부모 밑에 자식이 오래된 순으로 포함, (b) 대댓글에 다시 답글 시도 시 400 `VALIDATION_ERROR`, (c) 본인 대댓글만 삭제 가능·타인 대댓글 삭제 시도 403 `FORBIDDEN`. 추가로 (d) 부모 댓글 삭제 시 자식 대댓글도 함께 삭제되는지(`ON DELETE CASCADE`) 회귀 테스트.

- [x] 5. **문서 동기화 및 최종 빌드**
  `docs/api-routes.md`의 `POST/GET /api/community/posts/{postId}/comments` 행에 대댓글 작성·중첩 조회 설명 갱신(`DELETE /api/community/comments/{commentId}`는 URL 변경 없음이나 부모 삭제 시 자식 함께 삭제되는 동작을 설명에 추가). `docs/api-contracts.md`의 `community` 절에 `parentCommentId` 요청 필드·응답 `parentCommentId`/`replies` 필드·400 `VALIDATION_ERROR`(대댓글에 답글 시도)·404 `NOT_FOUND`(존재하지 않는/다른 게시물 소속 부모) 계약 추가. `docs/prd.md` §3 구현 현황의 "커뮤니티 고도화 — 종목 기준·대댓글·사진 첨부" 행(현재 "일부 완료(COM-004)")을 "일부 완료(COM-004~005)"로 갱신하고 근거에 이 PR 번호 추가, COM-006은 아직 범위 밖임을 명시(CLAUDE.md 규칙10). `docs/specs/022-community-enhancement/spec.md` "완료 조건 COM-005" 체크박스를 구현·테스트 통과 확인 후 `[x]`로 갱신. `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행).

## COM-006 사진 첨부 (이슈 #248)

- [x] 1. **마이그레이션·저장소 추상화·설정**
  `db/migration/V26__create_community_post_images.sql`(plan.md SQL 그대로: `community_post_images` 테이블, `post_id` nullable FK `ON DELETE CASCADE` + `UNIQUE(post_id)`, `uploader_id` FK). `community.storage` 패키지에 `FileStorageService` 인터페이스(`store`/`load`/`delete`)와 `LocalFileStorageService` 구현체 신규 작성. `application.yml`에 `spring.servlet.multipart.max-file-size: 5MB`·`max-request-size: 6MB`·`finplay.community.image-storage.base-directory`(기본값 `./data/community-images`) 추가. `GlobalExceptionHandler`에 `MaxUploadSizeExceededException` → 400 `VALIDATION_ERROR` 핸들러 추가. 단위 테스트: `LocalFileStorageServiceTest`(`@TempDir`로 저장·로드·삭제 왕복, 존재하지 않는 파일 삭제 시 예외 없음).

- [x] 2. **`CommunityPostImage` 엔티티·업로드 API**
  `CommunityPostImage` 엔티티(plan.md 그대로: `uploader`·`post`(nullable)·`storedFilename`·`originalFilename`·`contentType`·`sizeBytes`·`createdAt`, `isAssigned()`·`assignToPost()`), `CommunityPostImageRepository`(`JpaRepository`) 신규. `CommunityPostImageService.uploadImage(authenticatedUserId, MultipartFile)`(빈 파일·허용하지 않는 형식 400, `UUID` 파일명 생성 후 `fileStorageService.store` 호출, `CommunityPostImage.create` 저장) 신규. `CommunityPostImageResponse`(`imageId`·`imageUrl`) 신규. `CommunityPostImageController`에 `POST /api/community/posts/images`(업로드, 201)·`GET /api/community/posts/images/{imageId}/file`(다운로드, 존재하지 않으면 404) 신규. 단위 테스트(`CommunityPostImageServiceTest`: 정상 업로드, 허용하지 않는 형식 400, 빈 파일 400) + `@DataJpaTest`(마이그레이션 FK·`UNIQUE(post_id)` 제약 확인, Testcontainers) + `@WebMvcTest`(`MockMultipartFile`로 업로드 성공/형식 오류 응답 계약, 다운로드 404).

- [x] 3. **게시물 생성 API에 이미지 연결 반영**
  `CommunityPostImageService.resolveImageForPost(authenticatedUserId, imageId)`(존재하지 않음 404, 타인 소유 403, 이미 연결됨 400 3단계 검증) 신규. `CommunityPost`에 `@OneToOne(mappedBy = "post")` `image` 필드 추가. `CommunityPostCreateRequest`에 `imageId`(nullable) 필드 추가. `CommunityPostResponse`에 `imageId`·`imageUrl`(nullable) 필드 추가, `from(CommunityPost)`에서 이미지 없으면 둘 다 `null`. `CommunityPostService.createPost`에 `imageId` 파라미터 추가 — 값이 있으면 `resolveImageForPost` 호출 후 게시물 저장, 저장된 `post`에 `image.assignToPost(post)` 호출(같은 트랜잭션). `CommunityPostController.createPost`가 `request.imageId()`를 전달. `CommunityPostRepository.findById`의 `@EntityGraph`에 `"image"` 추가, `CommunityPostRepositoryImpl.findPostsOrderByCreatedAtDesc`에 `leftJoin(post.image).fetchJoin()` 추가. 단위 테스트(정상 이미지 연결 생성, 미첨부 하위 호환, 존재하지 않는/타인 소유/이미 연결된 imageId 각각 404·403·400 전파) + `@DataJpaTest`(목록·단건 조회 시 `image` fetch join, N+1 없는지) + `@WebMvcTest`(요청 JSON `imageId` 포함/생략, 응답 JSON `imageId`·`imageUrl` 계약, 404/403/400 매핑).

- [x] 4. **게시물 삭제 시 이미지 정리**
  `CommunityPostImageService.deleteImageIfPresent(CommunityPost post)` 신규 — 연결된 이미지가 있으면 `storedFilename` 확보 후 DB 행 삭제, 게시물 삭제 성공 뒤 `fileStorageService.delete(storedFilename)` 호출(실패 시 로그만). `CommunityPostService.deletePost`에서 댓글 삭제 다음, 게시물 삭제 전후로 이 메서드 호출(plan.md "삭제 처리" 순서 그대로). 단위 테스트(이미지 있는/없는 게시물 삭제 각각, 물리 파일 삭제 호출 검증 — mock) + `@DataJpaTest`(게시물 삭제 시 `community_post_images` 행이 `ON DELETE CASCADE`로 함께 삭제되는지).

- [x] 5. **통합 테스트**
  Testcontainers 기반 `@SpringBootTest`로 spec.md "완료 조건 COM-006" 4개 시나리오 구현: (a) 이미지 업로드 → 그 `imageId`로 게시물 작성 → 단건 조회 시 `imageUrl` 포함, 다운로드 엔드포인트로 바이트 확인, (b) 허용하지 않는 형식·5MB 초과 업로드 시도 각각 400, (c) 이미지 첨부 게시물 삭제 후 DB 행·물리 파일 모두 제거 확인, (d) 미첨부 게시물 하위 호환(기존 COM-001 시나리오) 회귀 — `imageId`·`imageUrl` 모두 `null`. 추가 회귀: 타인 소유 imageId로 게시물 생성 시도 403, 이미 사용된 imageId 재사용 시도 400.

- [x] 6. **문서 동기화 및 최종 빌드**
  `docs/api-routes.md`에 `POST /api/community/posts/images`·`GET /api/community/posts/images/{imageId}/file` 신규 행 추가, 기존 `POST/GET /api/community/posts*` 행에 `imageId`/`imageUrl` 반영 설명 갱신. `docs/api-contracts.md`의 `community` 절에 업로드·다운로드 엔드포인트 계약(요청 파트명·응답 필드·400/404 오류), 게시물 생성 `imageId` 필드·403/400 오류 계약 추가. `docs/prd.md` §3 구현 현황의 "커뮤니티 고도화 — 종목 기준·대댓글·사진 첨부" 행(현재 "일부 완료(COM-004~005)")을 "완료(COM-004~006)"로 갱신하고 근거에 이 PR 번호 추가. `docs/specs/022-community-enhancement/spec.md` "완료 조건 COM-006" 체크박스를 구현·테스트 통과 확인 후 `[x]`로 갱신. `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행).

## COM-005 부모 댓글 tombstone 전환 (이슈 #277)

plan.md의 "COM-005 부모 댓글 tombstone 전환 (이슈 #277)" 절 설계를 그대로 따른다. `V25`(CASCADE)는 이미 머지됐으므로 수정하지 않고 새 마이그레이션(`V31`)으로 대체한다(ADR-0004).

- [x] 1. **마이그레이션 + 엔티티 tombstone 필드/메서드**
  `db/migration/V31__change_post_comments_parent_fk_to_restrict.sql`(plan.md SQL 그대로: `post_comments.deleted_at DATETIME NULL` 컬럼 추가 + `fk_post_comments_parent`를 `DROP FOREIGN KEY` 후 `ON DELETE RESTRICT`로 재생성, `idx_post_comments_parent`는 변경 없이 유지). `PostComment`에 `deletedAt`(nullable `LocalDateTime`) 필드, `tombstone(LocalDateTime deletedAt)`, `isTombstoned()` 추가(기존 `content`·`author` 필드는 건드리지 않는다 — 원본 보존). 단위 테스트(`tombstone()` 호출 후 `isTombstoned()`가 `true`, 호출 전엔 `false`) + `@DataJpaTest`(Testcontainers, `V31` 적용 후 `deleted_at` 컬럼 확인, FK가 `RESTRICT`로 바뀐 뒤 자식이 있는 부모 댓글을 리포지토리 레벨에서 직접 `delete()` 시도하면 제약 위반 예외가 발생하는지, `postCommentRepository.deleteByPost_Id`가 부모+자식이 섞인 게시물에서도 여전히 성공하는지 — plan.md "주의" 캐벗의 회귀 확인).
  **회귀 재현·수정 (계획 대비 정정):** 위 `deleteByPost_Id` 회귀가 실제로 재현됐다(MySQL이 단일 벌크 DELETE 안에서 행 처리 순서를 보장하지 않아 부모가 자식보다 먼저 지워지면 RESTRICT 위반). 별도 이슈로 미루지 않고 이 항목 범위 안에서 고쳤다 — `CommunityPostService.deletePost`가 그대로 쓰는 메서드라 이슈 #277의 "기존 테스트가 모두 통과합니다" 조건에 직접 걸리기 때문이다. `PostCommentRepository.deleteByPost_Id`를 `deleteByPost_IdAndParentCommentIsNotNull`(자식, 먼저)·`deleteByPost_IdAndParentCommentIsNull`(부모, 나중) 두 개로 분리하고 `CommunityPostService.deletePost`가 같은 트랜잭션 안에서 이 순서로 호출하도록 수정. 리포지토리 레벨(정순 성공/역순 실패 대조)과 서비스 레벨(`InOrder` mock 검증) 양쪽에서 확인.

- [x] 2. **`deleteComment` tombstone 로직 + 응답 표시 분기**
  `PostCommentService.deleteComment`를 재작성 — 소유자 검증(403, 기존 로직 유지) 후 `comment.getParentComment() == null`이면 실제 삭제 대신 `comment.tombstone(LocalDateTime.now(clock))` 호출(hard delete 없음), `parentComment != null`이면 기존처럼 `postCommentRepository.delete(comment)`. `PostCommentResponse.from(PostComment, List<PostCommentResponse>)`에 tombstone 분기 추가 — `comment.isTombstoned()`이면 `content`를 "삭제된 댓글입니다", `authorNickname`을 "(삭제됨)"으로 치환(레코드 필드 추가 없음, `parentCommentId`·`replies`는 영향받지 않음). 단위 테스트(`PostCommentServiceTest`: 최상위 댓글 삭제 시 `tombstone()` 경로를 타고 `postCommentRepository.delete()` 미호출, 대댓글 삭제 시 기존처럼 `delete()` 호출, 타인 댓글·대댓글 삭제 시도 403 회귀) + `@WebMvcTest`(`PostCommentControllerTest`: tombstone된 부모 댓글 조회 응답의 `content`·`authorNickname` 치환 계약, `replies`·`parentCommentId` 불변 계약).

- [x] 3. **통합 테스트**
  Testcontainers 기반 `@SpringBootTest`로 plan.md "테스트 계획 정정" 통합 테스트 4개 시나리오 구현: (a) 부모 댓글 작성 → 대댓글 작성 → 부모 삭제 → 게시물 상세 댓글 목록 재조회 시 부모 행이 사라지지 않고 `content`·`authorNickname`이 치환된 채 자식은 원래 내용 그대로 `replies`에 남아있는지, (b) 자식 없는 부모 댓글 삭제도 동일하게 tombstone되는지(하드 삭제 아님), (c) 대댓글(자식) 자신을 삭제하면 기존처럼 하드 삭제되어 부모의 `replies`에서 사라지는지, (d) 타인의 부모 댓글·대댓글 삭제 시도는 여전히 403 `FORBIDDEN`(회귀 — tombstone 도입으로 소유권 규칙이 약해지지 않았음을 확인).
  **회귀 재현·수정 (계획 대비 정정):** V31의 FK RESTRICT 전환으로 `community` 도메인 테스트 트리 전반의 `@BeforeEach`/`@AfterEach`가 조건 없이 `delete from post_comments`를 실행하던 곳에서 같은 종류의 행 순서 위반이 재현됐다(테스트 클래스 실행 순서에 따라 최대 33건까지 결정적으로 실패). 전수 조사(`grep -rn "delete from post_comments" src/test/java`)로 영향받는 파일 13개(신규 통합 테스트 1개 제외 12개 기존 파일)를 모두 "자식(parent_comment_id IS NOT NULL) 먼저 삭제 → 무조건 삭제" 2단계로 통일했다. tester가 `--rerun`으로 연속 2회 독립 재실행해 `com.finplay.api.community.*` 256개 전부 재현성 있게 통과함을 확인.

- [x] 4. **문서 동기화 및 최종 빌드**
  `docs/api-routes.md`의 `DELETE /api/community/comments/{commentId}` 행("부모 댓글을 삭제하면 그 자식 대댓글도 `ON DELETE CASCADE`로 함께 삭제된다") 설명을 tombstone 동작("부모 댓글을 삭제하면 실제로 삭제되지 않고 내용·작성자 표시가 치환되며, 자식 대댓글은 그대로 보존된다")으로 교체. `docs/api-contracts.md`의 `community` 절 중 같은 엔드포인트의 CASCADE 서술("부모 댓글(대댓글을 가진 댓글) 삭제 시 자식 대댓글도 DB `ON DELETE CASCADE`로 함께 삭제된다")을 tombstone 계약(치환되는 `content`·`authorNickname` 값, 자식 보존)으로 교체. `docs/prd.md` §3은 갱신 대상이 아니다(COM-005가 제공하는 기능 자체는 그대로이고 삭제 시 내부 동작만 바뀜 — CLAUDE.md 규칙10 "갱신 비대상: 버그 수정"). `docs/specs/022-community-enhancement/spec.md` "완료 조건 COM-005" 체크박스는 이미 `[x]`이므로 변경하지 않는다(요구사항 자체가 아니라 정책 정정이므로). `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행).

- [x] 5. **tombstone된 댓글에 답글 금지 (PR #331 리뷰 참고 사항 #2)**
  plan.md의 "tombstone된 댓글에 답글 금지" 절 설계를 그대로 따른다. `PostCommentService.createComment`의 `parentCommentId` 검증에 세 번째 단계 추가 — 조회된 부모가 `isTombstoned()==true`이면 400 `VALIDATION_ERROR`("삭제된 댓글에는 답글을 남길 수 없습니다."). 단위 테스트(`PostCommentServiceTest`: tombstone된 부모에 답글 시도 400, 정상 부모에는 여전히 허용되는 대조 케이스) + `@WebMvcTest`(`PostCommentControllerTest`: 400 응답 계약) + 통합 테스트(부모 tombstone 후 그 부모로 대댓글 작성 시도 → 400, 재조회로 응답에 새 대댓글이 반영되지 않았는지 확인). `docs/api-contracts.md`의 `POST /api/community/posts/{postId}/comments` 400 오류 사유에 이 케이스 추가. `./gradlew build` 전체 통과 확인.

## COM-006 후속: 이미지 저장소를 S3로 전환 (이슈 #330)

`FileStorageService` 인터페이스는 바꾸지 않는다. 구현체 추가와 프로파일 분기만으로 끝내는 것이 목표다(plan.md "COM-006 후속: 이미지 저장소를 S3로 전환" 절 참고). PR #329(이슈 #326, ADR-0020)는 이미 `dev`에 머지됐고 이 브랜치는 그 위로 리베이스된 상태다.

- [x] 1. **AWS SDK 의존성·`S3FileStorageService`·프로파일 분기·설정**
  `build.gradle`에 `software.amazon.awssdk:bom` platform + `software.amazon.awssdk:s3` 추가(Boot 4.1/Java17과 충돌 없는 최신 안정 BOM 버전을 확인해 고정). `community.storage` 패키지에 `S3FileStorageService`(`@Profile("prod")`) 신규 작성 — `store`/`load`/`delete`는 plan.md 서술대로 `PutObjectRequest`/`GetObjectRequest`/`DeleteObjectRequest`로 구현하고, 각각 `LocalFileStorageService`와 동일한 예외 계약(`store` 실패 시 `BusinessException(INTERNAL_ERROR)`, `load` 대상 없음 시 `BusinessException(NOT_FOUND)`, `delete`는 best-effort 로그만) 유지. 기존 `LocalFileStorageService`에 `@Profile("!prod")`를 추가한다(현재 무조건 등록 상태에서 로컬/테스트 전용으로 좁힌다 — 두 구현체가 동시에 빈으로 등록되면 `prod` 기동이 실패하므로 필수 변경). `application-prod.yml`에 `finplay.community.image-storage.s3.bucket: ${COMMUNITY_S3_BUCKET}`(기본값 없음, fail-fast) 추가, `.env.example`에 `COMMUNITY_S3_BUCKET=`을 "배포(prod 프로필)에서만 필요" 절에 추가(버킷은 AWS 콘솔에서 사전 생성 필요임을 주석으로 명시). `S3Client`는 자격 증명·리전 모두 SDK 기본 체인(EC2 IAM 인스턴스 프로파일, `AWS_REGION`)에 맡긴다 — 정적 액세스 키·커스텀 리전 설정 키를 새로 추가하지 않는다. 단위 테스트: `S3FileStorageServiceTest`(Mockito `S3Client` mock) — 정상 store/load/delete, S3 예외 발생 시 예외 변환 각각 확인. 기존 `LocalFileStorageServiceTest`가 `@Profile("!prod")` 추가 후에도 그대로 통과하는지 확인(단위 테스트는 빈을 직접 생성하므로 프로파일 애너테이션 영향이 없을 것으로 예상되나 실제로 확인).

- [x] 2. **배포 문서·이관 절차 문서화**
  `deploy/README.md`에 S3 버킷 생성(퍼블릭 액세스 차단 유지)·IAM 역할(대상 버킷 한정 `s3:GetObject`/`PutObject`/`DeleteObject` 최소 권한)·EC2 인스턴스 프로파일 연결 체크리스트를 ADR-0020의 RDS·ElastiCache 콘솔 설정 안내와 같은 형식으로 추가한다. 기존 로컬 업로드 파일 이관 절차(`aws s3 sync` 1회성 스크립트, 블루-그린 전환 전 실행)를 같은 문서에 남긴다 — 이관 대상 데이터가 실제로 있는지 먼저 EC2에서 확인하고, 소수/테스트 데이터 수준이면 이관을 생략하고 재업로드 안내로 대체한다는 판단 기준도 함께 적는다(plan.md "기존 로컬 데이터 이관 방안" 절 그대로). `compose.deploy.yaml`에 이미지 볼륨을 새로 추가하지 않는다(ADR-0020 §결정 3 유지 확인, 코드 변경 없음 — 이 항목은 "추가하지 않았음"을 리뷰에서 확인하기 위한 체크 항목).

- [x] 3. **문서 동기화 및 최종 빌드**
  `docs/specs/022-community-enhancement/plan.md`의 "Decision Gate 확정" 절 — "`S3FileStorageService` 등은 실제로 필요해지는 시점(운영 배포 논의)에 새로 추가한다" 문장 아래(PR #329가 이미 추가했을 화살표 각주가 있다면 그 바로 아래)에 "구현 완료(이슈 #330, 이 PR)"를 표기하는 각주를 추가한다. `docs/prd.md` §3 "커뮤니티 고도화" 행 근거 칸에 이슈 #330/이 PR 번호를 추가한다(기능 제공 범위는 그대로이므로 판정 문구 "완료(COM-004~006)" 자체는 유지 — CLAUDE.md 규칙10 "갱신 비대상"에 해당하는지는 실제 diff를 보고 최종 판단한다). `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행) — 특히 `prod` 프로필로 애플리케이션 컨텍스트를 띄우는 테스트가 있다면 `S3Client`/버킷 설정 부재로 실패하지 않는지 확인한다.
