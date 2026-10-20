package dev.felicityiris.llmsysmon.ml;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background service that periodically calls a local Ollama LLM (qwen3.5:2b by default)
 * to generate contextual system optimization recommendations.
 *
 * <p>Key design principles:
 * <ul>
 *   <li>One single-thread executor handles all scheduling — no runaway threads.</li>
 *   <li>{@link AtomicBoolean} guard prevents concurrent Ollama requests.</li>
 *   <li>Falls back to {@link RecommendationEngine} when LLM is unavailable.</li>
 *   <li>Logs each distinct failure type at most once per minute.</li>
 * </ul>
 */
public class LocalLlmRecommendationService {

    private static final Logger LOG = Logger.getLogger(LocalLlmRecommendationService.class.getName());

    // How much CPU/RAM delta (%) triggers an immediate refresh (with cooldown)
    private static final double SIGNIFICANT_CHANGE_DELTA = 15.0;
    // Minimum gap between early-trigger refreshes
    private static final long   EARLY_TRIGGER_COOLDOWN_MS = 8_000;
    // How old a completed response can be and still be accepted (stale guard)
    private static final long   STALE_RESPONSE_MAX_AGE_MS = 60_000;
    // Force refresh after 30 seconds even if system metrics appear identical
    private static final long   MAX_IDLE_REFRESH_MS = 30_000;

    private final LlmConfig               config;
    private final HttpClient              httpClient;
    private final Gson                    gson = new Gson();
    private final ScheduledExecutorService executor;

    private final AtomicBoolean isRequesting = new AtomicBoolean(false);
    private final AtomicLong    lastInferenceCompletedMs = new AtomicLong(0);
    private final AtomicLong    requestSequence = new AtomicLong(0);
    private final AtomicLong    lastAcceptedSequence = new AtomicLong(0);

    /** The snapshot/prediction pair queued for the next inference call. */
    private final AtomicReference<SnapshotPair> pendingPair = new AtomicReference<>();

    /** The pair of metrics used for the last successful inference. */
    private volatile SnapshotPair lastInferredPair = null;

    private volatile LlmRecommendation    latestRecommendation = LlmRecommendation.pending();
    private volatile Consumer<LlmRecommendation> listener      = null;

    // Rate-limited logging: track last time we logged each failure type
    private volatile long lastConnectionErrorLogMs = 0;
    private volatile long lastParseErrorLogMs      = 0;
    private volatile long lastTimeoutErrorLogMs     = 0;
    private static final long LOG_THROTTLE_MS      = 60_000;

    // ── Inner record ─────────────────────────────────────────────

    public record SnapshotPair(SystemSnapshot snapshot, PredictionResult prediction, Instant queuedAt) {}

    // ── Constructor ──────────────────────────────────────────────

    public LocalLlmRecommendationService(LlmConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-recommendation-svc");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /** Starts the periodic recommendation scheduler with fixed delay. */
    public void start() {
        executor.scheduleWithFixedDelay(
                this::attemptInference,
                1,                              // initial delay (seconds)
                config.getRefreshIntervalSeconds(),
                TimeUnit.SECONDS
        );
        LOG.info("[LLM] Recommendation service started (model=%s, interval=%ds)"
                .formatted(config.getModelName(), config.getRefreshIntervalSeconds()));
    }

    /** Graceful shutdown — drains in-flight requests within 5 seconds. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("[LLM] Recommendation service shut down.");
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Called on every 1-second telemetry tick. Non-blocking — stages the snapshot
     * for the scheduled 5-second inference window without triggering immediate requests.
     */
    public void submitForRecommendation(SystemSnapshot snapshot, PredictionResult prediction) {
        pendingPair.set(new SnapshotPair(snapshot, prediction, Instant.now()));
    }

    /** Returns the most recently completed recommendation (thread-safe). */
    public LlmRecommendation getLatestRecommendation() {
        return latestRecommendation;
    }

    /** Registers a callback invoked on the executor thread whenever a new recommendation is ready. */
    public void setRecommendationListener(Consumer<LlmRecommendation> listener) {
        this.listener = listener;
    }

    /** Returns true if an inference request is currently executing in the background. */
    public boolean isRequestInFlight() {
        return isRequesting.get();
    }

    /** Returns the timestamp in milliseconds when the last inference attempt completed. */
    public long getLastInferenceCompletedMs() {
        return lastInferenceCompletedMs.get();
    }

    // ── Internal inference ────────────────────────────────────────

    /**
     * Executes an inference attempt if not already requesting and the minimum interval has elapsed.
     */
    public void attemptInference() {
        long now = System.currentTimeMillis();
        long minIntervalMs = config.getRefreshIntervalSeconds() * 1000L;

        // Enforce configured interval between consecutive inferences
        if (lastInferenceCompletedMs.get() > 0 && (now - lastInferenceCompletedMs.get()) < minIntervalMs) {
            return;
        }

        // Prevent overlapping requests
        if (!isRequesting.compareAndSet(false, true)) {
            return;
        }

        SnapshotPair pair = pendingPair.get();
        if (pair == null) {
            isRequesting.set(false);
            return;
        }

        // Avoid unnecessary repeated requests if system metrics haven't meaningfully changed
        if (latestRecommendation != null && latestRecommendation.getSource() == LlmRecommendation.Source.LLM) {
            long timeSinceLast = now - lastInferenceCompletedMs.get();
            if (lastInferredPair != null && !hasSystemStateMeaningfullyChanged(pair, lastInferredPair)) {
                if (timeSinceLast < MAX_IDLE_REFRESH_MS) {
                    isRequesting.set(false);
                    return;
                }
            }
        }

        long thisSequence = requestSequence.incrementAndGet();

        try {
            String prompt  = buildPrompt(pair.snapshot(), pair.prediction());
            String rawJson = callOllama(prompt);
            LlmRecommendation rec = parseResponse(rawJson);
            validateResponse(rec);

            // Stale response guard: reject if our snapshot is too old
            long ageMs = Duration.between(pair.queuedAt(), Instant.now()).toMillis();
            if (ageMs > STALE_RESPONSE_MAX_AGE_MS) {
                LOG.fine("[LLM] Discarding stale response (age=%dms)".formatted(ageMs));
                return;
            }

            // Sequence guard: reject if a newer response was already accepted
            if (thisSequence < lastAcceptedSequence.get()) {
                LOG.fine("[LLM] Discarding older response (seq=%d < last=%d)".formatted(thisSequence, lastAcceptedSequence.get()));
                return;
            }
            lastAcceptedSequence.set(thisSequence);
            lastInferredPair = pair;

            onResult(rec);

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            rateLimitedLog(Level.WARNING, "connection", "[LLM] Ollama not reachable (%s).".formatted(e.getClass().getSimpleName()));
            handleInferenceFailure(pair);
        } catch (java.net.SocketTimeoutException | java.util.concurrent.TimeoutException e) {
            rateLimitedLog(Level.WARNING, "timeout", "[LLM] Request timed out.");
            handleInferenceFailure(pair);
        } catch (LlmParseException e) {
            rateLimitedLog(Level.WARNING, "parse", "[LLM] Response parse error: %s.".formatted(e.getMessage()));
            handleInferenceFailure(pair);
        } catch (Exception e) {
            rateLimitedLog(Level.WARNING, "connection", "[LLM] Unexpected error: %s.".formatted(e.getMessage()));
            handleInferenceFailure(pair);
        } finally {
            lastInferenceCompletedMs.set(System.currentTimeMillis());
            isRequesting.set(false);
        }
    }

    private void handleInferenceFailure(SnapshotPair pair) {
        // Retain previous valid recommendation if one is already active
        if (latestRecommendation != null && latestRecommendation.getSource() == LlmRecommendation.Source.LLM) {
            return;
        }
        // Fall back to rule-based heuristics if no valid LLM recommendation exists
        onResult(buildFallback(pair));
    }

    private void onResult(LlmRecommendation rec) {
        latestRecommendation = rec;
        Consumer<LlmRecommendation> cb = listener;
        if (cb != null) {
            cb.accept(rec);
        }
    }

    /**
     * Determines whether system metrics or forecasts changed enough to justify a new LLM inference.
     */
    public boolean hasSystemStateMeaningfullyChanged(SnapshotPair current, SnapshotPair last) {
        if (current == null || last == null) return true;
        SystemSnapshot s1 = current.snapshot();
        SystemSnapshot s2 = last.snapshot();
        if (s1 == null || s2 == null) return true;

        double cpuDiff = Math.abs(s1.getCpu().getUsage() - s2.getCpu().getUsage());
        double memDiff = Math.abs(s1.getMemory().getUsage() - s2.getMemory().getUsage());
        double swapDiff = Math.abs(s1.getMemory().getSwapUsage() - s2.getMemory().getSwapUsage());
        double gpuDiff = 0.0;
        if (s1.getGpu() != null && s2.getGpu() != null && s1.getGpu().isAvailable() && s2.getGpu().isAvailable()) {
            gpuDiff = Math.abs(s1.getGpu().getUsage() - s2.getGpu().getUsage());
        }

        PredictionResult p1 = current.prediction();
        PredictionResult p2 = last.prediction();
        double predCpuDiff = 0.0;
        if (p1 != null && p2 != null && p1.available() && p2.available()) {
            predCpuDiff = Math.abs(p1.predictedCpu() - p2.predictedCpu());
        }

        return cpuDiff >= 4.0 || memDiff >= 3.0 || swapDiff >= 2.0 || gpuDiff >= 5.0 || predCpuDiff >= 4.0;
    }

    // ── Prompt construction ───────────────────────────────────────

    private String buildPrompt(SystemSnapshot snapshot, PredictionResult prediction) {
        StringBuilder sb = new StringBuilder();

        // Current metrics
        if (snapshot != null) {
            double cpu    = snapshot.getCpu().getUsage();
            double mem    = snapshot.getMemory().getUsage();
            double swap   = snapshot.getMemory().getSwapUsage();
            double gpu    = snapshot.getGpu() != null ? snapshot.getGpu().getUsage() : 0.0;
            boolean gpuOk = snapshot.getGpu() != null && snapshot.getGpu().isAvailable();

            sb.append("Current system metrics:\n");
            sb.append("  CPU: %.1f%%\n".formatted(cpu));
            sb.append("  RAM: %.1f%%\n".formatted(mem));
            sb.append("  Swap: %.1f%%\n".formatted(swap));
            if (gpuOk) sb.append("  GPU: %.1f%%\n".formatted(gpu));
        }

        // Predicted metrics (5-second horizon)
        if (prediction != null && prediction.available()) {
            sb.append("ML forecast (5s ahead):\n");
            sb.append("  CPU: %.1f%%\n".formatted(prediction.predictedCpu()));
            sb.append("  RAM: %.1f%%\n".formatted(prediction.predictedMemory()));
            sb.append("  Swap: %.1f%%\n".formatted(prediction.predictedSwap()));
            if (prediction.predictedGpu() > 0) {
                sb.append("  GPU: %.1f%%\n".formatted(prediction.predictedGpu()));
            }
        }

        sb.append("""

Respond ONLY with a JSON object in this exact format — no markdown, no explanation, nothing else:
{
  "severity": "INFO|LOW|MEDIUM|HIGH|CRITICAL",
  "title": "<short title, ≤10 words>",
  "summary": "<one sentence recommendation, ≤25 words>",
  "reasoning": "<brief explanation, ≤30 words>",
  "actions": ["<action 1>", "<action 2>"],
  "confidence": <0.0–1.0>
}
""");

        return sb.toString();
    }

    // ── Ollama HTTP call ──────────────────────────────────────────

    public String callOllama(String userPrompt) throws Exception {
        String systemPrompt = """
                You are a conservative Windows system performance advisor.
                Analyze real-time CPU, RAM, Swap, and GPU metrics from LLMSysMon.
                Give concise, safe, actionable advice. Never suggest reboots or data deletion.
                Respond ONLY with the requested JSON object — no extra text.""";

        String body = gson.toJson(new OllamaRequest(
                config.getModelName(),
                false,
                false,
                "json",
                new OllamaOptions(config.getMaxTokens()),
                List.of(
                        new OllamaMessage("system", systemPrompt),
                        new OllamaMessage("user",   userPrompt)
                )
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/api/chat"))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new LlmParseException("HTTP %d from Ollama".formatted(response.statusCode()));
        }
        return response.body();
    }

    // ── Response parsing ──────────────────────────────────────────

    public LlmRecommendation parseResponse(String ollamaJson) throws LlmParseException {
        try {
            // Ollama /api/chat response: {"message": {"content": "..."}, ...}
            JsonObject root    = JsonParser.parseString(ollamaJson).getAsJsonObject();
            String     content = root.getAsJsonObject("message").get("content").getAsString().trim();

            // Strip any accidental markdown fences the model might add
            if (content.startsWith("```")) {
                content = content.replaceAll("```[a-zA-Z]*\\n?", "").replaceAll("```", "").trim();
            }

            // Attempt to extract first JSON object if model added prose
            int braceStart = content.indexOf('{');
            int braceEnd   = content.lastIndexOf('}');
            if (braceStart < 0 || braceEnd < 0 || braceEnd <= braceStart) {
                throw new LlmParseException("No JSON object found in response content");
            }
            content = content.substring(braceStart, braceEnd + 1);

            JsonObject rec = JsonParser.parseString(content).getAsJsonObject();

            String severityStr = getStringField(rec, "severity");
            LlmRecommendation.Severity severity;
            try {
                severity = LlmRecommendation.Severity.valueOf(severityStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new LlmParseException("Unknown severity: " + severityStr);
            }

            String title    = getStringField(rec, "title");
            String summary  = getStringField(rec, "summary");
            String reasoning = rec.has("reasoning") ? rec.get("reasoning").getAsString() : "";

            List<String> actions = new ArrayList<>();
            if (rec.has("actions") && rec.get("actions").isJsonArray()) {
                for (var el : rec.getAsJsonArray("actions")) {
                    actions.add(el.getAsString());
                }
            }

            double confidence = 0.5;
            if (rec.has("confidence")) {
                confidence = rec.get("confidence").getAsDouble();
            }

            return new LlmRecommendation.Builder()
                    .source(LlmRecommendation.Source.LLM)
                    .severity(severity)
                    .title(title)
                    .summary(summary)
                    .reasoning(reasoning)
                    .actions(actions)
                    .confidence(Math.max(0.0, Math.min(1.0, confidence)))
                    .generatedAt(Instant.now())
                    .build();

        } catch (LlmParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmParseException("Failed to parse LLM response: " + e.getMessage());
        }
    }

    private void validateResponse(LlmRecommendation rec) throws LlmParseException {
        if (rec.getTitle().isBlank())   throw new LlmParseException("title is blank");
        if (rec.getSummary().isBlank()) throw new LlmParseException("summary is blank");
        if (rec.getActions().size() > 5) throw new LlmParseException("too many actions: " + rec.getActions().size());
    }

    // ── Fallback ──────────────────────────────────────────────────

    private LlmRecommendation buildFallback(SnapshotPair pair) {
        if (!config.isFallbackEnabled() || pair == null || pair.prediction() == null) {
            return new LlmRecommendation.Builder()
                    .source(LlmRecommendation.Source.FALLBACK)
                    .severity(LlmRecommendation.Severity.INFO)
                    .title("LLM Offline")
                    .summary("Ollama is not available. Rule-based guidance shown.")
                    .actions(List.of())
                    .confidence(0.0)
                    .build();
        }

        PredictionResult pred = pair.prediction();
        List<RecommendationEngine.Recommendation> recs = RecommendationEngine.generate(
                pred.currentCpu(),    pred.predictedCpu(),
                pred.currentMemory(), pred.predictedMemory(),
                pred.currentSwap(),   pred.predictedSwap(),
                pred.currentGpu(),    pred.predictedGpu()
        );

        if (recs.isEmpty()) {
            recs = List.of(new RecommendationEngine.Recommendation(
                    "System is running optimally.", "", RecommendationEngine.Severity.OPTIMAL));
        }

        RecommendationEngine.Recommendation top = recs.get(0);
        LlmRecommendation.Severity sev = switch (top.severity()) {
            case CRITICAL -> LlmRecommendation.Severity.CRITICAL;
            case WARNING  -> LlmRecommendation.Severity.HIGH;
            case INFO     -> LlmRecommendation.Severity.LOW;
            default       -> LlmRecommendation.Severity.INFO;
        };

        return new LlmRecommendation.Builder()
                .source(LlmRecommendation.Source.FALLBACK)
                .severity(sev)
                .title("[Rules] " + top.summary())
                .summary(top.summary())
                .reasoning(top.detail())
                .actions(List.of())
                .confidence(0.0)
                .generatedAt(Instant.now())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String getStringField(JsonObject obj, String key) throws LlmParseException {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new LlmParseException("Missing field: " + key);
        }
        return obj.get(key).getAsString();
    }

    private void rateLimitedLog(Level level, String type, String msg) {
        long now = System.currentTimeMillis();
        boolean shouldLog = switch (type) {
            case "connection" -> (now - lastConnectionErrorLogMs) >= LOG_THROTTLE_MS;
            case "parse"      -> (now - lastParseErrorLogMs)      >= LOG_THROTTLE_MS;
            case "timeout"    -> (now - lastTimeoutErrorLogMs)    >= LOG_THROTTLE_MS;
            default           -> true;
        };
        if (shouldLog) {
            LOG.log(level, msg);
            switch (type) {
                case "connection" -> lastConnectionErrorLogMs = now;
                case "parse"      -> lastParseErrorLogMs      = now;
                case "timeout"    -> lastTimeoutErrorLogMs    = now;
            }
        }
    }

    // ── Ollama request DTOs (Gson-serialized) ─────────────────────
    private record OllamaRequest(String model, boolean stream, boolean think, String format, OllamaOptions options, List<OllamaMessage> messages) {}
    private record OllamaOptions(int num_predict) {}
    private record OllamaMessage(String role, String content) {}

    /** Thrown when the LLM response cannot be parsed or validated. */
    public static class LlmParseException extends Exception {
        LlmParseException(String msg) { super(msg); }
    }
}
