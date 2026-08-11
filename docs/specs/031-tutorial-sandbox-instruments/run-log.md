# Run Log: 031-tutorial-sandbox-instruments

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md 1번 "샘플 종목 데이터 모델", ADR-0004(migration 정책) |

## 모니터링 (사람용 요약)
- V32 마이그레이션(`is_tutorial_sample` 컬럼 + 샘플 종목 6행) 추가, `Instrument`·`InstrumentResponse`에 필드 반영, `InstrumentService.getTradableInstrumentEntity`에 샘플 종목 커뮤니티 태그 제외 조건 추가. 컴파일 통과.
