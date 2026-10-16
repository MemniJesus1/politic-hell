package com.politichell.hud;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Простая заглушка-экономика: балансы в plugins/PoliticHellHUD/data.yml. */
public class FileCurrencyProvider implements CurrencyProvider {
    private final File file;
    private final long startCoins, startDonate;
    private final Map<UUID, long[]> data = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public FileCurrencyProvider(File dataFolder, long startCoins, long startDonate) {
        this.file = new File(dataFolder, "data.yml");
        this.startCoins = startCoins;
        this.startDonate = startDonate;
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("balances");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                data.put(id, new long[]{sec.getLong(key + ".coins"), sec.getLong(key + ".donate")});
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private long[] row(UUID id) {
        return data.computeIfAbsent(id, k -> { dirty = true; return new long[]{startCoins, startDonate}; });
    }

    @Override public long get(UUID p, Currency c) { return row(p)[c.ordinal()]; }

    @Override public void set(UUID p, Currency c, long amount) {
        row(p)[c.ordinal()] = amount;
        dirty = true;
    }

    @Override public synchronized void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        for (var e : data.entrySet()) {
            y.set("balances." + e.getKey() + ".coins", e.getValue()[0]);
            y.set("balances." + e.getKey() + ".donate", e.getValue()[1]);
        }
        try { y.save(file); dirty = false; } catch (IOException ex) { ex.printStackTrace(); }
    }
}
