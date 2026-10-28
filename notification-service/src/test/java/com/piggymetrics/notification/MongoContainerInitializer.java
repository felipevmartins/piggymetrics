package com.piggymetrics.notification;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts one MongoDB container per test JVM and points Spring Data at it. It replaces the embedded Flapdoodle
 * server, which Spring Boot 3 no longer auto-configures. The image is set by {@code piggymetrics.test.mongo-image}.
 * Registered through {@code META-INF/spring.factories} so JUnit 4 and slice tests share it; Ryuk removes the container
 * when the JVM exits.
 */
public class MongoContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final GenericContainer<?> MONGO = new GenericContainer<>(
			DockerImageName.parse(System.getProperty("piggymetrics.test.mongo-image", "mongo:8.0")))
			.withExposedPorts(27017)
			.waitingFor(Wait.forListeningPort());

	private static synchronized String uri() {
		if (!MONGO.isRunning()) {
			MONGO.start();
		}
		return "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/piggymetrics";
	}

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TestPropertyValues.of("spring.mongodb.uri=" + uri()).applyTo(context);
	}
}
