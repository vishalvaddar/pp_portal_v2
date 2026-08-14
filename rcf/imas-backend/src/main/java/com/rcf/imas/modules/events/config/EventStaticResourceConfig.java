package com.rcf.imas.modules.events.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Static-serves pp.event_photos/pp.event_reports files at the SAME URL prefixes Node used
 *  (index.js:147-155: app.use("/uploads/events/photos", express.static(EVENT_PHOTOS_DIR)) / "/reports").
 *  GET-only in practice -- paired with the permitAll() matchers added to SecurityConfig in Task 1. */
@Configuration
public class EventStaticResourceConfig implements WebMvcConfigurer {

    private final String eventStoragePath;

    public EventStaticResourceConfig(@Value("${imas.event-storage-path}") String eventStoragePath) {
        this.eventStoragePath = eventStoragePath;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path base = Paths.get(eventStoragePath).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/events/photos/**")
                .addResourceLocations("file:" + base.resolve("photos") + "/");
        registry.addResourceHandler("/uploads/events/reports/**")
                .addResourceLocations("file:" + base.resolve("reports") + "/");
    }
}
