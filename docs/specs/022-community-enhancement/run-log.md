# Run Log: 022-community-enhancement

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava`, `compileTestJava`, `test --tests CommunityPostTest --tests InstrumentServiceTest` | plan.md "데이터 모델"·"패키지·클래스 설계", ADR-0002, ADR-0004 |

## 모니터링 (사람용 요약)
- COM-004 항목1: `V24` 마이그레이션·`CommunityPost.instrument`·`InstrumentService.getTradableInstrumentEntity` 추가, compileJava/compileTestJava 통과, 신규 단위 테스트 2건 통과.
