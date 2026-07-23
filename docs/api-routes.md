# API 라우트 지도

전체 엔드포인트를 한눈에 보는 레지스트리. **controller를 추가/변경하면 같은 커밋에서 이 문서를 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

상세 명세(요청/응답 스키마)는 앱 실행 후 Swagger UI에서 확인한다: `http://localhost:8080/swagger-ui.html`

## 라우트 목록

| Method | URL | 도메인 | 요약 | Spec |
|---|---|---|---|---|
| _(아직 없음)_ | | | | |

## 시스템 엔드포인트

| Method | URL | 요약 |
|---|---|---|
| GET | /actuator/health | 헬스체크 |
| GET | /swagger-ui.html | API 문서 (springdoc) |
| GET | /v3/api-docs | OpenAPI JSON |
