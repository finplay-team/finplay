# Spec: 프로젝트 기반 잔여 작업 (Redis · QueryDSL · 공통 오류 · Clock)

> PRD 근거: `docs/prd.md` §7 기술 계획, §8 태스크 1. 요구사항 ID 없음 (기술 기반 작업).
> 하네스 셋팅에서 이미 완료된 항목(MySQL·Testcontainers·Flyway·compose·프로필·Spotless·JaCoCo)은 제외한 **잔여분만** 다룬다 (`checklist.md` 참조).

## 개요

기능 개발(002~008)이 공통으로 의존하는 기반을 완성한다. 시세 저장용 Redis, 목록 조회용 QueryDSL, PRD 5장 공통 오류 체계, 시간 의존 테스트를 위한 주입 가능한 Clock이 대상이다.

## 사용자 시나리오

- 개발자는 `./gradlew bootRun`으로 MySQL·Redis가 함께 기동된 로컬 환경을 얻는다.
- 개발자는 Testcontainers로 Redis 연동 테스트를 실행할 수 있다.
- 클라이언트는 어떤 오류든 `{"error":{code,message,requestId}}` 단일 포맷으로 받는다.
- 테스트 작성자는 Clock을 주입해 개장·장중·마감 등 시간 조건을 재현할 수 있다.

## 요구사항

- [ ] compose.yaml에 Redis 서비스가 추가되고, 앱이 spring-boot-docker-compose로 자동 연결된다.
- [ ] compose.yaml에 Kafka 컨테이너가 포함되되, 앱 기동·테스트 성공 조건에서 제외된다 (PRD §7 — 2차 준비용, 1차 업무 코드 미사용).
- [ ] Testcontainers Redis 컨테이너가 MySQL과 같은 공유 싱글턴 방식으로 제공된다.
- [ ] QueryDSL이 설정되고 Q클래스가 컴파일된다.
- [ ] PRD 5장 공통 오류표의 코드 전부가 ErrorCode enum으로 정의된다.
- [ ] 비즈니스 예외·검증 실패·인증 실패가 전역 예외 핸들러를 거쳐 `{"error":{code,message,requestId}}` 포맷으로 응답된다.
- [ ] 모든 요청에 requestId가 부여되고 오류 응답에 포함된다.
- [ ] KST 기준 주입 가능한 Clock 빈이 제공되고, 테스트에서 고정 Clock으로 대체할 수 있다.

## 비즈니스 규칙

- 오류 응답 포맷은 예외 없이 `{"error":{code,message,requestId}}` 하나다 — 도메인별 변형 금지.
- 에러 코드는 PRD §5 표에 있는 것만 쓴다. 새 코드가 필요하면 PRD 후속 결정으로 추가한다.
- 시간을 읽는 코드는 `LocalDateTime.now()` 같은 직접 호출 금지 — 반드시 주입된 Clock을 쓴다.

## 범위 제외

- 인증·인가 규칙 자체 (002에서 Security 설정과 함께 구현. 여기서는 오류 포맷만).
- Redis에 저장할 시세 키 설계 (003 범위).
- Kafka 업무 코드 일체 (2차 — PRD C-001).
- 분산락·다중 인스턴스 대비 (2차 — PRD §7 트랜잭션 경계).

## 완료 조건

- [ ] `./gradlew bootRun` 시 MySQL·Redis가 자동 기동되고 헬스체크가 통과한다.
- [ ] Redis Testcontainers 연동 통합 테스트가 통과한다.
- [ ] 존재하지 않는 URL·검증 실패·비즈니스 예외 각각이 공통 오류 포맷으로 응답되는 테스트가 통과한다.
- [ ] 고정 Clock을 주입한 테스트가 시간 조건 분기를 재현한다.
- [ ] `./gradlew build` 전체 통과 (Kafka 컨테이너 없이도 통과).
