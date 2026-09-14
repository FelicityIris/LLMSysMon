package dev.felicityiris.llmsysmon.ml;

import dev.felicityiris.llmsysmon.database.MetricRepository;
import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public class DatasetExporter {
    private final MetricRepository metricRepository;

    public DatasetExporter(MetricRepository metricRepository) {
        this.metricRepository = metricRepository;
    }

    public void export(Instant start, Instant end, Path outputPath) throws IOException {
        List<SystemSnapshot> snapshots = metricRepository.findBetween(start, end);

        Path parent = outputPath.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write("timestamp,cpu_usage,memory_usage,swap_usage");
            writer.newLine();

            for (SystemSnapshot snapshot : snapshots) {
                writer.write(String.format(
                        "%s,%.4f,%.4f,%.4f",
                        snapshot.getTimestamp(),
                        snapshot.getCpu().getUsage(),
                        snapshot.getMemory().getUsage(),
                        snapshot.getMemory().getSwapUsage()
                ));

                writer.newLine();
            }
        }

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
}
