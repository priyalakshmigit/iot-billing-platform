FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

ARG GRADLE_VERSION=9.3.1
RUN apt-get update \
	&& apt-get install -y --no-install-recommends wget unzip \
	&& wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/gradle.zip \
	&& unzip -q /tmp/gradle.zip -d /opt \
	&& ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
	&& rm -rf /var/lib/apt/lists/* /tmp/gradle.zip

COPY . .
RUN gradle :event-consumer:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=builder /workspace/event-consumer/build/libs/*.jar /app/app.jar

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
