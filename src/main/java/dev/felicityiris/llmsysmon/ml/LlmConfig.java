package dev.felicityiris.llmsysmon.ml;

/**
 * Immutable configuration for the local LLM recommendation service.
 * All fields have sensible defaults targeting qwen3.5:2b via Ollama on localhost.
 */
public final class LlmConfig {

    private final String baseUrl;
    private final String modelName;
    private final int    refreshIntervalSeconds;
    private final int    requestTimeoutSeconds;
    private final int    maxTokens;
    private final boolean fallbackEnabled;

    private LlmConfig(Builder b) {
        this.baseUrl                = b.baseUrl;
        this.modelName              = b.modelName;
        this.refreshIntervalSeconds = b.refreshIntervalSeconds;
        this.requestTimeoutSeconds  = b.requestTimeoutSeconds;
        this.maxTokens              = b.maxTokens;
        this.fallbackEnabled        = b.fallbackEnabled;
    }

    /** Creates a config with all defaults. */
    public static LlmConfig defaults() {
        return new Builder().build();
    }

    public String  getBaseUrl()                { return baseUrl; }
    public String  getModelName()              { return modelName; }
    public int     getRefreshIntervalSeconds() { return refreshIntervalSeconds; }
    public int     getRequestTimeoutSeconds()  { return requestTimeoutSeconds; }
    public int     getMaxTokens()              { return maxTokens; }
    public boolean isFallbackEnabled()         { return fallbackEnabled; }

    // ── Builder ──────────────────────────────────────────────────

    public static final class Builder {
        private String  baseUrl                = "http://localhost:11434";
        private String  modelName              = "qwen3.5:2b";
        private int     refreshIntervalSeconds = 5;
        private int     requestTimeoutSeconds  = 20;
        private int     maxTokens              = 300;
        private boolean fallbackEnabled        = true;

        public Builder baseUrl(String v)                { this.baseUrl = v; return this; }
        public Builder modelName(String v)              { this.modelName = v; return this; }
        public Builder refreshIntervalSeconds(int v)    { this.refreshIntervalSeconds = v; return this; }
        public Builder requestTimeoutSeconds(int v)     { this.requestTimeoutSeconds = v; return this; }
        public Builder maxTokens(int v)                 { this.maxTokens = v; return this; }
        public Builder fallbackEnabled(boolean v)       { this.fallbackEnabled = v; return this; }

        public LlmConfig build() { return new LlmConfig(this); }
    }
}
