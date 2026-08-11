# Run Log: 022-community-enhancement

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava`, `compileTestJava`, `test --tests CommunityPostTest --tests InstrumentServiceTest` | plan.md "데이터 모델"·"패키지·클래스 설계", ADR-0002, ADR-0004 |
| - | implementer | `./gradlew.bat compileJava` | plan.md "API 설계"·"패키지·클래스 설계"(항목 2: Create/Update Request·Response·Service·Controller) |
| - | implementer | `./gradlew.bat compileJava --rerun-tasks` | plan.md "패키지·클래스 설계"(항목 3: `findPostsOrderByCreatedAtDesc(Pageable, Long)` instrumentId 필터) |

## 모니터링 (사람용 요약)
- COM-004 항목1: `V24` 마이그레이션·`CommunityPost.instrument`·`InstrumentService.getTradableInstrumentEntity` 추가, compileJava/compileTestJava 통과, 신규 단위 테스트 2건 통과.
- COM-004 항목2: Create/Update 요청 DTO·응답 DTO에 종목 태그 필드 추가, `CommunityPostService`가 `instrumentService.getTradableInstrumentEntity` 호출해 컨트롤러까지 연결, compileJava 통과(테스트는 tester 담당).
- COM-004 항목3: 목록 조회에 `instrumentId` 필터 추가 — 리포지토리(QueryDSL `leftJoin().fetchJoin()` + 동적 `BooleanExpression`)·서비스·컨트롤러(`@RequestParam(required = false)`) 관통, compileJava 통과(기존 리포지토리·서비스·컨트롤러 테스트는 옛 시그니처라 컴파일 실패 상태 — tester가 갱신 예정).
| - | reviewer(리뷰) | `git diff dev...HEAD`(전체), conventions.md·ADR-0002·0003·0004·api-routes.md·api-contracts.md 대조 | spec.md COM-004 완료조건, plan.md 설계 |

## 모니터링 (사람용 요약)
- 리뷰 완료(COM-004, PR #246 브랜치): 차단 0건, 권장 0건. 레이어·N+1·docs 동기화(api-routes/api-contracts/prd) 모두 일치, 테스트 4계층(단위·DataJpaTest·WebMvcTest·통합) 충실. 머지 가능.

## AI 로그 (에이전트 참조용, PR #256 리뷰)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | reviewer(리뷰) | `git diff dev...HEAD` (PR #256) | CLAUDE.md 규칙 7, api-contracts.md 4개 엔드포인트 응답 계약 대조 |
| - | implementer(PR #256 리뷰 차단·권장 반영) | `.\gradlew.bat test --tests "com.finplay.api.community.*"` + `spotlessApply` + `build` | 16:24 리뷰 차단 1건·권장 1건 |

## 모니터링 (사람용 요약)
- PR #256 리뷰 완료: 물어본 두 가지(`getTradableInstrumentEntity` 404/400 분리, PATCH 전체 교체 방식)는 문제없음 확인. 차단 1건(`GET /api/community/posts/{postId}` 단건 조회 계약만 응답 필드 갱신에서 빠짐 — 코드·통합 테스트는 9필드인데 문서는 6필드), 권장 1건(PATCH로 태그를 `null`로 보내 해제하는 동작에 테스트 없음).
- 반영: `docs/api-contracts.md:277`에 3필드 추가 + 태그 없으면 `null`이라는 문구 + Spec 칸에 `022 COM-004`·`Issue #246` 추가. `CommunityPostServiceTest`에 `updatePostDetachesInstrumentWhenInstrumentIdIsNullOnAlreadyTaggedPost` 신규 추가(이미 태그된 게시물 준비 → `instrumentId=null`로 update → `post.getInstrument()`가 `null`이고 `instrumentService` 미호출 확인). 커뮤니티 테스트 전체 통과, `./gradlew build` 전체 재검증.

## AI 로그 (에이전트 참조용, COM-005 항목1)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava`, `compileTestJava` | plan.md "COM-005 대댓글" 데이터 모델·패키지·클래스 설계, ADR-0004 |

## 모니터링 (사람용 요약)
- COM-005 항목1: `V25` 마이그레이션(`post_comments.parent_comment_id` self-referencing FK, `ON DELETE CASCADE`, `idx_post_comments_parent`), `PostComment.parentComment`·`isReply()`·`create` 시그니처 변경 추가. 기존 `PostComment.create` 호출부(서비스 1곳 + 테스트 5개 파일)를 `null` 인자로 기계적 수정, compileJava/compileTestJava 통과(테스트 작성은 tester 담당).

## AI 로그 (에이전트 참조용, COM-005 항목2)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `$env:JAVA_HOME=...; .\gradlew.bat compileJava` | plan.md "COM-005 대댓글" API 설계·`createComment` 대댓글 검증 로직·패키지·클래스 설계(항목 2) |

## 모니터링 (사람용 요약)
- COM-005 항목2: `PostCommentCreateRequest.parentCommentId` 추가, `PostCommentResponse`에 `parentCommentId`·`replies`(`List.copyOf` 방어적 복사) 추가하고 `from(comment)`/`from(comment, replies)` 두 팩토리로 분리. `PostCommentService.createComment`에 `parentCommentId` 검증(불일치/없음 404, 이미 자식이면 400) 추가, 컨트롤러가 `request.parentCommentId()` 전달. compileJava 통과(테스트는 tester 담당, `getComments` 중첩 응답은 항목3 범위).

## AI 로그 (에이전트 참조용, COM-005 항목3)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava` | plan.md "COM-005 대댓글" 패키지·클래스 설계(`PostCommentService.getComments` 재구성) |

## 모니터링 (사람용 요약)
- COM-005 항목3: `getComments`가 기존 `findAllByPostIdOrderByCreatedAtAscIdAsc` 결과를 `parentComment == null` 최상위/자식으로 그룹핑(`parentComment.getId()` 기준)해 `PostCommentResponse.from(comment, replies)`로 중첩 변환하도록 재구성. 정렬은 원 쿼리 순서(`createdAt asc, id asc`) 보존, 추가 정렬 없음. compileJava 통과(테스트는 tester 담당).

## AI 로그 (에이전트 참조용, COM-005 항목4~5)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | tester | `.\gradlew.bat test --tests "com.finplay.api.community.PostCommentReplyIntegrationTest"` | spec.md "완료 조건 COM-005" 3개 시나리오 + CASCADE 회귀 |
| - | planner(동기화) | `.\gradlew.bat build`(JAVA_HOME=ms-17.0.20) | CLAUDE.md 규칙 7·10, api-routes.md·api-contracts.md·prd.md §3 동기화 |
| - | reviewer(리뷰) | `git diff dev...HEAD`(전체), conventions.md·ADR-0002·0003·0004 대조 | spec.md COM-005 완료조건, plan.md 설계 |

## 모니터링 (사람용 요약)
- COM-005 항목4: `PostCommentReplyIntegrationTest` 신규(중첩 조회, 대댓글에 재답글 400, 타인 삭제 403, 부모 삭제 CASCADE 회귀) 4/4 통과. 새 시드 데이터 없어 COM-004 때 발생했던 공유 DB 오염 재현 없음.
- COM-005 항목5: `api-routes.md`·`api-contracts.md`에 `parentCommentId`/`replies`/400/404 계약 반영, `prd.md` §3을 "일부 완료(COM-004~005)"로 갱신(근거 이슈 #247), spec.md COM-005 완료 조건 `[x]`. `./gradlew build` 전체 통과(SHA `af8cd55cbabf4378538825662967e3d2b5822bf7`).
- 리뷰 완료(COM-005): 차단 0건, 권장 2건(`PostComment.java` 첫 줄 주석이 "평면 댓글"로 남아있던 것 — 수정함, run-log에 COM-005 항목4~5 기록 누락 — 이 항목으로 보완). 레이어·N+1·CASCADE 조합·docs 동기화·테스트 4계층 모두 문제없음 확인. 머지 가능.

## AI 로그 (에이전트 참조용, PR #260 리뷰)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | reviewer(리뷰) | `git diff dev...HEAD` (PR #260) | `PostCommentRepository.deleteByPost_Id` 파생 delete 쿼리 동작, V25 ON DELETE CASCADE |
| - | implementer(PR #260 리뷰 차단 반영) | `.\gradlew.bat test --tests "com.finplay.api.community.*"` + `spotlessApply` + `build` | 리뷰 차단 1건 |

## 모니터링 (사람용 요약)
- PR #260 리뷰 완료: 리뷰어에게 물어본 두 질문 모두 문제없음 확인(400 vs 404 판정, ON DELETE CASCADE 설계). 차단 1건 — `deleteByPost_Id`가 파생 delete라 부모·자식 댓글을 개별 DELETE로 처리하는데 V25의 ON DELETE CASCADE와 겹치면 자식이 이미 사라진 뒤 재삭제를 시도해 예외가 날 수 있다는 지적. 그 조합(부모+대댓글이 있는 게시물 삭제)을 검증하는 테스트가 없었다.
- 반영: `CommunityPostDeleteIntegrationTest`에 `ownerDeleteWithParentCommentAndReplyReturns204AndRemovesPostParentAndChildWithoutStaleStateException` 신규 추가 — 수정 전 코드로 먼저 실행해 실제로는 예외가 나지 않음을 확인했다(`PostComment`에 `@Version`이 없어 Hibernate가 delete 영향행수를 검사하지 않아 0행 DELETE가 조용히 무시됨). 다만 이는 우연한 안전이라 향후 낙관적 락 추가 시 재발할 수 있고, 게시물당 댓글 수만큼 개별 DELETE가 나가는 비효율도 있어 리뷰 제안대로 `deleteByPost_Id`를 `@Modifying @Query` 벌크 삭제로 전환했다. 커뮤니티 테스트 전체·`./gradlew build` 전체 재검증 통과.

## AI 로그 (에이전트 참조용, COM-006 항목1)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `$env:JAVA_HOME=...; .\gradlew.bat spotlessApply compileJava compileTestJava`, `test --tests LocalFileStorageServiceTest` | plan.md "COM-006 사진 첨부" Decision Gate 확정·`FileStorageService`/`LocalFileStorageService` 설계, ADR-0004 |

## 모니터링 (사람용 요약)
- COM-006 항목1: `V26` 마이그레이션(`community_post_images`, `post_id` nullable FK `ON DELETE CASCADE` + `UNIQUE`), `community.storage` 패키지에 `FileStorageService`/`LocalFileStorageService`(로컬 파일시스템, `@Value` 생성자 수동 작성) 신규, `application.yml`에 multipart 크기 제한·`finplay.community.image-storage.base-directory` 추가, `GlobalExceptionHandler`에 `MaxUploadSizeExceededException` → 400 `VALIDATION_ERROR` 핸들러 추가. `LocalFileStorageServiceTest`(`@TempDir`) 3건 통과. compileJava/compileTestJava 통과(엔티티·업로드 API는 항목2 범위).

## AI 로그 (에이전트 참조용, COM-006 항목2)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `$env:JAVA_HOME=...; .\gradlew.bat compileJava` | plan.md "COM-006 사진 첨부" 데이터 모델(`CommunityPostImage` 엔티티)·패키지·클래스 설계(항목 2: 업로드 API) |

## 모니터링 (사람용 요약)
- COM-006 항목2: `CommunityPostImage` 엔티티(`isAssigned()`·`assignToPost()`)·`CommunityPostImageRepository`(`JpaRepository`) 신규. `CommunityPostImageService.uploadImage`(빈 파일·허용하지 않는 형식 400, `UUID`+원본 확장자 파일명 생성 후 `fileStorageService.store` 호출)와 `loadImageFile`(존재하지 않으면 404, `CommunityPostImageFile`(resource+contentType) 반환) 신규. `CommunityPostImageResponse`(`imageId`·`imageUrl`) 신규. `CommunityPostImageController`에 `POST /api/community/posts/images`(201)·`GET /api/community/posts/images/{imageId}/file` 신규. compileJava 통과(단위·슬라이스 테스트는 tester 담당, `resolveImageForPost`는 항목3 범위).

## AI 로그 (에이전트 참조용, COM-006 항목3)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `JAVA_HOME=/c/Users/pmsal/.jdks/ms-17.0.20 ./gradlew.bat compileJava --console=plain` | plan.md "COM-006 사진 첨부" 패키지·클래스 설계(항목 3: 게시물 생성 API에 이미지 연결) |

## 모니터링 (사람용 요약)
- COM-006 항목3: `CommunityPostImageService.resolveImageForPost`(존재하지 않음 404, 타인 소유 403, 이미 연결됨 400 순서 검증) 신규. `CommunityPost`에 `@OneToOne(mappedBy = "post")` `image` 필드, `CommunityPostRepository.findById` `@EntityGraph`에 `"image"`, `CommunityPostRepositoryImpl`에 `leftJoin(post.image).fetchJoin()` 추가. `CommunityPostCreateRequest.imageId`·`CommunityPostResponse.imageId`/`imageUrl`(`CommunityPostImageResponse.toImageUrl` 재사용) 추가. `CommunityPostService.createPost`가 게시물 저장 후 같은 트랜잭션에서 `image.assignToPost(savedPost)` 호출, 컨트롤러가 `request.imageId()` 전달. `docs/api-routes.md`·`docs/api-contracts.md`의 기존 `POST/GET/PATCH /api/community/posts*` 행에 `imageId`/`imageUrl` 계약 반영(업로드·다운로드 엔드포인트 신규 행과 PRD §3 갱신은 tasks.md 항목6 범위로 남김). compileJava 통과(테스트는 tester 담당).

## AI 로그 (에이전트 참조용, COM-006 항목3 버그 수정)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `JAVA_HOME=... ./gradlew.bat compileJava compileTestJava`, `test --tests CommunityPostServiceTest` | tester가 작성한 `CommunityPostImageIntegrationTest`에서 발견한 회귀(생성 응답 `imageId`/`imageUrl` null) |

## 모니터링 (사람용 요약)
- COM-006 항목3 버그 수정: `image.assignToPost(savedPost)`는 소유 측(FK)만 갱신하고, 이미 메모리에 있는 `savedPost.image`(역방향, `mappedBy="post"`)는 Hibernate가 같은 영속성 컨텍스트 안에서 자동 동기화해주지 않아 생성 응답의 `imageId`/`imageUrl`이 `null`로 나가는 버그가 있었다(DB 재조회 시엔 정상). `CommunityPost.attachImage(image)` 신규(역방향 필드 명시적 동기화)를 추가해 `createPost`가 `assignToPost` 직후 `savedPost.attachImage(image)`도 호출하도록 수정. `CommunityPostServiceTest.createPostAssignsImageToSavedPostWhenImageIdProvided`가 mock의 `assignToPost` 호출 여부만 검증해 이 문제를 못 잡았던 것도 보강 — `image.getId()`를 스텁하고 반환된 `CommunityPostResponse.imageId()`/`imageUrl()`이 실제 값을 갖는지 단정 추가. compileJava/compileTestJava 통과, 대상 테스트 통과.

## AI 로그 (에이전트 참조용, COM-006 항목4)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `$env:JAVA_HOME="C:\Users\pmsal\.jdks\ms-17.0.20"; .\gradlew.bat compileJava` | plan.md "삭제 처리" 순서(댓글 삭제 → 이미지 정리 → 게시물 삭제), tasks.md COM-006 항목4 |

## 모니터링 (사람용 요약)
- COM-006 항목4: `CommunityPostImageService.deleteImageIfPresent(CommunityPost post)` 신규 — 연결 이미지가 있으면 `storedFilename` 확보 후 DB 행 삭제, 이어서 `fileStorageService.delete(storedFilename)` 호출(구현체가 이미 IOException을 잡아 로그만 남기므로 여기서 추가 try-catch 없음). `CommunityPostService.deletePost`에서 `postCommentRepository.deleteByPost_Id` 다음·`communityPostRepository.delete(post)` 이전에 호출하도록 연결. compileJava 통과(단위·`@DataJpaTest`는 tester 담당).

## AI 로그 (에이전트 참조용, PR #269 리뷰)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | reviewer(리뷰) | `git diff dev...HEAD` (PR #269) | 파일 경로 조립, 다운로드 접근 범위, plan.md "경로 조작 방지" 서술과 실제 코드 대조 |
| - | implementer(PR #269 리뷰 차단 2건·권장 2건 반영) | `.\gradlew.bat compileJava compileTestJava` + `test --tests "com.finplay.api.community.*"` + `spotlessApply` + `build` | 리뷰 차단 2건·권장 2건 |

## 모니터링 (사람용 요약)
- PR #269 리뷰 완료: 리뷰어에게 물어본 두 질문(저장 구조·검증 순서) 모두 문제없음 확인. 차단 2건 — ① `resolveExtension`이 클라이언트 원본 파일명에서 마지막 점 이후 전부를 확장자로 써 저장소가 그 값을 경계 검사 없이 경로로 조립함(`a.b/c` 같은 입력이 400이 아니라 500으로 새고, plan.md의 "경로 조작 방지" 서술이 실제 코드와 불일치), ② `loadImageFile`이 소유권·연결 여부를 전혀 검사하지 않아 아직 게시되지 않은(post_id IS NULL) 이미지도 `imageId`만 알면 누구나 다운로드 가능.
- 반영: (1) 확장자를 원본 파일명이 아니라 이미 검증한 `contentType`에서 매핑으로 결정하도록 변경, `LocalFileStorageService`에 `normalize()` 기반 경계 검사를 store/load/delete 3곳 모두에 추가(저장 키 생성 규칙이 바뀌어도 저장소가 스스로를 지킴). (2) `loadImageFile(authenticatedUserId, imageId)`로 시그니처 변경 — `isAssigned()`가 true면 누구나, false면 업로더 본인만 허용하고 그 외는 404로 존재를 숨김. 컨트롤러에 `@AuthenticationPrincipal` 추가.
- 권장 2건도 함께 반영: 다운로드 응답에 `X-Content-Type-Options: nosniff` 헤더 추가(매직 바이트 미검사 트레이드오프의 짝). 물리 파일 삭제를 `CommunityPostImageDeletedEvent` + `@TransactionalEventListener(AFTER_COMMIT)`로 전환(`RankingEventListener` 선례) — 게시물 삭제 트랜잭션이 롤백되면 DB 행은 살아있는데 파일만 사라지는 상태를 막는다.
- `LocalFileStorageServiceTest`에 store/load/delete 3개 경계 탈출 회귀 테스트, `CommunityPostImageServiceTest`에 악의적 파일명 확장자 무시 회귀 테스트·미할당 이미지 접근 제어 3종(업로더 본인 허용/타인 거부/미존재)을 추가. `docs/api-contracts.md`·`plan.md`의 관련 서술도 실제 동작에 맞게 정정. `./gradlew build` 전체(테스트·jacoco·spotbugs·spotless 포함) 통과.

## AI 로그 (에이전트 참조용, COM-005 tombstone 항목1)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `./gradlew compileJava` | plan.md "COM-005 부모 댓글 tombstone 전환" 데이터 모델·엔티티 변경 정정, ADR-0004 |

## 모니터링 (사람용 요약)
- COM-005 tombstone 항목1: `V31` 마이그레이션(`post_comments.deleted_at DATETIME NULL` 추가 + `fk_post_comments_parent`를 `DROP`·재생성해 `ON DELETE RESTRICT`로 전환, 인덱스는 유지) 신규, `V25`는 수정하지 않음. `PostComment`에 `deletedAt`·`tombstone(LocalDateTime)`·`isTombstoned()` 추가(`content`·`author`는 그대로 보존). compileJava 통과(테스트는 tester 담당).
- 회귀 수정(tester 발견, 이슈 #277): `RESTRICT` 전환으로 `PostCommentRepository.deleteByPost_Id` 단일 벌크 DELETE가 부모+자식 섞인 게시물에서 행 처리 순서 미보장으로 FK 위반 가능 — `deleteByPost_IdAndParentCommentIsNotNull`(자식 먼저)·`deleteByPost_IdAndParentCommentIsNull`(부모 나중) 두 개의 `@Modifying` 쿼리로 분리, `CommunityPostService.deletePost`(기존 `@Transactional` 경계 그대로)에서 순서대로 호출하도록 수정. 다른 호출부 없음(단일 호출 지점). compileJava 통과.

## AI 로그 (에이전트 참조용, COM-005 tombstone 항목2)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `./gradlew compileJava` | plan.md "COM-005 부모 댓글 tombstone 전환" `deleteComment` 재설계·`PostCommentResponse` 재설계 |

## 모니터링 (사람용 요약)
- COM-005 tombstone 항목2: `PostCommentService.deleteComment`가 소유자 검증(403) 통과 후 `parentComment == null`이면 `comment.tombstone(LocalDateTime.now(clock))`(기존 주입된 `Clock` 재사용, hard delete 없음), 아니면 기존처럼 `postCommentRepository.delete(comment)`. `PostCommentResponse.from(comment, replies)`에 tombstone 분기 추가 — tombstone이면 `content`="삭제된 댓글입니다", `authorNickname`="(삭제됨)"으로 치환, `parentCommentId`·`replies`는 무영향(레코드 필드 추가 없음). 컨트롤러·엔드포인트 계약 변경 없음. compileJava 통과(테스트는 tester 담당).
