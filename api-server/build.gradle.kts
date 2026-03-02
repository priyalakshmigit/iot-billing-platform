plugins {
	java
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.5"
}

description = "API server - REST endpoints and background sweeper job"

val grpcVersion = "1.72.0"
val protobufVersion = "4.30.2"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.grpc:grpc-netty-shaded:${grpcVersion}")
	implementation("io.grpc:grpc-protobuf:${grpcVersion}")
	implementation("io.grpc:grpc-stub:${grpcVersion}")
	implementation("com.google.protobuf:protobuf-java:${protobufVersion}")
	compileOnly("javax.annotation:javax.annotation-api:1.3.2")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	
	implementation("com.fasterxml.jackson.core:jackson-databind")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	
	implementation("redis.clients:jedis:5.0.1")
	implementation("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql:42.7.1")
	
	project(":common-lib").also { implementation(it) }

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc:${protobufVersion}"
	}
	plugins {
		create("grpc") {
			artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}"
		}
	}
	generateProtoTasks {
		all().configureEach {
			plugins {
				create("grpc")
			}
		}
	}
}

sourceSets {
	main {
		java {
			srcDir("build/generated/source/proto/main/java")
			srcDir("build/generated/source/proto/main/grpc")
		}
	}
}
