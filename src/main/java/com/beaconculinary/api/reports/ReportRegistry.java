package com.beaconculinary.api.reports;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every ReportDefinition bean, by key, in a stable catalogue order (category, then title). */
@Component
public class ReportRegistry {
    private final Map<String, ReportDefinition> byKey = new LinkedHashMap<>();

    public ReportRegistry(List<ReportDefinition> definitions) {
        definitions.stream()
                .sorted(Comparator.comparing(ReportDefinition::category).thenComparing(ReportDefinition::title))
                .forEach(definition -> {
                    if (byKey.putIfAbsent(definition.key(), definition) != null) {
                        throw new IllegalStateException("Duplicate report key: " + definition.key());
                    }
                });
    }

    public List<ReportDefinition> all() {
        return List.copyOf(byKey.values());
    }

    public Optional<ReportDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }
}
