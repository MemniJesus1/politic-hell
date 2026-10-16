package com.politichell.hud;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Список стран из plugins/PoliticHellHUD/countries.yml (генерируется build_pack.py вместе с флагами). */
public class Countries {
    public record Country(String id, String name, String glyph, String color) {}

    private final Map<String, Country> map = new LinkedHashMap<>();
    private String noneGlyph = Glyphs.FLAG_NONE;

    public Countries(PoliticHellHud plugin) {
        File f = new File(plugin.getDataFolder(), "countries.yml");
        if (!f.exists()) plugin.saveResource("countries.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        noneGlyph = y.getString("none-glyph", Glyphs.FLAG_NONE);
        ConfigurationSection sec = y.getConfigurationSection("countries");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            map.put(id.toLowerCase(), new Country(id.toLowerCase(), sec.getString(id + ".name", id),
                    sec.getString(id + ".glyph", ""), sec.getString(id + ".color", "#FFFFFF")));
        }
    }

    public Country get(String id) { return id == null ? null : map.get(id.toLowerCase()); }
    public Iterable<Country> all() { return map.values(); }
    public java.util.Set<String> ids() { return map.keySet(); }
    public String noneGlyph() { return noneGlyph; }
}
