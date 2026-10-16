package com.politichell.hud;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Отношения между странами: война / союз (relations.yml). ЗАГЛУШКА — позже это будет браться из Towny/SiegeWar
 * (отношения наций). Достаточно заменить {@link #between(String, String)}.
 */
public class Relations {
    /** SAME — одна страна (зелёный); TREATY / UNION / NEWBIE — голубой; ENEMY — красный; NEUTRAL — серый. */
    public enum Type { SAME, TREATY, UNION, NEWBIE, ENEMY, NEUTRAL, SELF }

    private final File file;
    private final Set<String> wars = new HashSet<>();
    private final Set<String> alliances = new HashSet<>();
    private final Set<String> unions = new HashSet<>();

    public Relations(File dataFolder) {
        file = new File(dataFolder, "relations.yml");
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        wars.addAll(y.getStringList("wars"));
        alliances.addAll(y.getStringList("alliances"));
        unions.addAll(y.getStringList("unions"));
    }

    private static String key(String a, String b) {
        a = a.toLowerCase(); b = b.toLowerCase();
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    public Type between(String a, String b) {
        if (a == null || b == null) return Type.NEUTRAL;
        if (a.equalsIgnoreCase(b)) return Type.SAME;          // одна страна — свои
        var in = PoliticHellHud.get() != null ? PoliticHellHud.get().integrations() : null;
        if (in != null) {                                     // дипломатия Towny: /n enemy add → враг, /n ally add → союзник (голубой)
            if (in.enemy(a, b)) return Type.ENEMY;
            if (in.allied(a, b)) return Type.TREATY;
        }
        String k = key(a, b);
        if (wars.contains(k)) return Type.ENEMY;
        if (alliances.contains(k)) return Type.TREATY;        // союзный договор
        if (unions.contains(k)) return Type.UNION;            // одна нация / союз стран
        return Type.NEUTRAL;
    }

    private void clear(String a, String b) { String k = key(a, b); wars.remove(k); alliances.remove(k); unions.remove(k); }
    public void declareWar(String a, String b) { clear(a, b); wars.add(key(a, b)); save(); }
    public void ally(String a, String b) { clear(a, b); alliances.add(key(a, b)); save(); }
    public void union(String a, String b) { clear(a, b); unions.add(key(a, b)); save(); }
    public void peace(String a, String b) { clear(a, b); save(); }
    public Set<String> unions() { return unions; }
    public Set<String> wars() { return wars; }
    public Set<String> alliances() { return alliances; }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("wars", new ArrayList<>(wars));
        y.set("alliances", new ArrayList<>(alliances));
        y.set("unions", new ArrayList<>(unions));
        try { y.save(file); } catch (IOException ex) { ex.printStackTrace(); }
    }
}
