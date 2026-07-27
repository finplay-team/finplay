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
