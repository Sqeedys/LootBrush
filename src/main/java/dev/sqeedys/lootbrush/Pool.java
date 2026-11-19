package dev.sqeedys.lootbrush;

import java.util.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

record Pool(Map<String, Integer> tables) {
    Pool {
        if (tables == null || tables.isEmpty() || tables.size() > 64) throw new IllegalArgumentException("Pool needs 1–64 tables.");
        var copy = new LinkedHashMap<String, Integer>();
        tables.forEach((id, weight) -> {
            if (id == null || Identifier.tryParse(id) == null || weight == null || weight < 1 || weight > 1_000_000)
                throw new IllegalArgumentException("Table IDs must be valid; weights must be 1–1000000.");
            String normalized = Identifier.parse(id).toString();
            if (copy.put(normalized, weight) != null) throw new IllegalArgumentException("Duplicate table: " + normalized);
        });
        tables = Collections.unmodifiableMap(copy);
    }
    String choose(RandomSource random) {
        int draw = random.nextInt(tables.values().stream().mapToInt(Integer::intValue).sum());
        for (var entry : tables.entrySet()) { draw -= entry.getValue(); if (draw < 0) return entry.getKey(); }
        throw new AssertionError();
    }
}
