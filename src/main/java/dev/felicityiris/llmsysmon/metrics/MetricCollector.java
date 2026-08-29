package dev.felicityiris.llmsysmon.metrics;

public interface MetricCollector<T> {
    T collect();
}
