package dev.felicityiris.llmsysmon.ml;

import java.time.Duration;

public enum DurationRange {
    ONE_HOUR(Duration.ofHours(1)),
    SIX_HOURS(Duration.ofHours(6)),
    TWELVE_HOURS(Duration.ofHours(12)),
    ONE_DAY(Duration.ofDays(1)),
    ONE_WEEK(Duration.ofDays(7));

    private final Duration duration;

    DurationRange(Duration duration) {
        this.duration = duration;
    }

    public Duration getDuration() {
        return duration;
    }
}
