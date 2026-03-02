plugins {
	java
}

allprojects {
	group = "com.skylo.iot"
	version = "0.0.1-SNAPSHOT"

	repositories {
		mavenCentral()
	}
}

subprojects {
	apply(plugin = "java")

	java {
		toolchain {
			languageVersion = JavaLanguageVersion.of(25)
		}
	}
}
