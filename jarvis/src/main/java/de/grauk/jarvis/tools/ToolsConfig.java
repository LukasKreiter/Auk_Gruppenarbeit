package de.grauk.jarvis.tools;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Boot konfiguriert einen TaskScheduler nur mit @EnableScheduling; für Timer definieren wir einen eigenen. */
@Configuration
public class ToolsConfig {

    @Bean
    public ThreadPoolTaskScheduler toolTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("jarvis-timer-");
        scheduler.setDaemon(true);
        return scheduler;
    }
}
