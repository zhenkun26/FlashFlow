package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MessagingBoundaryTest {
    @Test
    void v21HasNoLiveRocketMqDependencyOrAsyncController() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String controllers;
        try (var paths = Files.walk(Path.of("src/main/java/dev/flashflow"))) {
            controllers = paths.filter(path -> path.toString().endsWith("Controller.java"))
                    .map(path -> {
                        try { return Files.readString(path); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                    }).reduce("", String::concat);
        }
        assertThat(pom).doesNotContain("org.apache.rocketmq");
        assertThat(controllers).doesNotContain("/api/v2/orders");
    }
}
