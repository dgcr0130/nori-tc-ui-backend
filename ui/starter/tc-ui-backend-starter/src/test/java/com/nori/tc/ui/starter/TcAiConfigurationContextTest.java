package com.nori.tc.ui.starter;

import com.nori.tc.ui.adapter.db.UiDbAdapterModuleConfiguration;
import com.nori.tc.ui.adapters.redis.config.UiRedisAdapterModuleConfiguration;
import com.nori.tc.ui.adapters.web.config.UiWebAdapterModuleConfiguration;
import com.nori.tc.ui.core.ai.TcAiReadPort;
import com.nori.tc.ui.core.ai.TcAiReadService;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

/** 네 소유 모듈의 명시적 import에서 AI 그래프를 모아 활성·비활성 등록을 확인합니다. */
class TcAiConfigurationContextTest {
    private static final List<String> AI_TYPES = List.of("TcAiDbReadAdapter", "TcAiReadService",
            "TcAiIdentityResolver", "TcAiToolConfiguration", "TcAiRedisReadAdapter");

    @Test
    void moduleImportsAssembleTheFourToolGraph() throws ClassNotFoundException {
        Class<?>[] imports = Stream.of(UiDbAdapterModuleConfiguration.class, UiCoreModuleConfiguration.class,
                        UiWebAdapterModuleConfiguration.class, UiRedisAdapterModuleConfiguration.class)
                .flatMap(module -> Arrays.stream(module.getAnnotation(Import.class).value()))
                .filter(type -> AI_TYPES.contains(type.getSimpleName())).toArray(Class<?>[]::new);
        assertThat(imports).extracting(Class::getSimpleName).containsExactlyInAnyOrderElementsOf(AI_TYPES);
        Class<?> resolver = Class.forName("com.nori.ai.integration.api.AiRequestIdentityResolver");
        Class<?> registration = Class.forName("com.nori.ai.integration.api.AiToolRegistration");
        var runner = new ApplicationContextRunner().withUserConfiguration(imports);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TcAiReadService.class));
        runner.withPropertyValues("nori.ai.integration.enabled=true").withInitializer(context -> {
            // 실제 읽기 adapter와 resolver의 저장소 경계만 대역으로 공급합니다.
            Arrays.stream(imports).filter(type -> type.getSimpleName().equals("TcAiDbReadAdapter")
                            || type.getSimpleName().equals("TcAiIdentityResolver")
                            || type.getSimpleName().equals("TcAiRedisReadAdapter"))
                    .flatMap(type -> Arrays.stream(type.getConstructors()[0].getParameterTypes()))
                    .filter(type -> !type.getSimpleName().equals("RedisTemplate"))
                    .distinct().forEach(type -> context.getBeanFactory().registerSingleton(type.getName(), Mockito.mock(type)));
            context.getBeanFactory().registerSingleton("dlqRedisTemplate", Mockito.mock(org.springframework.data.redis.core.RedisTemplate.class));
            context.getBeanFactory().registerSingleton("commLogRedisTemplate", Mockito.mock(org.springframework.data.redis.core.RedisTemplate.class));
            context.getBeanFactory().registerAlias("tools.jackson.databind.ObjectMapper", "uiRedisObjectMapper");
        }).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TcAiReadService.class).hasSingleBean(TcAiReadPort.class);
            assertThat(context).hasSingleBean(TcAiReadPort.RedisReads.class);
            assertThat(context.getBeansOfType(resolver)).hasSize(1);
            assertThat(context.getBeansOfType(registration)).hasSize(4);
        });
    }
}
