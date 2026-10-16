package com.politichell.hud;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Несколько конфиг-файлов (hud.yml, compass.yml, gameplay.yml). Недостающие ключи берутся из jar — старый файл не ломается. */
public class Configs {
    private final PoliticHellHud plugin;
    private final Map<String, YamlConfiguration> map = new HashMap<>();

    public Configs(PoliticHellHud plugin) { this.plugin = plugin; }

    public YamlConfiguration get(String name) { return map.computeIfAbsent(name, this::load); }

    public void reload() { map.clear(); }

    private YamlConfiguration load(String name) {
        File f = new File(plugin.getDataFolder(), name + ".yml");
        if (!f.exists()) plugin.saveResource(name + ".yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource(name + ".yml");
        if (in != null) y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        return y;
    }
}
