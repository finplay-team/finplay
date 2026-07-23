---
name: review-pr
description: PR 하나를 서브에이전트들로 병렬 리뷰하고 결과를 gh pr review로 게시한다. 사용법: /review-pr 12
---

# /review-pr — PR 리뷰 오케스트레이션

당신(메인 세션)은 오케스트레이터다. 직접 리뷰하지 않고 서브에이전트에게 위임한 뒤 결과를 종합해 게시한다. API 키 불필요 — 이 세션(구독)에서 실행된다.

## 절차

1. `gh pr view <번호>`로 PR 제목/설명 확인, `gh pr checkout <번호>`로 체크아웃. (원격이 없거나 번호 미지정이면 현재 브랜치 vs dev 로컬 diff로 대체한다.)
2. 서브에이전트 **병렬** 투입. packet은 최소로: PR 번호, diff 범위(`git diff dev...HEAD`), 관련 spec 경로만 전달한다. 대화 히스토리나 PR 논의 요약은 전달하지 않는다.
   - **reviewer (리뷰 모드)** — 범위: `git diff dev...HEAD`. 컨벤션/ADR/문서 동기화/테스트 누락 점검.
   - **tester (빌드 검증)** — `.\gradlew.bat build` 실행 + 실패 분석 (테스트 작성은 지시하지 않는다).
   - **reviewer (QA 모드)** — 변경에 controller/API가 포함된 경우에만, 리뷰 모드와 별도 인스턴스로 투입. 해당 spec 폴더 경로를 전달.
3. 결과 종합. 판정 기준.
   - 승인 의견: 리뷰 모드 차단 0건 AND 빌드 통과 AND QA FAIL 0건
   - 수정 요청 의견: 그 외
4. 게시한다.
   ```
   gh pr review <번호> --comment --body "<종합 리뷰>"
   ```
   본문 구성: 판정 → 차단 사항(있으면) → 권장 사항 → 빌드/QA 결과 요약. **자동 승인(`--approve`)은 하지 않는다** — 최종 승인 클릭은 사람이 한다 (ADR-0005).
5. 체크아웃했던 브랜치를 원래 브랜치로 되돌린다.

## 준자동 모드 (선택)

리뷰 담당자가 세션을 켜둘 수 있으면: `/loop 30m` + "열린 PR 중 리뷰 안 된 것을 찾아 /review-pr 실행"으로 폴링할 수 있다. 이미 리뷰 코멘트를 단 PR은 건너뛴다 (`gh pr view --json reviews`로 확인).
