import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// 루트는 공통 설정만 담당한다. 플러그인은 여기서 버전만 고정하고 각 모듈이 필요한 것만 적용한다. (ADR 0002)
plugins {
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.kotlin.spring) apply false
	alias(libs.plugins.spring.boot) apply false
	alias(libs.plugins.spring.dependency.management) apply false
}

group = "com.team376"
version = "0.0.1-SNAPSHOT"

// 버전 카탈로그 접근자(libs)는 루트 스크립트 스코프에서만 보인다.
// subprojects 블록의 수신자는 하위 프로젝트라 catalog extension 이 없으므로 여기서 미리 꺼내 둔다.
val jdkVersion = libs.versions.jdk.get().toInt()
val kotlinReflect = libs.kotlin.reflect
val kotlinTestJunit5 = libs.kotlin.test.junit5
val junitPlatformLauncher = libs.junit.platform.launcher

subprojects {
	// :apps, :libs 는 모듈을 담는 컨테이너일 뿐 빌드 스크립트가 없다. 공통 설정 대상에서 뺀다.
	if (!buildFile.exists()) return@subprojects

	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "org.jetbrains.kotlin.plugin.spring")
	apply(plugin = "io.spring.dependency-management")

	group = rootProject.group
	version = rootProject.version

	repositories {
		mavenCentral()
	}

	extensions.configure<JavaPluginExtension> {
		toolchain {
			languageVersion = JavaLanguageVersion.of(jdkVersion)
		}
	}

	extensions.configure<KotlinJvmProjectExtension> {
		compilerOptions {
			freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
		}
	}

	// 애플리케이션 모듈은 Spring Boot 플러그인이 BOM 을 가져오지만, 라이브러리 모듈에는 없다.
	// 두 모듈이 같은 버전을 쓰도록 공통 설정에서 BOM 을 명시적으로 import 한다.
	extensions.configure<DependencyManagementExtension> {
		imports {
			mavenBom(SpringBootPlugin.BOM_COORDINATES)
		}
	}

	dependencies {
		add("implementation", kotlinReflect)
		add("testImplementation", kotlinTestJunit5)
		add("testRuntimeOnly", junitPlatformLauncher)
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
	}
}
