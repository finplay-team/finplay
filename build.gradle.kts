plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "8.0.0"
	id("com.github.spotbugs") version "6.5.9"
}

group = "com.finplay"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-mysql")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("com.mysql:mysql-connector-j")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-mysql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
	}
}

// 커버리지 게이트: 라인 커버리지 40% 미만이면 빌드 실패 (진입점 클래스 제외). 초기 경량 시작값 — 지표 보고 상향 예정.
tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	classDirectories.setFrom(
		files(classDirectories.files.map { fileTree(it) { exclude("**/*Application*") } }),
	)
	violationRules {
		rule {
			limit {
				counter = "LINE"
				value = "COVEREDRATIO"
				minimum = "0.40".toBigDecimal()
			}
		}
	}
}

// 정적 버그 탐지 (SpotBugs). 테스트 코드는 분석 대상에서 제외.
spotbugs {
	excludeFilter = file("config/spotbugs/exclude.xml")
}

tasks.spotbugsMain {
	reports.create("html") { required = true }
}

tasks.spotbugsTest {
	enabled = false
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
	java {
		target("src/**/*.java")
		// NAVER 자바 스타일 (팀 노션 확정) — 빌드 게이트는 Spotless로 유지하고 스타일만 교체
		eclipse().configFile("config/naver-eclipse-formatter.xml")
		removeUnusedImports()
	}
}
