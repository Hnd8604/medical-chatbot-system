package com.medicalchatbot.backend.support;

import java.time.ZoneId;
import java.util.List;

import com.medicalchatbot.backend.config.properties.AppProperties;

/** Configuration property records for unit tests. */
public final class TestProperties {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private TestProperties() {
    }

    public static AppProperties app() {
        return app(VIETNAM);
    }

    public static AppProperties app(ZoneId timezone) {
        return new AppProperties(timezone, new AppProperties.Cors(List.of("http://localhost:5174")),
                new AppProperties.RateLimit(10));
    }
}
