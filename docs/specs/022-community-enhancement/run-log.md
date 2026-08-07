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
