# Run Log: 003-market-data

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `./gradlew compileJava` | plan.md 구성요소 설계(market 패키지), spec.md MKT-001, ADR-0002/0004, conventions.md |
| - | reviewer(리뷰) | `git diff dev...HEAD` (feat/014-instruments-list vs dev) | conventions.md, ADR-0002/0003/0004, spec.md MKT-001 |

## 모니터링 (사람용 요약)
- instruments 마이그레이션(V7, 시드 16+12) + Instrument 엔티티·Repository·Service·Controller(GET /api/instruments?market=) 구현, 컴파일 통과. 단건 조회(#15)는 범위 밖으로 제외.
- 리뷰(이슈 #14, 로컬 reviewer): 차단 0건 / 권장 2건 — 서비스 단위 테스트 부재, 컨트롤러 테스트 메서드명·검증 불일치. 머지 가능 판정.
- PR #74 리뷰(팀 리뷰어): 차단 1건(공개 화이트리스트가 plan.md의 "조회 API는 인증 필요" 전제와 불일치 — 사용자 결정으로 인증 필요로 변경, SecurityConfig 공개 목록에서 제거) + 권장 2건(컨트롤러의 Market.valueOf try-catch가 컨트롤러에서 비즈니스 판단을 하는 문제 — `@RequestParam Market` 타입 바인딩 + GlobalExceptionHandler의 `MethodArgumentTypeMismatchException` → 400 매핑으로 교체, 향후 #15 단건 조회에도 재사용됨; 테스트 메서드명이 실제 검증 내용과 불일치 — 정정). 빈 문자열(`market=`) 쿼리가 타입 바인딩에서도 생략과 동일하게 처리되는지 임시 프로브 테스트로 실측 확인 후 반영, 프로브는 삭제.
