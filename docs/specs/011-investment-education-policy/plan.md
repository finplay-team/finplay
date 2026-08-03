# Plan: 투자 교육 정책

## 관련 문서
- Spec: `./spec.md`
- PRD: `../../prd.md` — C-001, C-004, 3차 MVP 및 1차 제외 범위, 3차 Decision Gate
- 관련 ADR: ADR-0002
- 컨벤션: `../../conventions.md`

## 착수 제한
- 이 계획은 3차 MVP 후보 계약이며 현재 production 구현 계획의 승인으로 간주하지 않는다.
- 2차 MVP 완료와 3차 착수 승인, `spec.md`의 T2+ 구현 세부사항 확정 전에는 아래 task를 실행하거나 `src/`, DB migration, `docs/api-routes.md`를 변경하지 않는다.
- Gate 통과 시에도 실제 구현 전 확정된 계약을 이 문서에 반영하고 사용자 확인을 다시 받는다.
- 같은 PR에서 PRD C-004를 동기화했다. 3차 교육 코치는 확정 교육 자료를 초보자에게 설명·재서술할 수 있지만 판정·추천·예측·근거 없는 숫자 생성은 금지한다.

## 컴포넌트 설계
- `education` 도메인 패키지 안에서 `controller → service → repository` 흐름을 유지한다.
- 콘텐츠 조회, 서버 판정, 사용자 학습 상태, 배지 부여 책임을 service에서 조합하며 Controller는 인증 사용자와 HTTP 계약만 처리한다.
- 문제·정답은 `spec.md`의 "과정 및 필수 퀴즈 정의"를 최초 콘텐츠 버전의 정본으로 삼는다. 저장 방식(DB seed 또는 정적 리소스)은 T2 구현 설계에서 결정하되 key·질문·선택지·정답·정적 해설을 바꾸지 않는다.
- 배지 저장은 동일 사용자·배지 코드 중복을 DB 유일 제약으로 차단하고, 과정 완료와 배지 최초 부여를 한 트랜잭션으로 처리한다.
- 복기 저장은 교육 진도와 분리하며 복기 생성 성공·실패가 과정 상태나 배지를 바꾸지 않는다.
- 교육 코치 어댑터는 확정 교육 자료 검색 결과만 LLM에 제공한다. 코치 호출은 퀴즈 트랜잭션과 분리하고 장애 시 코치 API만 503을 반환한다.

## API 설계
모든 API는 인증이 필요하며 다른 사용자의 식별자를 요청으로 받지 않는다.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/education/courses` | 없음 | `EducationCourseListResponse` | 8개 과정의 순서·상태·잠금·진도 조회. 잠긴 콘텐츠 제외 |
| GET | `/api/education/courses/{courseKey}` | 없음 | `EducationCourseDetailResponse` | 열린 과정의 레슨·문제·선택지 조회. 정답 제외 |
| POST | `/api/education/courses/{courseKey}/start` | 없음 | `EducationCourseStartResponse` | 최초 시작 또는 완료 과정 재학습 콘텐츠 반환. 재요청 멱등, 정답 비노출 |
| POST | `/api/education/courses/{courseKey}/lessons/{lessonKey}/answers` | `EducationAnswerCreateRequest` | `EducationAnswerResponse` | 레슨의 필수 문제 답안 제출, 서버 판정, 진도·오답·완료·신규 배지 결과 반환 |
| GET | `/api/education/progress` | 없음 | `EducationProgressResponse` | 전체 과정 진도, 현재 오답, 완료 이력, 획득 배지 조회 |
| POST | `/api/education/courses/{courseKey}/lessons/{lessonKey}/reflections` | `EducationReflectionCreateRequest` | `EducationReflectionResponse` | 본인 레슨 복기 이력 생성 |
| POST | `/api/education/coach/explanations` | `EducationCoachExplanationCreateRequest` | `EducationCoachExplanationResponse` | 확정 교육 자료 RAG 기반 교육 설명과 출처 반환 |

과정 상태는 `NOT_STARTED`, `IN_PROGRESS`, `COMPLETED`이며 잠금은 저장하지 않고 조회 시 계산한 `locked` 필드다. 사용자 과정 행이 없으면 `NOT_STARTED`이고, 첫 과정은 선행 과정이 없어 열리며 이후 과정은 직전 과정 완료 기록이 있을 때 열린다. GET은 DB write를 하지 않는다. `POST .../start`는 열린 과정의 최초 요청에서 사용자 과정 행을 만들어 `IN_PROGRESS`로 바꾸며 재요청은 기존 상태·진도를 그대로 반환한다. 완료 과정 재요청도 새 회차를 생성하지 않고 재학습용 콘텐츠를 반환한다. `POST .../answers`의 오답은 시도·오답 상태만 기록하며 정답 수·진도·완료·잠금 계산·배지를 변경하지 않는다. 같은 정답을 재제출해도 이미 인정된 진도는 늘지 않고 배지의 `newlyAwarded`는 최초 부여 요청에서만 `true`다.

조회·시작·답안·진도·코치 성공은 200, 복기 생성은 201이다. 답안과 복기는 `start`를 거쳐 `IN_PROGRESS` 또는 `COMPLETED`인 열린 과정에서만 허용한다.

## 입력 명세
| 필드 | 필수 | 검증 |
|---|---|---|
| `courseKey` | 필수 | `spec.md`에 등록된 8개 과정 key, 미존재 시 `EDUCATION_COURSE_NOT_FOUND` |
| `lessonKey` | 필수 | 해당 과정에 등록된 레슨 key, 미존재 또는 과정 불일치 시 `EDUCATION_LESSON_NOT_FOUND` |
| `questionKey` | 필수 | 해당 과정·레슨·현재 콘텐츠 버전의 문제 key, 아니면 `EDUCATION_QUESTION_NOT_FOUND` |
| `selectedChoiceKey` | 필수 | 해당 문제에 등록된 선택지 key, 아니면 `VALIDATION_ERROR` |
| `reflectionText` | 필수 | 공백 제외 1~2000자, 위반 시 `VALIDATION_ERROR` |
| `question` | 필수 | 코치 질문, 공백 제외 1~500자, 위반 시 `VALIDATION_ERROR` |

답안 요청은 `questionKey`, `selectedChoiceKey`만 포함한다. 복기 요청은 `reflectionText`만 포함한다. 코치 요청은 `courseKey`, `lessonKey`, `question`을 포함하며 클라이언트가 RAG 출처나 숫자 근거를 주입할 수 없다.

## 최초 콘텐츠 manifest
구현은 아래 key 조합으로 `spec.md`의 질문·선택지·정답·정적 해설을 정확히 적재한다.

| 순서 | courseKey | lessonKey | questionKey | correctChoiceKey | badgeCode | sourceKey |
|---|---|---|---|---|---|---|
| 1 | `INVESTMENT_AND_RISK` | `RISK_AND_RETURN_BASICS` | `RISK_Q1_LOSS_POSSIBILITY` | `A` | `EDUCATION_INVESTMENT_AND_RISK` | `EDU_SOURCE_RISK_AND_RETURN_BASICS_V1` |
| 2 | `STOCKS_AND_CRYPTO_DIFFERENCES` | `ASSET_CHARACTERISTICS` | `ASSET_Q1_ISSUER_AND_MARKET` | `A` | `EDUCATION_STOCKS_AND_CRYPTO_DIFFERENCES` | `EDU_SOURCE_ASSET_CHARACTERISTICS_V1` |
| 3 | `ORDERS_AND_EXECUTIONS` | `ORDER_EXECUTION_BASICS` | `EXECUTION_Q1_ORDER_VS_TRADE` | `B` | `EDUCATION_ORDERS_AND_EXECUTIONS` | `EDU_SOURCE_ORDER_EXECUTION_BASICS_V1` |
| 4 | `MARKET_AND_LIMIT_ORDERS` | `ORDER_TYPE_TRADEOFFS` | `ORDER_TYPE_Q1_PRICE_CONTROL` | `A` | `EDUCATION_MARKET_AND_LIMIT_ORDERS` | `EDU_SOURCE_ORDER_TYPE_TRADEOFFS_V1` |
| 5 | `UNREALIZED_AND_REALIZED_PNL` | `PNL_STATE_DIFFERENCES` | `PNL_Q1_BEFORE_AND_AFTER_SELL` | `A` | `EDUCATION_UNREALIZED_AND_REALIZED_PNL` | `EDU_SOURCE_PNL_STATE_DIFFERENCES_V1` |
| 6 | `FEES_AND_RETURNS` | `NET_RETURN_BASICS` | `RETURN_Q1_FEE_EFFECT` | `B` | `EDUCATION_FEES_AND_RETURNS` | `EDU_SOURCE_NET_RETURN_BASICS_V1` |
| 7 | `DIVERSIFICATION` | `CONCENTRATION_RISK` | `DIVERSIFICATION_Q1_PURPOSE` | `B` | `EDUCATION_DIVERSIFICATION` | `EDU_SOURCE_CONCENTRATION_RISK_V1` |
| 8 | `INVESTMENT_PLAN_AND_REVIEW` | `PLAN_AND_REVIEW_LOOP` | `PLAN_Q1_REVIEW_PURPOSE` | `B` | `EDUCATION_INVESTMENT_PLAN_AND_REVIEW` | `EDU_SOURCE_PLAN_AND_REVIEW_LOOP_V1` |

## 핵심 응답 계약
- 과정 목록: 과정마다 `courseKey`, `title`, `displayOrder`, `status`, `locked`, `correctRequiredQuestions`, `totalRequiredQuestions`, `completedAt`, `courseBadgeAwarded`. 잠긴 항목에는 레슨·문제·선택지를 포함하지 않는다.
- 과정 상세·시작: `courseKey`, `title`, `displayOrder`, `status`, `locked=false`, `contentVersion`, `lessons`; 레슨과 문제에는 `lessonKey`, `questionKey`, 질문, 선택지만 포함하고 `correctChoiceKey`는 노출하지 않는다.
- 답안 제출: `questionKey`, `correct`, `explanation`, `progress`, `courseCompleted`, `newlyAwardedBadges`.
- 전체 진도: 과정별 `status`, 계산된 `locked`, 정답 진도, 현재 오답 key, `completedAt`, 전체 `awardedBadges`와 아래 재학습 집계.
  - 과정별: `attemptCount`, `incorrectAttemptCount`, `lastAnsweredAt`, `relearningAttemptCount`, `lastRelearningAnsweredAt`.
  - 문항별: `questionKey`, `currentlyIncorrect`와 과정별과 같은 다섯 집계 필드.
  - `attemptCount`와 `incorrectAttemptCount`는 전체 시도 누계다. `relearningAttemptCount`와 `lastRelearningAnsweredAt`은 과정 `completedAt` 이후 시도만 집계한다. 해당 시도가 없으면 count는 0, timestamp는 null이다.
  - 선택한 답안 원문의 전체 목록은 반환하지 않는다. 집계와 현재 오답 상태로 반복 학습을 관찰하되 불필요한 원문 노출과 응답 팽창을 피한다.
- 복기 저장: 서버 생성 `reflectionKey`, `courseKey`, `lessonKey`, `reflectionText`, `createdAt`. 각 POST는 새 이력을 201로 생성하며 기존 기록을 변경하지 않는다.
- 코치 설명: `explanation`, 검색 근거의 `sourceKeys`, 고정 `disclaimer="교육 목적 정보이며 투자 추천이 아닙니다."`. 추천·가격 방향·예상 수익·검색 근거에 없는 숫자를 묻는 경우 금지 내용을 생성하지 않고 교육 범위 안내와 사용한 `sourceKeys`만 반환한다.
- 배지 항목: 안정적인 `badgeCode`, `awardedAt`. 전체 완료 배지 코드는 `INVESTMENT_BEGINNER`다.
- 정답 선택지 ID 자체는 제출 응답에 반환하지 않는다. `explanation`은 사전 작성 해설을 기본값으로 하며 LLM 사용 여부와 무관하게 판정 결과는 동일하다.

## 오류 계약
| HTTP | code | 조건 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 필수값 누락, 과정에 속하지 않은 선택지 |
| 401 | `UNAUTHORIZED` | 인증 정보 없음·만료 |
| 403 | `EDUCATION_COURSE_LOCKED` | 선행 과정 미완료인 과정의 상세·시작·답안·복기·코치 요청 |
| 404 | `EDUCATION_COURSE_NOT_FOUND` | 과정 코드 없음 |
| 404 | `EDUCATION_LESSON_NOT_FOUND` | 레슨 없음 또는 과정 불일치 |
| 404 | `EDUCATION_QUESTION_NOT_FOUND` | 문제 없음 또는 과정·콘텐츠 버전 불일치 |
| 409 | `EDUCATION_COURSE_NOT_STARTED` | `start` 전 답안 또는 복기 요청 |
| 409 | `EDUCATION_CONTENT_VERSION_CONFLICT` | 조회 후 콘텐츠 버전이 바뀌어 현재 답안을 판정할 수 없음 |
| 503 | `EDUCATION_COACH_UNAVAILABLE` | LLM 또는 RAG 검색 장애. 다른 교육 API에는 사용하지 않음 |

## 데이터 모델
구체 테이블명과 컬럼은 Gate 통과 후 기존 배지 모델 조사 결과에 맞춰 확정한다. 필요한 논리 모델은 다음과 같다.

- 교육 과정/문제/선택지/정답: 안정적인 과정 코드, 콘텐츠 버전, 표시 순서, 필수 여부, 서버 정답과 정적 해설.
- 사용자 과정 진도: 최초 `start`에서 생성되는 사용자·과정·콘텐츠 버전 행, `IN_PROGRESS`·`COMPLETED`, 최초 시작·완료 시각. 행 없음은 `NOT_STARTED`이며 잠금 컬럼은 두지 않는다.
- 사용자 문제 시도: 제출 답안, 서버 판정, 시도 시각. 감사 가능한 이력과 현재 오답 상태를 구분한다.
- 사용자 배지: 사용자, 배지 코드, 최초 획득 시각. `(user_id, badge_code)` 유일 제약으로 중복을 막는다.
- 사용자 복기: 서버 생성 key, 사용자·과정·레슨, 본문, 생성 시각. 진도·보상 FK 입력으로 사용하지 않는다.
- 교육 RAG 자료: `sourceKey`, 과정·레슨 key, 확정 자료 본문과 콘텐츠 버전. LLM에는 검색된 자료와 사용자 질문만 전달한다.
- 교육 모델은 계좌·현금·시드머니 필드를 참조하거나 갱신하지 않는다.

## 트랜잭션 및 동시성
- 답안 시도 저장, 현재 오답·진도 갱신, 과정 최초 완료, 해당 과정 배지 부여, 전체 완료 검사와 `INVESTMENT_BEGINNER` 부여를 하나의 service 트랜잭션에서 처리한다.
- 과정 완료 커밋 뒤 다음 GET은 직전 과정 완료 존재 여부로 다음 과정의 `locked=false`를 즉시 계산한다. 다음 과정 행 생성이나 잠금 컬럼 갱신은 하지 않는다.
- DB 유일 제약을 최종 중복 방어선으로 사용해 반복·동시 제출을 멱등한 배지 결과로 수렴시킨다.
- `start`는 `(user_id, course_key)` 유일 제약에 MySQL 원자적 `INSERT ... ON DUPLICATE KEY UPDATE id = id`를 사용한 뒤 해당 행을 조회한다. 최초 동시 요청과 재요청 모두 예외 없이 같은 사용자·과정 진도 하나와 200 응답으로 수렴하며 새 회차나 보상을 만들지 않는다.
- 답안 트랜잭션은 사용자·과정 진도 행을 비관 잠금(`SELECT ... FOR UPDATE`)한 뒤 시도 저장, 진도·완료와 배지를 처리한다. 같은 동시 답안 중 커밋에 성공해 배지를 실제 생성한 응답만 `newlyAwarded=true`이고, 대기 후 상태를 재조회한 응답은 `false`다. `(user_id, badge_code)` DB unique는 최종 방어선이다.
- 복기 생성과 LLM/RAG 설명 호출은 퀴즈 트랜잭션 밖에서 수행하고 판정·진도·보상 입력으로 사용하지 않는다.

## LLM/RAG 안전 계약
- 검색 범위는 요청한 과정·레슨의 확정 교육 자료 `sourceKey`로 제한한다.
- 모델 입력에는 검색된 자료, 사용자 질문, 금지 정책만 전달한다. 시세·계좌·주문 데이터나 임의 외부 검색 결과를 사용하지 않는다.
- 출력 검증에서 특정 자산 추천, 매수·매도 지시, 가격 방향, 예상 수익·수익률, 검색 자료에 없는 숫자를 차단하고 안전한 교육 범위 문구로 대체한다.
- 정상 응답은 실제 사용한 `sourceKeys`와 고정 교육 목적 안내를 포함한다. 검색 근거가 없거나 LLM/RAG가 실패하면 추측하지 않고 503을 반환한다.
- 코치 API 성공·실패는 퀴즈 판정·정적 해설·과정 상태·배지 결과를 변경하지 않는다.

## 테스트 계획
- 단위: 행 없는 초기 상태·순차 잠금 순수 계산, `start` 멱등, 서버 정답 판정, 오답 무진도·무보상, 과정·전체 완료, 재학습 집계, LLM 결과 배제.
- 슬라이스: 7개 API의 인증, 잠금, 검증, 정답 비노출, 응답 필드와 오류 코드.
- 통합: GET 무쓰기, 최초 동시 `start` 두 요청의 단일 행·모두 200 수렴, 8개 과정 순차 최초 완료, 과정별/전체 배지 단 한 번 부여, 비관 잠금 동시 답안 응답의 `newlyAwarded` 단일 true, 재학습 집계, 복기 이력, 사용자 격리, RAG 출처·금지 출력·독립 503, 교육 완료 후 계좌 잔액 불변.
- 문서: Controller 구현이 승인된 시점에 실제 매핑을 기준으로 `docs/api-routes.md`를 동기화한다.

## T1 확정과 T2+ 미확정 설계
- T1 확정: 최초 콘텐츠 manifest와 재현 가능한 8개 RAG 본문, 행 없는 초기 상태·계산 잠금·멱등·서버 판정·재학습 집계·보상 정책, 7개 API 전체 HTTP 계약, 복기 및 LLM/RAG 안전·장애 계약.
- T2+ 미확정: 콘텐츠 저장·배포 방식과 후속 콘텐츠 버전 변경 시 기존 진도·완료 승계 규칙.
- 배지 공통 도메인 신설 여부 및 확정된 8개 과정 배지 코드의 표시 자산 연결 방식.
- 완료 과정의 `start` 재요청은 별도 회차를 생성하지 않는다. 완료 후 답안 시도를 재학습 이력으로 구분하는 내부 컬럼과 시도·복기 보존 기간은 T2+에서 정한다.
- 답안 제출 재시도용 별도 멱등키 필요 여부. 인정 진도와 배지는 key 없이도 중복 증가하지 않아야 한다.
- 확정 RAG 안전·503 계약을 만족하는 모델, 벡터 저장소, 검색 파라미터, 프롬프트와 timeout 값.
