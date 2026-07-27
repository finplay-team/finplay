# Run Log: 008-community

## Issue #26

### AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| Task 1 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-26-tasks.md Task 1, issue-26-plan.md D1(생성 DTO 동일 제약)·D3(트랜잭션 경계), conventions.md DTO record 규칙 |
| Task 2 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-26-tasks.md Task 2, docs/adr/0002-architecture.md (controller→service 흐름) |

### 모니터링 (사람용 요약)
- Task 1 — `CommunityPostUpdateRequest`(title `@NotBlank`+`@Size(max=100)`, content `@NotBlank`+`@Size(max=5000)`) 신규 추가. `CommunityPostService.updatePost`는 이미 `@Transactional`이 있어 변경 없음. 컴파일 통과.
- Task 2 — `CommunityPostController`에 `@PatchMapping("/{postId}")` 추가, `updatePost` 호출 후 200 OK 응답. 컴파일 통과. `docs/api-routes.md` 동기화는 이 작업 범위 밖(계획서 명시)이라 미반영.
| 리뷰 | reviewer(리뷰) | `git diff dev...HEAD` (6639094) | conventions.md 레이어·DTO·에러 규칙, ADR-0002/0003/0004, issue-26-plan.md/tasks.md, docs/api-routes.md |

### 모니터링 (사람용 요약, 추가)
- 리뷰 — 레이어링·소유권 검증(403)·미존재(404)·트랜잭션 경계·테스트 3계층 모두 양호. `docs/api-routes.md` PATCH 엔드포인트 미반영(Task 5 미완료)만 차단.

## Issue #28

### AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 20:25 | implementer | `.\gradlew.bat spotlessApply --no-daemon --max-workers=1` | conventions.md Java 포맷 규칙 |
| 20:25 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` | issue-28-plan.md production 구성, ADR-0002·0004 |
| 검증 | tester | 댓글 대상 테스트 | Service 3개, Controller 400 빈 문자열 포함, Repository MySQL 4개, 통합 2개 통과 |
| 검증 | tester | Signup + 댓글 통합 테스트 | 공유 인증·댓글 통합 조합 통과 |
| 검증 | implementer | `.\gradlew.bat spotlessApply --no-daemon --max-workers=1` | Spotless 통과 |
| 검증 | implementer | `.\gradlew.bat build --no-daemon --max-workers=1` | HEAD `dde3e3e`, 407개 테스트·JaCoCo·SpotBugs·Spotless 통과, 4분 29초 |
| 최종 | implementer | `./gradlew.bat build --no-daemon --max-workers=1` | HEAD `7277d4c`, BUILD SUCCESSFUL, 407개 테스트·JaCoCo·SpotBugs·Spotless 통과, 4분 23초 |

### 모니터링 (사람용 요약)
- 20:25 — 평면 댓글 생성 API·영속 모델·V4 마이그레이션·API 문서를 구현했고 포맷 및 컴파일을 통과했다.
- 검증 — Service 3개, 명시적 빈 문자열을 포함한 Controller 검증, Repository MySQL 4개, 통합 2개와 Signup 조합 테스트가 통과했다.
- 검증 — HEAD `dde3e3e`에서 전체 build가 407개 테스트·JaCoCo·SpotBugs·Spotless를 포함해 4분 29초에 통과했다.
- 최종 — HEAD `7277d4c`에서 `./gradlew.bat build --no-daemon --max-workers=1`이 407개 테스트·JaCoCo·SpotBugs·Spotless를 포함해 4분 23초에 `BUILD SUCCESSFUL`로 통과했다.

## Issue #27

### AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| Task 1 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-27-tasks.md Task 1, conventions.md 레이어 규칙(service 트랜잭션 경계·orElseThrow), ADR-0002 |
| Task 2 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-27-tasks.md Task 2, conventions.md API 규칙(본문 없는 성공 204), ADR-0002(controller→service 흐름) |

### 모니터링 (사람용 요약)
- Task 1 — `PostCommentRepository.deleteByPost_Id(Long)` 파생 삭제 쿼리, `CommunityPostService.deletePost(authenticatedUserId, postId)`(NOT_FOUND→FORBIDDEN→댓글 삭제→게시물 삭제 순) 추가. 컴파일 통과. 컨트롤러·테스트·문서는 범위 밖.
- Task 2 — `CommunityPostController`에 `@DeleteMapping("/{postId}")` 추가, `deletePost` 호출 후 204 No Content 응답. 컴파일 통과. 테스트·`docs/api-routes.md` 동기화는 범위 밖.
- 리뷰 — 레이어링·소유권 검증(403)·미존재(404)·트랜잭션 경계(댓글 선삭제 후 게시물 삭제)·테스트 3계층 모두 적합. `docs/api-routes.md` DELETE 엔드포인트 미반영(Task 5 미완료)으로 차단 1건.
- 문서 동기화 — planner(동기화 모드)가 `docs/api-routes.md`에 `DELETE /api/community/posts/{postId}` 요약 행과 상세 절(인증·요청·204 응답·401/403/404 오류·Spec 008 COM-001, Issue #27)을 추가하고 issue-27-tasks.md Task 5를 완료 처리했다.

## Issue #29

### AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 22:06 | implementer | `.\gradlew.bat spotlessApply --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | conventions.md Java 포맷 규칙 |
| 22:06 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-29-plan.md D1~D4, ADR-0002 레이어 구조 |
| 환경 실패 | tester | 오래된 `JAVA_HOME=C:\Users\PMS\.jdks\ms-17.0.20` 설정 후 wrapper 실행 — Gradle 시작 전 경로 없음으로 실패 | 확인된 하네스 설정 실수, production 결함 아님 |
| 환경 확인 | tester | `docker info` — 성공 | Repository·통합 테스트의 MySQL Testcontainers 실행 전제 확인 |
| 집중 검증 | tester | `$env:JAVA_HOME='C:\Users\PMS\.jdks\corretto-17.0.18'; .\gradlew.bat test --tests "com.finplay.api.community.service.PostCommentServiceTest" --no-daemon --max-workers=1` — 성공 (1분 7초) | Service 정상·빈 목록·게시물 미존재 |
| 집중 검증 | tester | `$env:JAVA_HOME='C:\Users\PMS\.jdks\corretto-17.0.18'; .\gradlew.bat test --tests "com.finplay.api.community.controller.PostCommentControllerTest" --no-daemon --max-workers=1` — 성공 (1분 17초) | Controller 응답·404·401 |
| 집중 검증 | tester | `$env:JAVA_HOME='C:\Users\PMS\.jdks\corretto-17.0.18'; .\gradlew.bat test --tests "com.finplay.api.community.repository.PostCommentRepositoryTest" --no-daemon --max-workers=1` — 성공 (1분 58초) | MySQL 게시물 격리·정렬·작성자 fetch |
| 집중 검증 | tester | `$env:JAVA_HOME='C:\Users\PMS\.jdks\corretto-17.0.18'; .\gradlew.bat test --tests "com.finplay.api.community.PostCommentListIntegrationTest" --no-daemon --max-workers=1` — 성공 (2분 27초) | 인증 필터·MySQL 목록 핵심 시나리오 |
| 최종 | tester | `.\gradlew.bat build --no-daemon --max-workers=1` | HEAD `77427a6`, `BUILD SUCCESSFUL` (5분 7초), JaCoCo·SpotBugs·Spotless 통과 |

### 모니터링 (사람용 요약)
- 게시물 존재와 빈 댓글 목록을 구분하고, 대상 게시물 댓글을 작성자 fetch join 및 `createdAt ASC, id ASC`로 조회하는 GET API와 라우트 문서를 구현했다. 포맷과 컴파일이 통과했다.
- 환경 실패 — 문서의 오래된 `JAVA_HOME` 경로를 사용한 첫 wrapper 시도는 Gradle 시작 전에 실패했다. 설치된 `C:\Users\PMS\.jdks\corretto-17.0.18`로 바로잡았으며 통과 증거로 계산하지 않았다.
- 집중 검증 — Docker 가용성을 확인한 뒤 Service·Controller·Repository MySQL·목록 통합 테스트를 수정된 JDK 환경에서 순차 실행해 모두 통과했다.
- 최종 — HEAD `77427a6`에서 `.\gradlew.bat build --no-daemon --max-workers=1`이 5분 7초에 `BUILD SUCCESSFUL`로 끝났고 JaCoCo·SpotBugs·Spotless를 포함한 전체 게이트가 통과했다.
