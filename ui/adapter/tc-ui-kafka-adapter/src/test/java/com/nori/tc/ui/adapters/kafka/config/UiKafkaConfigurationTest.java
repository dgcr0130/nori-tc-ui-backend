package com.nori.tc.ui.adapters.kafka.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UiKafkaConfigurationTest {

    private static final String TRUSTSTORE_RESOURCE =
            "com/nori/tc/ui/adapters/kafka/config/UiKafkaConfigurationTest.class";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(UiKafkaConfiguration.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:9092",
                    "tc.ui.backend.kafka.gateway-events-topic=tc.ui.events.gateway",
                    "tc.ui.backend.kafka.business-events-topic=tc.ui.events.business",
                    "tc.ui.backend.kafka.commands-topic=tc.ui.commands",
                    "spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512",
                    "spring.kafka.properties.sasl.jaas.config=test-jaas",
                    "spring.kafka.ssl.trust-store-location=classpath:" + TRUSTSTORE_RESOURCE,
                    "spring.kafka.ssl.trust-store-password=test-password",
                    "spring.kafka.ssl.trust-store-type=PKCS12",
                    "spring.kafka.consumer.group-id=must-not-apply",
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "spring.kafka.consumer.enable-auto-commit=true",
                    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                    "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer");

    @Test
    void commonSecurityReachesDedicatedConsumerWithoutChangingConsumerPolicy() {
        runner.withPropertyValues("spring.kafka.properties.security.protocol=SASL_SSL")
                .run(context -> {
                    final ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                            context.getBean("uiCommandListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
                    final Map<String, Object> props = factory.getConsumerFactory().getConfigurationProperties();
                    assertSecurity(props, "SASL_SSL");
                    assertThat(props).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                            .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                            .doesNotContainKeys(ConsumerConfig.GROUP_ID_CONFIG, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
                    assertThat(factory.getContainerProperties().getAckMode())
                            .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
                    final DefaultErrorHandler handler = (DefaultErrorHandler)
                            ReflectionTestUtils.getField(factory, "commonErrorHandler");
                    assertThat(handler).isNotNull();
                    assertThat(handler.isAckAfterHandle()).isFalse();
                });
    }

    @Test
    void securityProtocolBindingAndConsumerMapPrecedenceArePreserved() {
        runner.withPropertyValues("spring.kafka.security.protocol=SASL_SSL")
                .run(context -> {
                    final ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                            context.getBean("uiCommandListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
                    assertSecurity(factory.getConsumerFactory().getConfigurationProperties(), "SASL_SSL");
                });
        runner.withPropertyValues(
                        "spring.kafka.security.protocol=SSL",
                        "spring.kafka.properties.security.protocol=SASL_SSL",
                        "spring.kafka.consumer.properties.security.protocol=SASL_PLAINTEXT")
                .run(context -> {
                    final ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                            context.getBean("uiCommandListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
                    assertSecurity(factory.getConsumerFactory().getConfigurationProperties(), "SASL_PLAINTEXT");
                });
    }

    private static void assertSecurity(final Map<String, Object> props, final String protocol) throws IOException {
        assertThat(props).containsEntry("security.protocol", protocol)
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry("sasl.jaas.config", "test-jaas")
                .containsEntry("ssl.truststore.location", new ClassPathResource(TRUSTSTORE_RESOURCE).getFile().getAbsolutePath())
                .containsEntry("ssl.truststore.password", "test-password")
                .containsEntry("ssl.truststore.type", "PKCS12");
    }
}
