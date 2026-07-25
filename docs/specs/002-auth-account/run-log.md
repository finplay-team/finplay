# Run Log: 002-auth-account

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:30 | implementer | `.\gradlew.bat compileJava` | plan.md 데이터 모델·구성요소 설계, ADR-0002·ADR-0004 |
| 15:10 | implementer | `.\gradlew.bat compileJava` | plan.md 구성요소 설계(EmailSender 어댑터·프로필 분기), conventions.md 시크릿 규칙 |
| 15:45 | implementer | `.\gradlew.bat compileJava` | spec AUTH-004·plan API설계(발송 제한·HMAC·이전 코드 무효화), conventions dto/레이어 규칙, ClockConfig 주입 |

## 모니터링 (사람용 요약)
- 14:30 — V2 마이그레이션(auth 5개 테이블) + User·EmailVerification 엔티티/Repository 추가, 컴파일 통과.
- 15:10 — EmailSender 어댑터(Fake=`!prod`·Resend=`prod` RestClient) 추가, prod yml에 resend/email 설정, 컴파일 통과.
- 15:45 — 인증번호 발송 API(`POST /api/auth/email-verifications`) 추가: Controller·EmailVerificationService(발송 제한 3종·HMAC 저장·이전 코드 무효화)·요청 DTO, api-routes 갱신, 컴파일 통과.
