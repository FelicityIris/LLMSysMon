package dev.felicityiris.llmsysmon.ml;

import java.time.Instant;
import java.util.List;

/**
 * Output DTO produced by {@link LocalLlmRecommendationService}.
 *
 * <p>Has its own {@link Severity} enum (INFO/LOW/MEDIUM/HIGH/CRITICAL) that maps
 * cleanly onto the LLM response contract, and a helper that converts to
 * {@link RecommendationEngine.Recommendation} for backward-compatible display.</p>
 */
public final class LlmRecommendation {

    /** Qualitative severity levels used in the LLM response contract. */
    public enum Severity {
        INFO, LOW, MEDIUM, HIGH, CRITICAL;

        /** Maps to the nearest {@link RecommendationEngine.Severity} value. */
        public RecommendationEngine.Severity toEngineSeverity() {
            return switch (this) {
                case INFO     -> RecommendationEngine.Severity.INFO;
                case LOW      -> RecommendationEngine.Severity.INFO;
                case MEDIUM   -> RecommendationEngine.Severity.WARNING;
                case HIGH     -> RecommendationEngine.Severity.WARNING;
                case CRITICAL -> RecommendationEngine.Severity.CRITICAL;
            };
        }
    }

    /** Where this recommendation came from. */
    public enum Source { LLM, FALLBACK, PENDING }

    // ── Fields ───────────────────────────────────────────────────

    private final Source         source;
    private final Severity       severity;
    private final String         title;
    private final String         summary;
    private final String         reasoning;
    private final List<String>   actions;
    private final double         confidence;
    private final Instant        generatedAt;

    private LlmRecommendation(Builder b) {
        this.source      = b.source;
        this.severity    = b.severity;
        this.title       = b.title;
        this.summary     = b.summary;
        this.reasoning   = b.reasoning;
        this.actions     = b.actions != null ? List.copyOf(b.actions) : List.of();
        this.confidence  = b.confidence;
        this.generatedAt = b.generatedAt != null ? b.generatedAt : Instant.now();
    }

    // ── Factory helpers ──────────────────────────────────────────

    /** A placeholder returned before the first LLM call completes. */
    public static LlmRecommendation pending() {
        return new Builder()
                .source(Source.PENDING)
                .severity(Severity.INFO)
                .title("LLM: Initializing...")
                .summary("LLM recommendation engine is starting up.")
                .reasoning("")
                .actions(List.of())
                .confidence(0.0)
                .build();
    }

    // ── Accessors ────────────────────────────────────────────────

    public Source       getSource()      { return source; }
    public Severity     getSeverity()    { return severity; }
    public String       getTitle()       { return title; }
    public String       getSummary()     { return summary; }
    public String       getReasoning()   { return reasoning; }
    public List<String> getActions()     { return actions; }
    public double       getConfidence()  { return confidence; }
    public Instant      getGeneratedAt() { return generatedAt; }

    /**
     * Converts to a {@link RecommendationEngine.Recommendation} so the existing dashboard
     * display code (which reads {@code rec.summary()} / {@code rec.detail()}) works unchanged.
     */
    public RecommendationEngine.Recommendation toEngineRecommendation() {
        String detail = reasoning.isBlank() ? summary : reasoning;
        if (!actions.isEmpty()) {
            detail = detail + "\n→ " + String.join("\n→ ", actions);
        }
        return new RecommendationEngine.Recommendation(summary, detail, severity.toEngineSeverity());
    }

    // ── Builder ──────────────────────────────────────────────────

    public static final class Builder {
        private Source       source      = Source.LLM;
        private Severity     severity    = Severity.INFO;
        private String       title       = "";
        private String       summary     = "";
        private String       reasoning   = "";
        private List<String> actions     = List.of();
        private double       confidence  = 0.5;
        private Instant      generatedAt = null;

        public Builder source(Source v)        { this.source = v; return this; }
        public Builder severity(Severity v)    { this.severity = v; return this; }
        public Builder title(String v)         { this.title = v; return this; }
        public Builder summary(String v)       { this.summary = v; return this; }
        public Builder reasoning(String v)     { this.reasoning = v; return this; }
        public Builder actions(List<String> v) { this.actions = v; return this; }
        public Builder confidence(double v)    { this.confidence = v; return this; }
        public Builder generatedAt(Instant v)  { this.generatedAt = v; return this; }

        public LlmRecommendation build() { return new LlmRecommendation(this); }
    }
}
