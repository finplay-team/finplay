# specs — 기능 명세 (spec-kit 스타일)

기능 하나당 폴더 하나. 번호는 3자리 순번.

```
specs/
└── 001-member-auth/
    ├── spec.md      # 무엇을, 왜 (요구사항, 시나리오, 완료 조건)
    ├── plan.md      # 어떻게 (기술 설계, API 명세, 스키마)
    ├── tasks.md     # 작업 분해 체크리스트
    └── run-log.md   # 실행 로그 (implementer·reviewer가 자동 기록, /feature 진행 중에만 생김)
```

## 흐름

1. 기능 논의 후 `spec.md` 작성 — 구현 세부사항 없이 요구사항과 완료 조건만.
2. spec 합의 후 `plan.md` — 엔드포인트, 테이블 설계, 관련 ADR 링크.
3. `tasks.md`로 분해 후 이슈 생성 → 구현.
   - 항목 굵기: **커밋 1개 = 응집된 기능 조각** (엔티티+repository+마이그레이션 한 덩어리 등). 너무 잘게 쪼개면 /feature 루프의 에이전트 투입 오버헤드가 작업 시간을 지배한다. spec 하나당 3~7개 권장.

AI에게 시킬 때는 "specs/001-member-auth 진행해줘"처럼 폴더 단위로 지시한다.
템플릿은 `_template/`을 복사해서 시작한다.

## run-log.md — 실행 로그

/feature 루프에서 **implementer·reviewer만** 작업 종료 시 자동으로 남긴다 (tester·planner는 기록하지 않음 — 경량 시작 원칙, 필요해지면 확대). 사람이 직접 쓰는 파일이 아니다.

```md
# Run Log: 001-member-auth

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 22:05 | implementer | `.\gradlew.bat compileJava` | plan.md 구성요소 설계, ADR-0002 |
| 22:20 | reviewer(리뷰) | `git diff dev...HEAD` | conventions.md, ADR-0003 |

## 모니터링 (사람용 요약)
- 22:05 — Redis compose·Testcontainers 싱글턴 추가, 컴파일 통과.
- 22:20 — 리뷰 완료, 차단 0건, 머지 가능.
```

- 각 행·줄은 1줄. 장문 금지 (부담이 되면 경량화 원칙 위반).
- 다음 에이전트가 같은 spec에 재투입될 때 직전 판단 근거를 빠르게 참고하는 용도. `docs/context-router.md`의 "기능 구현" 행에서 참조하지 않는다 — 필요할 때만 훑어보는 보조 자료.
