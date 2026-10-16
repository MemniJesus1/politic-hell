package com.politichell.hud;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PoliticHellHud extends JavaPlugin implements Listener {
    private static PoliticHellHud instance;
    public static PoliticHellHud get() { return instance; }
    private CurrencyProvider provider;
    private EconomyBridge economy;
    public EconomyBridge economy() { return economy; }
    private HudRenderer renderer;
    private PackManager packs;
    private Countries countries;
    private PlayerStore players;
    private VoiceProvider voice = new VoiceProvider.Manual();
    private final InfoCache info = new InfoCache();
    private AfkManager afk;
    private Relations relations;
    private NewbieManager newbies;
    private NpcDemo npcDemo;
    private RankManager ranks;
    private com.politichell.hud.cards.CardManager cards;
    public com.politichell.hud.cards.CardManager cards() { return cards; }
    public RankManager ranks() { return ranks; }

    // ── страна игрока (заглушка под Towny: сюда подставим нацию) ──
    /** Название страны игрока (любой текст) или null. Если игрок выбрал только флаг из списка — название флага. */
    public String countryName(UUID id) {
        var t = integrations != null ? integrations.towny(id) : null;
        String src = cfg("gameplay").getString("country.source", "auto").toLowerCase(java.util.Locale.ROOT);
        if (t != null && !src.equals("manual")) {
            if (!t.nation().isBlank() && !src.equals("town")) return t.nation();      // нация Towny (название любое, как назвал игрок)
            if (!t.town().isBlank() && !src.equals("nation")) return t.town();        // нет нации — страной считается город
        }
        if ("manual".equals(src)) {   // запасной режим без Towny
            String n = players.getCountryName(id);
            if (n != null && !n.isBlank()) return n;
        }
        return null;
    }
    private boolean manualCountry() { return "manual".equalsIgnoreCase(cfg("gameplay").getString("country.source", "towny")); }
    /** Глиф флага игрока или null, если флаг не выбран. */
    public String flagGlyph(UUID id) {
        Countries.Country c = countries.get(players.getFlag(id));
        return c != null ? c.glyph() : null;
    }
    /** Ключ для сравнения стран (войны/союзы/«свои»), null — страны нет. */
    public String countryKey(UUID id) { return PlayerStore.key(countryName(id)); }
    private Configs configs;
    private UpdateManager updater;
    public UpdateManager updater() { return updater; }
    public PackManager packs() { return packs; }
    private Integrations integrations;
    public Integrations integrations() { return integrations; }
    public org.bukkit.configuration.file.YamlConfiguration cfg(String name) { return configs.get(name); }
    private CompassManager compass;
    private com.politichell.hud.map.MapManager map;
    public com.politichell.hud.map.MapManager map() { return map; }
    public CompassManager compass() { return compass; }
    public NewbieManager newbies() { return newbies; }
    public AfkManager afk() { return afk; }

    /** Стиль шапки TAB игрока: выбранный им или hud.yml → tab.default-style (pillars / panel / min). */
    public String tabStyleOf(UUID id) {
        String m = players.getTab(id);
        if (m == null) m = cfg("hud").getString("tab.default-style", "pillars");
        m = m.toLowerCase(Locale.ROOT);
        return m.equals("panel") || m.equals("min") ? m : "pillars";
    }
    public Relations relations() { return relations; }

    public Countries countries() { return countries; }
    public PlayerStore players() { return players; }
    public VoiceProvider voice() { return voice; }
    public InfoCache info() { return info; }
    /** Подключить настоящий источник голоса (Plasmo Voice). */
    public static void setVoiceProvider(VoiceProvider v) { instance.voice = v; }
    private final Set<UUID> hidden = new HashSet<>();
    private File prefsFile;

    // ── API ──
    public static CurrencyProvider provider() { return instance.provider; }

    /** Подключить настоящую экономику вместо заглушки. */
    public static void setProvider(CurrencyProvider p) { instance.provider = Objects.requireNonNull(p); }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        TextWidth.load(this);
        configs = new Configs(this);
        for (String n : new String[]{"hud", "compass", "gameplay", "map"}) configs.get(n);   // создать файлы при первом запуске
        configs.get("economy");
        var ec = cfg("economy");
        FileCurrencyProvider fileProv = new FileCurrencyProvider(getDataFolder(),
                ec.getLong("start-balance.coins", 500), ec.getLong("start-balance.donate", 0));
        economy = new EconomyBridge(this, fileProv);
        provider = economy;
        // экономики других плагинов регистрируются в течение старта — итог пишем в лог после полной загрузки
        Bukkit.getScheduler().runTaskLater(this, () -> { economy.reload(); economy.report(); }, 1L);
        Bukkit.getScheduler().runTaskTimer(this, economy::refreshOnline, 20L,
                Math.max(1L, ec.getLong("refresh-ticks", 10)));
        prefsFile = new File(getDataFolder(), "prefs.yml");
        loadPrefs();
        countries = new Countries(this);
        players = new PlayerStore(getDataFolder());
        players.saveIfMigrated();
        relations = new Relations(getDataFolder());
        configs.get("ranks");
        ranks = new RankManager(this);
        Bukkit.getScheduler().runTaskTimer(this, ranks, 20L, 20L);
        integrations = new Integrations(this);
        Bukkit.getScheduler().runTaskTimer(this, integrations, 40L, 20L);
        afk = new AfkManager(this);
        newbies = new NewbieManager(this);
        Bukkit.getScheduler().runTaskTimer(this, newbies, 20L, 20L);
        getServer().getPluginManager().registerEvents(newbies, this);
        Bukkit.getScheduler().runTaskTimer(this, afk, 20L, 20L);
        getServer().getPluginManager().registerEvents(afk, this);
        Bukkit.getScheduler().runTaskLater(this, afk::hookEssentials, 5L);
        Bukkit.getScheduler().runTaskTimer(this, info, 1L, 20L);
        compass = new CompassManager(this);
        Bukkit.getScheduler().runTaskTimer(this, compass, 20L, 1L);
        getServer().getPluginManager().registerEvents(compass, this);
        map = new com.politichell.hud.map.MapManager(this);
        Bukkit.getScheduler().runTaskLater(this, () -> map.enable(), 2L);   // после загрузки NMinimap
        renderer = new HudRenderer(this);
        packs = new PackManager(this);
        Bukkit.getScheduler().runTaskLater(this, () -> packs.reloadAndSend(null), 40L);
        cards = new com.politichell.hud.cards.CardManager(this);
        cards.enable();
        new AdminPanel(this).enable();
        updater = new UpdateManager(this);
        updater.onStartup();
        Bukkit.getScheduler().runTaskTimer(this, renderer, 20L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, () -> provider.save(), 6000L, 6000L);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PoliticHellHUD v" + getDescription().getVersion() + " (" + countries.ids().size() + " стран)");
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            registerPlaceholders();
            // на всякий случай повторяем после полного старта сервера
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!me.clip.placeholderapi.PlaceholderAPI.isRegistered("phhud")) registerPlaceholders();
            }, 100L);
        } else {
            getLogger().warning("PlaceholderAPI не найден — %phhud_*% работать не будут!");
        }
    }

    /** Перечитать все файлы настроек (команда /hudreload и панель /phh). */
    public void reloadAll() {
        reloadConfig();
        configs.reload();
        economy.reload();
        ranks.reload();
        integrations.reload();
        renderer.reload();
        compass.reload();
        countries = new Countries(this);
        if (cards != null) {
            cards.reload();
            for (Player p : Bukkit.getOnlinePlayers()) cards.refresh(p);
        }
    }

    public String economyInfo() { return economy.describe() + " | " + integrations.describe(); }

    private void registerPlaceholders() {
        try {
            boolean ok = new PhhudExpansion(this).register();
            getLogger().info("PlaceholderAPI: регистрация %phhud_*% -> " + (ok ? "OK" : "НЕ УДАЛАСЬ (смотри /papi list)"));
        } catch (Throwable t) {
            getLogger().severe("PlaceholderAPI: ошибка регистрации %phhud_*%: " + t);
            t.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (cards != null) cards.disable();
        if (provider != null) provider.save();
        if (map != null) map.shutdown();
        if (compass != null) compass.shutdown();
        if (packs != null) packs.shutdown();
        savePrefs();
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        ranks.refresh(e.getPlayer());
        Bukkit.getScheduler().runTaskLater(this, () -> packs.send(e.getPlayer()), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { renderer.forget(e.getPlayer().getUniqueId()); info.forget(e.getPlayer().getUniqueId()); ranks.forget(e.getPlayer().getUniqueId()); integrations.forget(e.getPlayer().getUniqueId()); }

    boolean isHidden(UUID id) { return hidden.contains(id); }

    private void loadPrefs() {
        hidden.clear();
        if (!prefsFile.exists()) return;
        for (String s : YamlConfiguration.loadConfiguration(prefsFile).getStringList("hidden")) {
            try { hidden.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void savePrefs() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("hidden", hidden.stream().map(UUID::toString).toList());
        try { y.save(prefsFile); } catch (IOException ex) { ex.printStackTrace(); }
    }

    // ── commands ──
    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String[] a) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "hud" -> {
                if (!(s instanceof Player p)) return true;
                boolean off = a.length > 0 ? a[0].equalsIgnoreCase("off") : !hidden.contains(p.getUniqueId());
                if (off) { hidden.add(p.getUniqueId()); p.sendActionBar(net.kyori.adventure.text.Component.empty()); }
                else { hidden.remove(p.getUniqueId()); renderer.refresh(p); }
                p.sendMessage("§6HUD: " + (off ? "§cвыключен" : "§aвключен"));
                savePrefs();
            }
            case "hudbalance" -> {
                if (!(s instanceof Player p)) return true;
                p.sendMessage("§6Монеты: §f" + provider.get(p.getUniqueId(), Currency.COINS)
                        + " §8| §dДонат: §f" + provider.get(p.getUniqueId(), Currency.DONATE));
            }
            case "hudreload" -> {
                reloadAll();
                s.sendMessage("§aPoliticHellHUD: конфиг перезагружен");
            }
            case "country" -> countryCommand(s, a);
            case "sbstyle" -> {
                if (!(s instanceof Player p)) return true;
                String cur = players.getSb(p.getUniqueId());
                if (cur == null) cur = cfg("hud").getString("scoreboard.default", "compact");
                String m = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "toggle";
                if (m.equals("toggle")) m = cur.equals("full") ? "compact" : "full";
                if (!m.equals("full") && !m.equals("compact")) { p.sendMessage("§e/sbstyle full|compact|toggle"); return true; }
                players.setSb(p.getUniqueId(), m);
                p.sendMessage(m.equals("full") ? "§aСкорборд: §fполный (с подписями)" : "§aСкорборд: §fкомпактный (иконки)");
                p.sendMessage("§7Обновится в течение пары секунд. Скрыть совсем: §f/sb");
            }
            case "tabstyle" -> {
                if (!(s instanceof Player p)) return true;
                List<String> all = List.of("pillars", "panel", "min");
                String cur = tabStyleOf(p.getUniqueId());
                String m = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "next";
                if (m.equals("колонны")) m = "pillars"; else if (m.equals("панель")) m = "panel"; else if (m.equals("минимал")) m = "min";
                if (m.equals("next") || m.equals("toggle")) m = all.get((all.indexOf(cur) + 1) % all.size());
                if (!all.contains(m)) { p.sendMessage("§e/tabstyle pillars|panel|min §7(или без слова — следующий)"); return true; }
                players.setTab(p.getUniqueId(), m);
                p.sendMessage("§aШапка TAB: §f" + (m.equals("pillars") ? "«Колонны» (колонны и переливающийся фон)" : m.equals("panel") ? "«Панель» (рамка-свечение, как у скорборда)" : "«Минимал» (без рамки)"));
                p.sendMessage("§7Обновится через секунду. Другие стили: §f/tabstyle pillars§7 · §f/tabstyle panel§7 · §f/tabstyle min");
            }
            case "afk" -> {
                if (!(s instanceof Player p)) return true;
                boolean on = afk.toggleManual(p.getUniqueId());
                p.sendMessage(on ? "§7Вы теперь AFK" : "§aВы вернулись");
            }
            case "map" -> map.command(s, cmd, a);
            case "compass", "waypoint", "hudpoi" -> compass.command(s, cmd.getName().toLowerCase(Locale.ROOT), a);
            case "hudnpc" -> {
                if (npcDemo == null) npcDemo = new NpcDemo(this);
                String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
                if (sub.equals("clear")) { s.sendMessage("§aУдалено НПС: §f" + npcDemo.clear()); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("Только из игры"); return true; }
                if (sub.equals("demo")) { npcDemo.demo(p); s.sendMessage("§aШесть тестовых НПС перед тобой. Убрать: §f/hudnpc clear"); return true; }
                if (sub.equals("spawn") && a.length >= 2) {
                    NpcDemo.Rel rel = NpcDemo.Rel.NEUTRAL;
                    if (a.length > 3) { try { rel = NpcDemo.Rel.valueOf(a[3].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { s.sendMessage("§cОтношение: same|ally|union|newbie|enemy|neutral"); return true; } }
                    boolean afkFlag = a.length > 4 && a[4].equalsIgnoreCase("afk");
                    boolean mute = a.length > 5 && a[5].equalsIgnoreCase("mute");
                    String rankArg = a.length > 6 ? a[6] : "player";
                    String flagArg = a.length > 2 && countries.get(a[2]) != null ? a[2] : null;     // id флага из /country list или «-» (без флага)
                    String nameArg = a.length > 2 && flagArg == null && !a[2].equals("-") ? a[2].replace('_', ' ') : (flagArg != null ? countries.get(flagArg).name() : "");
                    npcDemo.spawn(p.getLocation(), a[1], nameArg, flagArg, rel, afkFlag, !mute, rankArg);
                    return true;
                }
                s.sendMessage("§e/hudnpc demo §7— 6 НПС со всеми вариантами");
                s.sendMessage("§e/hudnpc spawn <ник> [id флага | Название_страны | -] [same|ally|union|newbie|enemy|neutral] [afk|-] [mute|-] [ранг]");
                s.sendMessage("§e/hudnpc clear §7— убрать всех");
            }
            case "hudrel" -> {
                if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
                    s.sendMessage("§cВойны: §f" + relations.wars());
                    s.sendMessage("§bСоюзы: §f" + relations.alliances());
                    s.sendMessage("§bСоюзы наций: §f" + relations.unions());
                    return true;
                }
                if (a.length < 3) {
                    s.sendMessage("§c/hudrel <war|ally|union|peace|list> <страна1> <страна2>   (название страны, пробелы → _ , например Новая_Атлантида)"); return true;
                }
                String k1 = PlayerStore.key(a[1]), k2 = PlayerStore.key(a[2]);
                switch (a[0].toLowerCase(Locale.ROOT)) {
                    case "war" -> relations.declareWar(k1, k2);
                    case "ally" -> relations.ally(k1, k2);
                    case "union" -> relations.union(k1, k2);
                    case "peace" -> relations.peace(k1, k2);
                    default -> { s.sendMessage("§cwar|ally|union|peace|list"); return true; }
                }
                s.sendMessage("§aОтношения обновлены: " + a[0] + " " + a[1] + " " + a[2]);
            }
            case "hudrank" -> {
                if (a.length < 2) {
                    StringBuilder sb = new StringBuilder("§c/hudrank <ник> <ранг|auto>   ранги: ");
                    for (var r : ranks.all()) sb.append(r.id()).append(' ');
                    s.sendMessage(sb.toString()); return true;
                }
                @SuppressWarnings("deprecation") var t = Bukkit.getOfflinePlayer(a[0]);
                if (a[1].equalsIgnoreCase("auto")) { players.setForcedRank(t.getUniqueId(), null); s.sendMessage("§aРанг " + a[0] + ": по LuckPerms (авто)"); }
                else if (ranks.byId(a[1]) == null) { s.sendMessage("§cНет такого ранга (см. ranks.yml)"); return true; }
                else { players.setForcedRank(t.getUniqueId(), a[1].toLowerCase(Locale.ROOT)); s.sendMessage("§aРанг " + a[0] + ": " + a[1]); }
                if (t.getPlayer() != null) ranks.refresh(t.getPlayer());
            }
            case "hudtest" -> HudTest.run(this, s);
            case "hudvoice" -> {
                if (a.length < 2 || !(voice instanceof VoiceProvider.Manual m)) { s.sendMessage("§c/hudvoice <игрок> <on|off>"); return true; }
                @SuppressWarnings("deprecation") var t = Bukkit.getOfflinePlayer(a[0]);
                if (a[1].equalsIgnoreCase("on")) m.on.add(t.getUniqueId()); else m.on.remove(t.getUniqueId());
                s.sendMessage("§aголос " + a[0] + ": " + a[1]);
            }
            case "hudpack" -> {
                if (!packs.enabled()) { s.sendMessage("§cpack.enabled: false в config.yml"); return true; }
                s.sendMessage("§eОтправляю актуальный ресурспак всем...");
                packs.reloadAndSend(() -> s.sendMessage("§aПак: " + packs.status()));
            }
            case "hudupdate" -> updater.command(s, a);
            case "hudeco" -> {
                if (a.length >= 1 && a[0].equalsIgnoreCase("info")) { s.sendMessage("§6Экономика: §f" + economy.describe()); s.sendMessage("§6Связки: §f" + integrations.describe()); return true; }
                if (a.length < 3) { s.sendMessage("§c/hudeco <give|take|set|get> <игрок> <coins|donate> [сумма]   |   /hudeco info"); return true; }
                @SuppressWarnings("deprecation")
                var off = Bukkit.getOfflinePlayer(a[1]);
                Currency c = Currency.byId(a[2]);
                if (c == null) { s.sendMessage("§cВалюта: coins или donate"); return true; }
                UUID id = off.getUniqueId();
                String sub = a[0].toLowerCase(Locale.ROOT);
                if (sub.equals("get")) {
                    s.sendMessage("§e" + a[1] + " " + c.id + " = " + provider.get(id, c));
                    return true;
                }
                if (a.length < 4) { s.sendMessage("§cУкажи сумму"); return true; }
                long amt;
                try { amt = Long.parseLong(a[3]); } catch (NumberFormatException ex) { s.sendMessage("§cСумма — целое число"); return true; }
                switch (sub) {
                    case "give" -> provider.add(id, c, amt);
                    case "take" -> provider.add(id, c, -amt);
                    case "set" -> provider.set(id, c, amt);
                    default -> { s.sendMessage("§cgive|take|set|get"); return true; }
                }
                s.sendMessage("§a" + a[1] + " " + c.id + " = " + provider.get(id, c));
            }
        }
        return true;
    }

    private void countryCommand(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        Player me = s instanceof Player p ? p : null;
        switch (sub) {
            case "list" -> {
                s.sendMessage("§6Флаги (id → для /country flag <id>):");
                StringBuilder sb = new StringBuilder();
                for (var c : countries.all()) sb.append("§f").append(c.glyph()).append(" §7").append(c.id()).append("  ");
                s.sendMessage(sb.toString());
            }
            case "info" -> {
                if (me == null) { s.sendMessage("§c/country list"); return; }
                String n = countryName(me.getUniqueId()), fg = flagGlyph(me.getUniqueId());
                s.sendMessage("§6Ваша страна: §f" + (n != null ? n : "§7нет (вступи в нацию Towny или создай свою: /n new <название>)") + (fg != null ? " §f" + fg : " §7(флаг не выбран)"));
                s.sendMessage("§7Название страны = название твоей нации в Towny, придумать можно любое.");
                s.sendMessage("§7/country flag <id|none>   — выбрать флаг (по желанию; список: /country list)   §8|   §7/country clear — убрать флаг");
            }
            case "name" -> {
                if (me == null) { s.sendMessage("§cТолько для игрока"); return; }
                if (!manualCountry()) { s.sendMessage("§eНазвание страны — это название твоей нации в Towny: §f/n new <название>§e. Флаг: §f/country flag <id>"); return; }
                String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).trim();
                int max = cfg("gameplay").getInt("country.name-max-length", 24);
                if (name.isEmpty()) { s.sendMessage("§c/country name <название>"); return; }
                if (name.length() > max) { s.sendMessage("§cСлишком длинное название (максимум " + max + " символов)"); return; }
                if (name.matches(".*[§&%].*")) { s.sendMessage("§cВ названии нельзя §f§ & %"); return; }
                players.setCountryName(me.getUniqueId(), name);
                s.sendMessage("§aНазвание страны: §f" + name);
            }
            case "flag" -> {
                if (me == null) { s.sendMessage("§cТолько для игрока"); return; }
                if (a.length < 2) { s.sendMessage("§c/country flag <id|none>   (список: /country list)"); return; }
                if (a[1].equalsIgnoreCase("none")) { players.setFlag(me.getUniqueId(), null); s.sendMessage("§aФлаг убран — над ником его не будет"); return; }
                Countries.Country c = countries.get(a[1]);
                if (c == null) { s.sendMessage("§cНет такого флага. /country list"); return; }
                players.setFlag(me.getUniqueId(), c.id());
                s.sendMessage("§aФлаг: §f" + c.glyph());
            }
            case "set", "clear" -> {          // быстрый вариант / админ: set <id флага> [игрок]  (имя = название флага)   |   clear [игрок]
                boolean clear = sub.equals("clear");
                int idx = clear ? 1 : 2;
                Player target = null;
                if (a.length > idx) {
                    if (!s.hasPermission("politichellhud.admin")) { s.sendMessage("§cНет прав"); return; }
                    target = Bukkit.getPlayerExact(a[idx]);
                    if (target == null) { s.sendMessage("§cИгрок не в сети"); return; }
                } else target = me;
                if (target == null) { s.sendMessage("§cУкажи игрока"); return; }
                UUID id = target.getUniqueId();
                if (clear) { players.setFlag(id, null); if (manualCountry()) players.setCountryName(id, null); s.sendMessage("§aФлаг убран"); return; }
                Countries.Country c = a.length < 2 ? null : countries.get(a[1]);
                if (c == null) { s.sendMessage("§cНет такого флага. /country list"); return; }
                players.setFlag(id, c.id());
                s.sendMessage("§aФлаг установлен: §f" + c.glyph());
            }
            default -> s.sendMessage("§c/country [info | flag <id|none> | list | clear]   админ: set <id флага> [игрок], clear [игрок]");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (n.equals("hud") && a.length == 1) return List.of("on", "off");
        if (n.equals("hudupdate") && a.length == 1) return UpdateManager.options();
        if (n.equals("country")) {
            if (a.length == 1) return List.of("info", "flag", "list", "set", "clear");
            if (a.length == 2 && (a[0].equalsIgnoreCase("set") || a[0].equalsIgnoreCase("flag"))) { List<String> l = new ArrayList<>(countries.ids()); l.add("none"); return l; }
        }
        if (n.equals("map")) return map.complete(a);
        if (n.equals("compass") && a.length == 1) return List.of("on", "off");
        if (n.equals("waypoint")) {
            if (a.length == 1) return List.of("add", "del", "list", "clear");
            if (a.length > 2 && a[0].equalsIgnoreCase("add")) return new ArrayList<>(CompassManager.ICONS);
        }
        if (n.equals("hudpoi")) {
            if (a.length == 1) return List.of("add", "del", "list");
            if (a.length == 3 && a[0].equalsIgnoreCase("add")) return new ArrayList<>(CompassManager.ICONS);
        }
        if (n.equals("country")) {
            if (a.length == 1) return List.of("info", "flag", "list", "clear", "set");
            if (a.length == 2 && (a[0].equalsIgnoreCase("flag") || a[0].equalsIgnoreCase("set"))) {
                List<String> l = new ArrayList<>(countries.ids()); if (a[0].equalsIgnoreCase("flag")) l.add("none"); return l;
            }
        }
        if (n.equals("hudrank")) {
            if (a.length == 1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            if (a.length == 2) { List<String> l = new ArrayList<>(); for (var r : ranks.all()) l.add(r.id()); l.add("auto"); return l; }
        }
        if (n.equals("tabstyle") && a.length == 1) return List.of("pillars", "panel", "min");
        if (n.equals("sbstyle") && a.length == 1) return List.of("full", "compact", "toggle");
        if (n.equals("hudnpc")) {
            if (a.length == 1) return List.of("demo", "spawn", "clear");
            if (a.length == 3 && a[0].equalsIgnoreCase("spawn")) return new ArrayList<>(countries.ids());
            if (a.length == 4) return List.of("same", "ally", "union", "newbie", "enemy", "neutral");
            if (a.length == 5) return List.of("afk", "-");
            if (a.length == 6) return List.of("mute", "-");
        }
        if (n.equals("hudrel")) {
            if (a.length == 1) return List.of("war", "ally", "union", "peace", "list");
            if (a.length == 2 || a.length == 3) return new ArrayList<>(countries.ids());
        }
        if (n.equals("hudvoice")) {
            if (a.length == 1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            if (a.length == 2) return List.of("on", "off");
        }
        if (n.equals("hudeco")) {
            if (a.length == 1) return List.of("give", "take", "set", "get", "info");
            if (a.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            if (a.length == 3) return List.of("coins", "donate");
        }
        return List.of();
    }
}
