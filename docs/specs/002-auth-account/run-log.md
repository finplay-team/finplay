# Run Log: 002-auth-account

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:30 | implementer | `.\gradlew.bat compileJava` | plan.md 데이터 모델·구성요소 설계, ADR-0002·ADR-0004 |
| 15:10 | implementer | `.\gradlew.bat compileJava` | plan.md 구성요소 설계(EmailSender 어댑터·프로필 분기), conventions.md 시크릿 규칙 |
| 15:45 | implementer | `.\gradlew.bat compileJava` | spec AUTH-004·plan API설계(발송 제한·HMAC·이전 코드 무효화), conventions dto/레이어 규칙, ClockConfig 주입 |
| 16:10 | implementer | `.\gradlew.bat test --tests "*GlobalExceptionHandlerTest" --tests "*EmailVerificationControllerTest"` | @WebMvcTest 슬라이스 스캔 원리(무지정 시 전체 컨트롤러 스캔), 이슈 #32 기존 테스트 |
| 16:40 | reviewer(리뷰) | `git diff dev...HEAD` | conventions.md(시크릿·레이어·DTO), ADR-0002·0003·0004, spec/plan AUTH-004 |
| 18:50 | implementer | `.\gradlew.bat test --tests "*FinPlayApiApplicationTests" --tests "*EmailVerificationServiceTest" --tests "*EmailVerificationControllerTest"` | 리뷰 차단 반영: conventions.md 시크릿 규칙(기본값 금지·fail-fast), JWT_SECRET 패턴 |
| 19:00 | implementer | `.\gradlew.bat build` | 시크릿 주입 중앙화: build.gradle Test 태스크에 더미 env 일괄 공급, @DynamicPropertySource 중복 제거(fail-fast 유지) |
| 21:13 | implementer | `.\gradlew.bat compileJava` | issue-9-plan.md Task 1 Provider 계약·프로필별 URI, ADR-0002·conventions.md |

## 모니터링 (사람용 요약)
- 14:30 — V2 마이그레이션(auth 5개 테이블) + User·EmailVerification 엔티티/Repository 추가, 컴파일 통과.
- 15:10 — EmailSender 어댑터(Fake=`!prod`·Resend=`prod` RestClient) 추가, prod yml에 resend/email 설정, 컴파일 통과.
- 15:45 — 인증번호 발송 API(`POST /api/auth/email-verifications`) 추가: Controller·EmailVerificationService(발송 제한 3종·HMAC 저장·이전 코드 무효화)·요청 DTO, api-routes 갱신, 컴파일 통과.
- 16:10 — 전체 스위트에서 깨지던 GlobalExceptionHandlerTest를 `@WebMvcTest(controllers = TestController.class)`로 슬라이스 한정, 두 테스트 클래스 통과 확인.
- 16:40 — 리뷰 완료(발송 API 범위). 차단 1건: EMAIL_VERIFICATION_SECRET 코드 내 dev 기본값(시크릿 컨벤션 위반·prod fail-open). 권장 2·참고 2.
- 18:50 — 차단 수정: EMAIL_VERIFICATION_SECRET `@Value` 기본값 제거(fail-fast), contextLoads 테스트에만 `@DynamicPropertySource`로 더미 시크릿 공급. 3개 테스트 통과.
- 19:00 — @SpringBootTest 확장성 문제 해결: 더미 시크릿을 build.gradle Test 태스크 env로 중앙화하고 `@DynamicPropertySource` 제거, 전체 `build` BUILD SUCCESSFUL.
- 21:13 — OAuth Provider 계약과 카카오·네이버 실제/Fake 프로필 구현, 환경변수 바인딩 추가, 컴파일 통과.
