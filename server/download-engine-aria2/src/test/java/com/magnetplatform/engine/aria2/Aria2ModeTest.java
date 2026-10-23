package com.magnetplatform.engine.aria2;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class Aria2ModeTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MockAria2Engine.class);

    @Test
    void disabledRpcDoesNotEnableFakeDownloads() {
        context.run(app -> assertThat(app).doesNotHaveBean(MockAria2Engine.class));
    }

    @Test
    void mockRequiresExplicitTestConfiguration() {
        context.withPropertyValues("magnet.engine.aria2.mock.enabled=true")
                .run(app -> assertThat(app).hasSingleBean(MockAria2Engine.class));
    }
}
