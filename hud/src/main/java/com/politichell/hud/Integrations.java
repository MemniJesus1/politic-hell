package com.politichell.hud;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Связки с другими плагинами БЕЗ их API — через PlaceholderAPI (никаких зависимостей при сборке, ничего не падает, если плагина нет):
 *  • Plasmo Voice  — %plasmovoice_installed% (true, если у игрока стоит мод голосового чата) → значок голоса в табе/над головой
 *  • Towny         — %townyadvanced_town% / _nation% / _player_status% → город, роль, а название страны = нация
 * Считаем в ГЛАВНОМ потоке раз в секунду, плейсхолдеры (TAB зовёт их из других потоков) читают кэш.
 * Включение/выключение — gameplay.yml → integrations.
 */
public class Integrations implements Runnable {
    /** Данные Towny игрока. Деньги — уже сжатые («12K»), «—» если нет города. */
    public record Towny(String town, String nation, String role, String bank, String nationBank, String residents, String plots, boolean sieged) {
        public Towny(String town, String nation, String role) { this(town, nation, role, "—", "—", "—", "—", false); }
    }
    private int tick;

    // ── дипломатия Towny: союзы и враги наций (/n ally add, /n enemy add) — читаем через API Towny рефлексией (без зависимости при сборке) ──
    private final Map<String, Set<String>> allies = new ConcurrentHashMap<>(), enemies = new ConcurrentHashMap<>();
    private boolean diploFailed;

    /** a, b — ключи стран (нация Towny, строчными, пробелы → «_»). */
    public boolean allied(String a, String b) { var s = allies.get(a); return s != null && s.contains(b) || (s = allies.get(b)) != null && s.contains(a); }
    public boolean enemy(String a, String b) { var s = enemies.get(a); return s != null && s.contains(b) || (s = enemies.get(b)) != null && s.contains(a); }

    private void refreshDiplomacy() {
        if (!townyOn || diploFailed || !Bukkit.getPluginManager().isPluginEnabled("Towny")) return;
        try {
            Class<?> uni = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Object u = uni.getMethod("getInstance").invoke(null);
            var getNation = uni.getMethod("getNation", String.class);
            Set<String> names = new HashSet<>();
            for (Towny t : towny.values()) if (!t.nation().isBlank()) names.add(t.nation());
            Map<String, Set<String>> al = new HashMap<>(), en = new HashMap<>();
            for (String n : names) {
                Object nation = getNation.invoke(u, n);
                if (nation == null) continue;
                al.put(PlayerStore.key(n), nameSet(nation, "getAllies"));
                en.put(PlayerStore.key(n), nameSet(nation, "getEnemies"));
            }
            allies.clear(); allies.putAll(al); enemies.clear(); enemies.putAll(en);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            diploFailed = true;
            plugin.getLogger().warning("Дипломатия Towny недоступна (другая версия API): " + e + " — союзы/враги задаются вручную /hudrel");
        } catch (Throwable t) {
            plugin.getLogger().warning("Дипломатия Towny: " + t);
        }
    }

    private static Set<String> nameSet(Object nation, String method) throws Exception {
        Set<String> r = new HashSet<>();
        Object list = nation.getClass().getMethod(method).invoke(nation);
        if (list instanceof Iterable<?> it) for (Object o : it) r.add(PlayerStore.key((String) o.getClass().getMethod("getName").invoke(o)));
        return r;
    }

    /** Игроки с мини-голосом (Plasmo Voice установлен у клиента). Читает VoiceProvider.Manual. */
    public static final Set<UUID> VOICE = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Towny> towny = new ConcurrentHashMap<>();
    private final PoliticHellHud plugin;
    private boolean voiceOn, townyOn;
    private boolean voiceSeen, townySeen;      // хоть раз получили настоящее значение (а не сырой %плейсхолдер%)

    public Integrations(PoliticHellHud plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        var c = plugin.cfg("gameplay");
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        voiceOn = papi && !"off".equalsIgnoreCase(c.getString("integrations.plasmovoice", "auto")) && Bukkit.getPluginManager().getPlugin("PlasmoVoice") != null;
        townyOn = papi && !"off".equalsIgnoreCase(c.getString("integrations.towny", "auto")) && Bukkit.getPluginManager().isPluginEnabled("Towny");
        if (!voiceOn) VOICE.clear();
        if (!townyOn) towny.clear();
    }

    public boolean voiceActive() { return voiceOn; }
    public boolean townyActive() { return townyOn; }
    public String describe() {
        return "Plasmo Voice: " + (voiceOn ? (voiceSeen ? "подключён" : "плагин есть, плейсхолдер пока молчит") : "нет/выкл")
                + " | Towny: " + (townyOn ? (townySeen ? "подключён" : "плагин есть, плейсхолдер пока молчит") : "нет/выкл");
    }

    public Towny towny(UUID id) { return townyOn ? towny.get(id) : null; }
    public void forget(UUID id) { VOICE.remove(id); towny.remove(id); }

    private static String ask(Player p, String ph) {
        String r = PlaceholderAPI.setPlaceholders(p, ph);
        return (r == null || r.equals(ph)) ? null : r.trim();       // не распознан → null
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("§.", "").trim();
        return (s.equalsIgnoreCase("none") || s.equalsIgnoreCase("null") || s.equals("-") || s.equals("—")) ? "" : s;
    }

    private static String role(String status) {
        return switch (clean(status).toLowerCase()) {
            case "nomad" -> "Кочевник";
            case "resident" -> "Житель";
            case "mayor" -> "Мэр";
            case "king", "queen" -> "Король";
            case "" -> "";
            default -> clean(status);
        };
    }

    /** «1 234,50 Coins» / «$1,234.5» → «1234» → «1K». */
    private static String money(String raw) {
        String s = clean(raw);
        if (s.isEmpty()) return "—";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d[\\d\\s.,]*").matcher(s);
        if (!m.find()) return "—";
        String n = m.group().replace(" ", "").replace("\u00a0", "");
        int dot = n.lastIndexOf('.'), com = n.lastIndexOf(',');
        int dec = Math.max(dot, com);
        if (dec >= 0 && n.length() - dec - 1 <= 2) n = n.substring(0, dec);       // дробную часть отбрасываем
        n = n.replaceAll("[.,]", "");
        try { return HudRenderer.compact(Long.parseLong(n), 36, false); } catch (Exception e) { return "—"; }
    }

    private static String num(String raw) {
        String s = clean(raw);
        return s.matches("\\d+") ? s : "—";
    }

    private static String voiceSiege(Player p) {      // SiegeWar 3.3+: %siegewar_player_is_sieged% (если SiegeWar нет — пусто)
        String r = ask(p, "%siegewar_player_is_sieged%");
        return r == null ? "" : r;
    }

    @Override
    public void run() {
        tick++;
        if (tick % 5 == 0) refreshDiplomacy();
        if (!voiceOn && !townyOn) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            try {
                if (voiceOn) {
                    String v = ask(p, "%plasmovoice_installed%");
                    if (v != null) { voiceSeen = true; if (v.equalsIgnoreCase("true")) VOICE.add(id); else VOICE.remove(id); }
                }
                if (townyOn) {
                    String town = ask(p, "%townyadvanced_town%");
                    if (town != null) {
                        townySeen = true;
                        String nation = ask(p, "%townyadvanced_nation%");
                        String st = ask(p, "%townyadvanced_player_status%");
                        Towny old = towny.get(id);
                        boolean extras = old == null || tick % 5 == 0;       // казна, участки, жители, осада — раз в 5 секунд (меняются редко)
                        if (clean(town).isEmpty()) towny.put(id, new Towny("", clean(nation), role(st)));
                        else if (!extras) towny.put(id, new Towny(clean(town), clean(nation), role(st), old.bank(), old.nationBank(), old.residents(), old.plots(), old.sieged()));
                        else {
                            String bank = money(ask(p, "%townyadvanced_town_balance%"));
                            String nbank = clean(nation).isEmpty() ? "—" : money(ask(p, "%townyadvanced_nation_balance%"));
                            String ro = num(ask(p, "%townyadvanced_town_residents_online%")), ra = num(ask(p, "%townyadvanced_town_residents_amount%"));
                            String pu = num(ask(p, "%townyadvanced_town_townblocks_used%")), pm = num(ask(p, "%townyadvanced_town_townblocks_maximum%"));
                            String si = voiceSiege(p);
                            towny.put(id, new Towny(clean(town), clean(nation), role(st), bank, nbank,
                                    ro.equals("—") || ra.equals("—") ? "—" : ro + "/" + ra,
                                    pu.equals("—") || pm.equals("—") ? "—" : pu + "/" + pm, "true".equalsIgnoreCase(si)));
                        }
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Интеграции: " + t);
                voiceOn = townyOn = false;     // не спамим при поломке
            }
        }
    }
}
