import com.google.protobuf.gradle.id

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
	id("com.google.protobuf") version "0.9.4"
}

group = "io.auxia"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// grpcVersion must match what grpc-spring-boot-starter ships (1.63.0) so that
// grpc-api and grpc-netty-shaded stay on the same version at runtime.
val grpcVersion = "1.63.0"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "3.25.3"
val coroutinesVersion = "1.9.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// gRPC + Protobuf
	// grpc-protobuf and grpc-core are already pulled in at 1.63.0 by the starter;
	// we only add what the starter doesn't include.
	implementation("net.devh:grpc-spring-boot-starter:3.1.0.RELEASE")
	implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
	implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")

	// Coroutines
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testRuntimeOnly("com.h2database:h2")
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc:$protobufVersion"
	}
	plugins {
		id("grpc") {
			artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
		}
		id("grpckt") {
			artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
		}
	}
	generateProtoTasks {
		all().forEach { task ->
			task.plugins {
				id("grpc")
				id("grpckt")
			}
			task.builtins {
				id("kotlin")
			}
		}
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
	annotation("net.devh.boot.grpc.server.service.GrpcService")
}

springBoot {
	mainClass.set("io.auxia.vellocell.VellocellApplicationKt")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.register<JavaExec>("runClient") {
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("io.auxia.vellocell.client.VeloCellClientKt")
	args = listOf(project.findProperty("username")?.toString() ?: "guest")
	standardInput = System.`in`
}
