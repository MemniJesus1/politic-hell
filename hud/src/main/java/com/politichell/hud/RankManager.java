package com.politichell.hud;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Привилегия / ранг игрока (значок перед ником в табе, над головой и на скорборде).
 * Ранги описаны в ranks.yml СВЕРХУ ВНИЗ: побеждает первый, чьё право игрок имеет (у LuckPerms группа = право group.&lt;имя&gt;).
 * Получил роль выше (VIP → модератор) — значок меняется сам. Самый нижний — «Игрок» (по умолчанию, у всех).
 * Считается в главном потоке раз в секунду, плейсхолдеры читают кэш (TAB зовёт их не из главного потока).
 */
public class RankManager implements Runnable {
    public record Rank(String id, int order, String permission, String icon, String label, String color) {}

    /** Встроенные значки (см. tools/build_pack.py, ICONS rk_*). В ranks.yml можно указать и свой символ: icon: "\uE380". */
    static final Map<String, String> BUILTIN = Map.of(
            "owner", "\uE370", "admin", "\uE371", "moderator", "\uE372", "helper", "\uE373",
            "premium", "\uE374", "vip", "\uE375", "player", "\uE376");

    private final PoliticHellHud plugin;
    private final List<Rank> ranks = new ArrayList<>();
    private Rank def;
    private final Map<UUID, Rank> cache = new ConcurrentHashMap<>();

    public RankManager(PoliticHellHud plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        ranks.clear();
        var y = plugin.cfg("ranks");
        ConfigurationSection sec = y.getConfigurationSection("ranks");
        int i = 0;
        if (sec != null) for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) continue;
            ranks.add(new Rank(id.toLowerCase(Locale.ROOT), i++, r.getString("permission", ""), icon(r.getString("icon", id)),
                    r.getString("label", id), r.getString("color", "&f")));
        }
        String d = y.getString("default", "player").toLowerCase(Locale.ROOT);
        def = ranks.stream().filter(r -> r.id().equals(d)).findFirst()
                .orElse(new Rank("player", 99, "", BUILTIN.get("player"), "Игрок", "&7"));
        cache.clear();
    }

    private static String icon(String s) {
        if (s == null || s.isBlank()) return "";
        String b = BUILTIN.get(s.toLowerCase(Locale.ROOT));
        if (b != null) return b;
        if (s.length() == 1) return s;
        if (s.toLowerCase(Locale.ROOT).startsWith("\\u") && s.length() >= 6) {
            try { return String.valueOf((char) Integer.parseInt(s.substring(2, 6), 16)); } catch (NumberFormatException ignored) {}
        }
        return "";
    }

    public Rank byId(String id) {
        if (id == null) return null;
        for (Rank r : ranks) if (r.id().equalsIgnoreCase(id)) return r;
        return def.id().equalsIgnoreCase(id) ? def : null;
    }

    public List<Rank> all() { return ranks; }
    public Rank get(UUID id) { return cache.getOrDefault(id, def); }

    /** Считает ранг игрока: ручной (/hudrank) → право группы → OP (если включено) → по умолчанию. */
    private Rank compute(Player p) {
        String forced = plugin.players().getForcedRank(p.getUniqueId());
        if (forced != null) { Rank r = byId(forced); if (r != null) return r; }
        for (Rank r : ranks) {
            if (r.permission().isBlank()) continue;
            if (p.isPermissionSet(r.permission()) && p.hasPermission(r.permission())) return r;
        }
        if (p.isOp()) {
            Rank r = byId(plugin.cfg("ranks").getString("op-rank", ""));
            if (r != null) return r;
        }
        return def;
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) cache.put(p.getUniqueId(), compute(p));
    }

    public void forget(UUID id) { cache.remove(id); }
    public void refresh(Player p) { cache.put(p.getUniqueId(), compute(p)); }
}
