package dev.felicityiris.llmsysmon.metrics.gpu;

import dev.felicityiris.llmsysmon.metrics.MetricCollector;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Collects real-time GPU utilisation and VRAM data via {@code nvidia-smi}.
 *
 * <p>On construction the collector probes whether {@code nvidia-smi} is
 * reachable.  If it is not (non-NVIDIA systems or missing driver tools),
 * every subsequent {@link #collect()} call returns {@link GpuMetrics#unavailable()}
 * instantly without blocking.
 *
 * <p>Each successful collection spawns one short-lived {@code nvidia-smi}
 * process and parses its CSV output.  Typical round-trip latency is
 * ~50–200 ms, which is well within the 1-second scheduler interval.
 */
public class GpuCollector implements MetricCollector<GpuMetrics> {

    private static final String[] QUERY_ARGS = {
            "nvidia-smi",
            "--query-gpu=name,utilization.gpu,memory.used,memory.total",
            "--format=csv,noheader,nounits"
    };

    private final boolean nvidiaAvailable;

    public GpuCollector() {
        this.nvidiaAvailable = probeNvidiaSmi();
    }

    /** Returns {@code true} if this machine has nvidia-smi and a reachable GPU. */
    public boolean isNvidiaAvailable() {
        return nvidiaAvailable;
    }

    @Override
    public GpuMetrics collect() {
        if (!nvidiaAvailable) {
            return GpuMetrics.unavailable();
        }

        try {
            Process process = new ProcessBuilder(QUERY_ARGS)
                    .redirectErrorStream(true)
                    .start();

            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                line = reader.readLine();
            }

            process.waitFor();

            if (line == null || line.isBlank()) {
                return GpuMetrics.unavailable();
            }

            return parseLine(line.trim());

        } catch (Exception e) {
            System.err.println("Failed to collect GPU metrics: " + e.getMessage());
            return GpuMetrics.unavailable();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Parses one CSV line produced by nvidia-smi.
     * Expected format: {@code name, util%, vramUsedMB, vramTotalMB}
     */
    private static GpuMetrics parseLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 4) {
            return GpuMetrics.unavailable();
        }

        String name      = parts[0].trim();
        double usage     = Double.parseDouble(parts[1].trim());
        long   vramUsed  = Long.parseLong(parts[2].trim());
        long   vramTotal = Long.parseLong(parts[3].trim());

        return new GpuMetrics(true, name, usage, vramUsed, vramTotal);
    }

    /** Quick probe: exit code 0 means nvidia-smi is present and functional. */
    private static boolean probeNvidiaSmi() {
        try {
            Process probe = new ProcessBuilder(
                    "nvidia-smi", "--query-gpu=name", "--format=csv,noheader"
            ).redirectErrorStream(true).start();

            // Drain stdout so the process doesn't block on a full pipe buffer
            try (InputStream is = probe.getInputStream()) {
                is.transferTo(OutputStream.nullOutputStream());
            }

            return probe.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
