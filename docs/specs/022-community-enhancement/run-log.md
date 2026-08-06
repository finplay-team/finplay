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
