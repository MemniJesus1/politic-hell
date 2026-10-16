package com.politichell.hud;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Личные данные игрока (players.yml): СВОЯ страна (название — любой текст, который игрок придумал), флаг (необязательный),
 * стиль скорборда, ручной ранг. Это ЗАГЛУШКА под Towny: позже название и флаг будут браться из нации Towny —
 * достаточно изменить {@code PoliticHellHud.countryName/flagId}.
 */
public class PlayerStore {
    private final File file;
    private final Map<UUID, String> cname = new ConcurrentHashMap<>();   // название страны (любой текст)
    private final Map<UUID, String> flag = new ConcurrentHashMap<>();    // id флага из countries.yml (необязательно)
    private final Map<UUID, String> sb = new ConcurrentHashMap<>();      // full / compact
    private final Map<UUID, String> tab = new ConcurrentHashMap<>();     // стиль шапки TAB: pillars / panel / min
    private final Map<UUID, String> rank = new ConcurrentHashMap<>();    // ручной ранг (/hudrank)

    private boolean needSave;

    public PlayerStore(File dataFolder) {
        file = new File(dataFolder, "players.yml");
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        read(y, "sb", sb); read(y, "rank", rank); read(y, "tab", tab);
        // формат 2 (1.8.3): название страны = нация Towny, флаг — только тот, что игрок выбрал сам. Старые флаги/названия (тестовые /country set,
        // «флаг из списка = страна») сбрасываются один раз, чтобы флаг нигде не появлялся сам.
        if (y.getInt("format", 1) >= 2) { read(y, "cname", cname); read(y, "flag", flag); }
        else if (!y.getKeys(false).isEmpty()) { needSave = true; }
    }

    public void saveIfMigrated() { if (needSave) { needSave = false; save(); } }

    private static void read(YamlConfiguration y, String sec, Map<UUID, String> into) {
        var s = y.getConfigurationSection(sec);
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try { into.put(UUID.fromString(k), s.getString(k)); } catch (IllegalArgumentException ignored) {}
        }
    }

    public String getCountryName(UUID id) { return cname.get(id); }
    public String getFlag(UUID id) { return flag.get(id); }
    public String getSb(UUID id) { return sb.get(id); }
    public String getTab(UUID id) { return tab.get(id); }
    public String getForcedRank(UUID id) { return rank.get(id); }

    public void setCountryName(UUID id, String name) { put(cname, id, name); }
    public void setFlag(UUID id, String flagId) { put(flag, id, flagId); }
    public void setSb(UUID id, String mode) { put(sb, id, mode); }
    public void setTab(UUID id, String mode) { put(tab, id, mode); }
    public void setForcedRank(UUID id, String r) { put(rank, id, r); }

    private void put(Map<UUID, String> m, UUID id, String v) {
        if (v == null) m.remove(id); else m.put(id, v);
        save();
    }

    /** Ключ страны для сравнения («одна страна», войны, союзы): название в нижнем регистре, пробелы → «_». */
    public static String key(String name) {
        return name == null ? null : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("format", 2);
        for (var e : cname.entrySet()) y.set("cname." + e.getKey(), e.getValue());
        for (var e : flag.entrySet()) y.set("flag." + e.getKey(), e.getValue());
        for (var e : sb.entrySet()) y.set("sb." + e.getKey(), e.getValue());
        for (var e : tab.entrySet()) y.set("tab." + e.getKey(), e.getValue());
        for (var e : rank.entrySet()) y.set("rank." + e.getKey(), e.getValue());
        try { y.save(file); } catch (IOException ex) { ex.printStackTrace(); }
    }
}
