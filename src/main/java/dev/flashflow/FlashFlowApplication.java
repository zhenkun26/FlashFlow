package dev.flashflow;

import dev.flashflow.shared.config.FlashFlowProperties;
import dev.flashflow.shared.config.MessagingProperties;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({
        "dev.flashflow.ordering.persistence",
        "dev.flashflow.inventory.persistence",
        "dev.flashflow.payment.persistence",
        "dev.flashflow.messaging.persistence",
        "dev.flashflow.admission.persistence",
        "dev.flashflow.verification.persistence"
})
@EnableConfigurationProperties({FlashFlowProperties.class, MessagingProperties.class})
public class FlashFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashFlowApplication.class, args);
    }

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }
}
