# Tasks: 투자 교육 정책

## T1 — 정책 문서 (완료)
- [x] 8개 과정·필수 퀴즈, 순차 잠금과 상태 전이, 진도·오답·재학습·최초 완료 배지, 복기·LLM/RAG 안전 정책과 `/api/education/**` 7개 계약을 확정한다.
- [x] 같은 PR에서 `docs/prd.md` C-004와 3차 로드맵을 동기화해 교육 코치의 확정 자료 설명·재서술 허용과 판정·추천·예측·근거 없는 숫자 금지를 반영한다.

아래 T2~T5는 이 문서 작업의 미완료분이 아니라 **2차 MVP 완료와 3차 착수 승인 후 각각 별도 후속 이슈로 생성할 후보**다. 현재 production 구현을 지시하지 않는다.

## T2 — 교육과정·문항·보상 기준 스키마
- [ ] `spec.md`의 8개 과정·레슨·문항·선택지·정답·정적 해설·확정 RAG 본문과 배지 기준을 변경 없이 적재하는 새 Flyway migration, 엔티티, Repository를 구현한다.
- [ ] 최초 `start`에서만 생성되는 사용자 과정 상태, 시도·현재 오답·복기·배지의 유일 제약과 저장소 정합성을 검증한다. 잠금 저장 컬럼이나 가입 시 과정 행은 만들지 않는다.

## T3 — 목록·상세 조회
- [ ] `GET /api/education/courses`, `GET /api/education/courses/{courseKey}`와 record DTO를 구현한다.
- [ ] 행 없음=`NOT_STARTED`, 선행 완료 기반 잠금 순수 계산, GET DB write 없음, 표시 순서, 잠긴 콘텐츠·정답 비노출과 사용자 격리를 Controller·통합 테스트로 검증한다.

## T4 — 시작·답안·진도·배지
- [ ] `POST /api/education/courses/{courseKey}/start`, `POST /api/education/courses/{courseKey}/lessons/{lessonKey}/answers`, `GET /api/education/progress`를 구현한다.
- [ ] 최초 `start` 행은 `(user_id, course_key)` 유일 키의 원자적 insert-or-existing으로 생성해 동시 요청도 단일 행·모두 200으로 수렴시키고, 재요청 멱등, 서버 판정, 오답 무진도·무보상, 선행 완료 기반 다음 과정 즉시 해제, 과정·문항별 전체/오답/완료 후 재학습 집계를 검증한다.
- [ ] 답안 처리 시 사용자·과정 진도 행 비관 잠금, 과정별 배지와 `INVESTMENT_BEGINNER` 중복 방지, 동시 응답 중 실제 생성자만 `newlyAwarded=true`, DB unique 최종 방어 및 계좌·시드머니 불변을 검증한다.

## T5 — 복기·LLM/RAG
- [ ] `POST /api/education/courses/{courseKey}/lessons/{lessonKey}/reflections` 저장 계약과 `POST /api/education/coach/explanations`의 8개 확정 본문 RAG 어댑터를 구현한다.
- [ ] 복기의 퀴즈 독립성, `sourceKeys`·교육 목적 안내, 추천·가격 방향·예상 수익·근거 없는 숫자 차단, 코치 전용 503과 퀴즈·진도 무영향을 검증하고 실제 Controller 매핑으로 `docs/api-routes.md`를 동기화한 뒤 전체 build를 통과시킨다.

## T2+ Decision Gate
- 2차 MVP 완료, 제품 책임자의 3차 MVP 착수 승인, 본 spec 사용자 확인 전에는 T2~T5 이슈를 착수하지 않는다.
- 구현 전에는 콘텐츠 저장 기술, 배지 표시 자산, 후속 콘텐츠 버전 승계, 시도·복기 보존 기간, LLM·벡터 저장소·검색·프롬프트·timeout 기술값만 추가 확정한다. T1에서 확정한 콘텐츠 본문, 행 없는 상태·계산 잠금·재학습 집계·동시성·보상·복기·RAG 정책과 7개 API 계약은 미확정 대상이 아니다.
