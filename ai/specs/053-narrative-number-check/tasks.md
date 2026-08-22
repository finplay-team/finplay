# Tasks: 서술 후검증을 표현 대조에서 숫자 대조로 옮기기

항목 6개. 순서대로 진행한다 — 2는 1에, 3·4는 2에 의존한다. 5·6은 2가 끝난 뒤면 언제든 된다.

**전 항목 공통 금지 사항** (spec §범위 제외, 2026-08-23 범위 축소).

- 금지 표현 37개를 빼거나 늘리지 않는다. `JUDGEMENT`의 가정법 어미 5개도 그대로 둔다.
- 시스템 프롬프트를 한 글자도 바꾸지 않는다 (§결정 3은 보류이지 폐기가 아니다).
- `PostSellPromptDto`에 반사실 필드를 넣지 않고 `PostSellFeedbackService.toPromptInput`을 바꾸지 않는다 (이슈 #531로 분리).
- 위반 시 동작(재생성 횟수·템플릿 폴백 규칙)을 바꾸지 않는다 (FEED-017).

---

- [x] **1. `NarrativeNumberValidator` 신설 + 단위 테스트** (FEED-014의 판정 규칙)

  `src/main/java/com/finplay/api/domain/feedback/service/NarrativeNumberValidator.java`를 만든다. 첫 줄에 한 줄 한국어 주석(CLAUDE.md 규칙 6). 입력은 `(서술, 프롬프트)`이고 반환은 기존 `NarrativeValidationDto`다 — 새 DTO를 만들지 않는다. 추출 정규식·`BigDecimal` 정규화·부호 규칙·콜론 분리는 plan.md §결정 C 그대로다. **이 항목에서는 아무도 이 클래스를 부르지 않는다** — 배선은 항목 2다.

  같은 커밋에 `NarrativeNumberValidatorTest`(신설)와 `NarrativePromptBuilderTest`의 단정 하나(시스템 프롬프트에 숫자가 없다)를 넣는다. 골든 마스터 `systemPromptMatchesSpecExactly`는 손대지 않는다.

  완료 판정 — spec §결정 1의 판정 표 6행이 전부 표대로 나오고, 프롬프트 수치 집합이 사용자 프롬프트 문자열 하나에서만 만들어진다.

- [ ] **2. `NarrativeService` 배선 — 숫자 대조를 매도 회고에만 건다** (FEED-014의 적용 범위 + FEED-017)

  `resolveWithTemplateFallback`에 네 번째 인자 `numberSourcePrompt`를 더해 카드는 `null`, 매도 회고는 자기 `userPrompt`를 넘긴다(plan.md §결정 B). 두 검증 결과의 적발 목록은 이어 붙여 **기존 로그 한 줄**에 그대로 넘기고 폴백 분기는 하나로 유지한다.

  `NarrativeServiceTest`에 파트 분기 테스트를 더한다 — **출처 없는 수치가 든 같은 문장**이 매도 회고에서는 `TEMPLATE`, 변동 카드에서는 `LLM`이 되는지 결과로 확인한다(mock 호출 횟수로 세지 않는다).

  **매도 회고 경로를 실제로 타는 통합 테스트가 이 항목에서 처음 새 축을 만난다.** `PostSellFeedbackNarrativeIntegrationTest`·`PostSellFeedbackRegenerationIntegrationTest`·`PostSellFeedbackJournalIntegrationTest`·`PostSellFeedbackPeerComparisonGateIntegrationTest`·`CryptoPostSellFeedbackE2eIntegrationTest`가 `FakeNarrativeGenerator`에 넣는 고정 서술이 프롬프트 수치와 어긋나면 **픽스처를 프롬프트에 맞춘다** — 기대값을 `TEMPLATE`으로 바꿔 통과시키지 않는다.

  완료 판정 — 위 통합 테스트가 전부 통과하고, `NarrativeValidatorTest`가 **수정 없이** 통과한다(FEED-016).

- [ ] **3. 템플릿 폴백 문장이 새 축을 통과하는지 교차 검사** (spec §완료 조건)

  `NarrativeTemplateBuilderTest`에 `postSellTemplate` × `postSellPrompt` 교차 검사를 더한다 — 같은 입력으로 두 문자열을 만들어 숫자 대조에 넣는다. 극값 없음 · `DAILY` 극값 · `multiDayHold` 세 갈래를 모두 건다(시각 표기가 갈리는 자리다).

  **카드 템플릿은 이 검사에 넣지 않는다.** 카드 프롬프트에 구간 길이(분)가 없어 `5분간`이 설계상 위반이며, 그것이 카드에 축을 걸지 않는 근거다(plan.md §결정 B). 이 사실을 테스트 주석으로 남긴다 — 다음 사람이 "카드도 걸자"고 되돌리지 않게 한다.

  완료 판정 — 폴백 문장이 스스로 위반이 아니므로 대체 경로가 성립한다.

- [ ] **4. 측정 ①의 "새 검증기" 열을 채우고 `measurement.md`를 갱신한다** (spec §측정)

  `NarrativeNumberCheckMeasurementTest`에 새 검증기 단정을 더한다 — 주입 12건을 `NarrativeNumberValidator`로 재고 **12/12**를 단정하며, `printsTheMeasurementTable`이 기존 열과 새 열을 함께 출력하게 한다. ②는 **6/6 유지**를 단정한다(반사실 두 값이 프롬프트에 없어 새 축으로도 걸린다).

  **`todaysValidatorReproducesTheFrozenBaseline`은 이 설계에서 깨지지 않는다.** 숫자 대조가 별도 클래스에 있어 `NarrativeValidator.validateCardOrPostSell`의 판정이 무변경이기 때문이다. 그 테스트의 "FEED-014가 숫자 대조를 더하면 첫 번째 단정이 깨진다"는 **주석 예고를 지우고**, 이 테스트가 이제 **FEED-016의 증거**(표현 축이 한 글자도 움직이지 않았다)라는 것으로 다시 적는다. 얼린 목록(`LEGACY_RULES`)은 그대로 둔다 — ①의 0/12 대조군이 거기 있다.

  `measurement.md`도 같은 커밋에서 갱신한다 — ①② 표의 "새 검증기" 열, 그리고 §대조군을 어떻게 얼렸나의 마지막 문단(예고가 빗나갔다는 사실과 그 이유).

- [ ] **5. `012` §후검증 동기화 + `NarrativeValidator` 클래스 주석 전제 갱신** (같은 커밋)

  `ai/specs/012-ai-feedback/spec.md` §후검증에 **검증 축이 하나 더해졌다**는 것을 적는다. **표현 목록 5줄과 파트별 적용 표는 한 글자도 건드리지 않는다** — 더하는 것은 "매도 회고에는 숫자 대조가 함께 걸린다"와 그 판정 규칙(수만 보되 부호 기호가 있으면 방향까지, 허용 집합은 프롬프트 문자열의 모든 수, 화이트리스트 없음)이며 근거로 `053`을 가리킨다. `TEMPLATE` 30% 기준선은 그대로다.

  같은 커밋에서 `NarrativeValidator`의 클래스·메서드 주석을 고친다 — "37개가 전부 리터럴이라 정규식을 쓰지 않는다"는 **표현 축에만 해당한다**는 것을 명시하고, 숫자 대조는 `NarrativeNumberValidator`에 있다는 것을 한 줄로 가리킨다. **판정 로직은 손대지 않는다.**

  `docs/api/feedback.md`의 매도 회고 §문구 제약 문단에 한 문장을 더한다 — 서술의 수치는 서버가 준 값이어야 하고 아니면 템플릿으로 대체된다(블랙박스 QA의 근거가 된다). 엔드포인트가 늘지 않으므로 `ai/api-routes.md`는 대상이 아니다.

- [ ] **6. `ai/prd.md` §3 "구현 현황"에 행 3개 추가** (CLAUDE.md 규칙 10)

  `FEED-014`·`FEED-016`·`FEED-017`을 새 행으로 추가하고 근거 칸에 **이 작업의 PR 번호**를 적는다. **`FEED-015`는 넣지 않는다 — 이슈 #531로 분리했다.**

  각 행에 판정과 함께 적을 것.
  - `FEED-014` — 완료. 매도 회고 서술의 수치를 프롬프트 수치 집합과 대조하고 어긋나면 템플릿으로 대체한다. **매도 회고에만 걸리며** 변동 카드·요약·브리핑은 무변경이다.
  - `FEED-016` — 완료(프로덕션 변경 없음). 표현 37개와 시스템 프롬프트가 무변경이고 `NarrativeValidatorTest`·프롬프트 골든 마스터가 수정 없이 통과하는 것이 근거다.
  - `FEED-017` — 완료(동작 무변경). 위반 시 경로가 그대로다 — 매도 회고는 템플릿, 요약·브리핑은 `NONE`. 엔드포인트·응답 필드·상태값이 늘지 않았고 `narrativeStatus`는 여전히 항상 `READY`다.
