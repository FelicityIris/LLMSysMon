package dev.felicityiris.llmsysmon.ml;

import dev.felicityiris.llmsysmon.database.MetricRepository;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DatasetExporter {
    private final MetricRepository metricRepository;

    public DatasetExporter(MetricRepository metricRepository) {
        this.metricRepository = metricRepository;
    }

    private void writeSnapshots(List<SystemSnapshot> snapshots, Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write("timestamp,cpu_usage,memory_usage,swap_usage,gpu_usage");
            writer.newLine();

            for (SystemSnapshot snapshot : snapshots) {
                double gpuUsage = (snapshot.getGpu() != null && snapshot.getGpu().isAvailable())
                        ? snapshot.getGpu().getUsage()
                        : 0.0;

                writer.write(String.format(
                        Locale.US,
                        "%s,%.4f,%.4f,%.4f,%.4f",
                        snapshot.getTimestamp(),
                        snapshot.getCpu().getUsage(),
                        snapshot.getMemory().getUsage(),
                        snapshot.getMemory().getSwapUsage(),
                        gpuUsage
                ));

                writer.newLine();
            }
        }
    }

    public void export(Instant start, Instant end, Path outputPath) throws IOException {
        List<SystemSnapshot> snapshots = metricRepository.findBetween(start, end);
        writeSnapshots(snapshots, outputPath);
        System.out.println("Exported " + snapshots.size() + " samples to " + outputPath);
    }

    public void exportAll(Path outputPath) throws IOException {
        export(Instant.EPOCH, Instant.now(), outputPath);
    }

    public void exportLast(DurationRange range, Path outputPath) throws IOException {
        Instant end = Instant.now();
        Instant start = end.minus(range.getDuration());
        export(start, end, outputPath);
    }

    /**
     * Fast, lightweight export of only the most recent N samples (chronologically ordered).
     * Used for low-latency 1-second inference cycles without reading/writing the entire database.
     */
    public void exportRecent(int limit, Path outputPath) throws IOException {
        List<SystemSnapshot> recentDesc = metricRepository.findRecent(limit);
        List<SystemSnapshot> chronological = new ArrayList<>(recentDesc);
        Collections.reverse(chronological);
        writeSnapshots(chronological, outputPath);
    }
}
