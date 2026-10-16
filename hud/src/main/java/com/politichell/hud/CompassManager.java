package com.politichell.hud;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Компас + детекторы на боссбаре (две полоски WHITE, их спрайты в ресурспаке прозрачные):
 *   полоска 1: сверху — курс цифрами («SE 127»), под ним — линейка (метки каждые 5°, N/E/S/W, маркеры POI);
 *   полоска 2: координаты «X 120  Y 64  Z -300»; если маркер у центра (±lock-angle°) — «значок название  124 м».
 * Маркеры: спавн мира, твоя кровать, место смерти, личные точки (/waypoint), общие точки (/hudpoi) и
 * точки от других плагинов (CompassManager.addProvider(...), например города/войны Towny).
 * Всё рисуется глифами шрифта politichell:compass (build_pack.py), положение — «пробелами» шрифта.
 */
public class CompassManager implements Runnable, Listener {
    public record Poi(String id, String name, String icon, String world, double x, double y, double z) {}
    public interface PoiProvider { List<Poi> pois(Player viewer); }

    private record Glyph(String ch, int w) {}
    private record Item(double cx, String glyph, int w, TextColor color, int order) {}
    private record Bars(BossBar top, BossBar bottom) {}
    private record Marker(Poi poi, double rel, double dist) {}

    private static final Key COMPASS = Key.key("politichell", "compass");
    private static final Key DEFAULT = Key.key("minecraft", "default");
    private static final String[] DIR = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final Map<String, String> ICON_DEFAULT = Map.of(
            "wp", "\uE360", "star", "\uE361", "home", "\uE362", "spawn", "\uE363", "death", "\uE364",
            "town", "\uE351", "war", "\uE353", "crown", "\uE352");
    public static final Set<String> ICONS = ICON_DEFAULT.keySet();

    private final PoliticHellHud plugin;
    private final Map<String, Glyph> glyphs = new HashMap<>();
    private double[] alphaLevels = {1.0};
    private double fadeStart, fadeCurve, fadeMin;
    private final Map<UUID, Bars> bars = new HashMap<>();
    private final Set<UUID> off = new HashSet<>();
    private final List<PoiProvider> providers = new ArrayList<>();
    private final List<Poi> global = new ArrayList<>();
    private final Map<UUID, List<Poi>> personal = new HashMap<>();
    private final Map<UUID, List<Poi>> dynCache = new HashMap<>();
    private final Map<UUID, Integer> dynAge = new HashMap<>();
    private final Map<UUID, String> lastWorld = new HashMap<>();
    private final File fPois, fWay, fPrefs;

    // config
    private boolean textMode;
    private boolean enabled, showCoords, showSpawn, showHome, showDeath;
    private int width, lockAngle, maxDist;
    private double ppd;

    public CompassManager(PoliticHellHud plugin) {
        this.plugin = plugin;
        File dir = plugin.getDataFolder();
        fPois = new File(dir, "pois.yml"); fWay = new File(dir, "waypoints.yml"); fPrefs = new File(dir, "compass-data.yml");
        loadGlyphs();
        reload();
        load();
    }

    public void addProvider(PoiProvider p) { providers.add(p); }

    private void loadGlyphs() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                Objects.requireNonNull(plugin.getResource("compass_glyphs.yml")), java.nio.charset.StandardCharsets.UTF_8));
        List<Double> al = y.getDoubleList("alpha");
        if (!al.isEmpty()) { alphaLevels = new double[al.size()]; for (int i = 0; i < al.size(); i++) alphaLevels[i] = al.get(i); }
        var sec = y.getConfigurationSection("glyphs");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) glyphs.put(k, new Glyph(sec.getString(k + ".char", ""), sec.getInt(k + ".width", 1)));
    }

    public void reload() {
        var c = plugin.cfg("compass");
        enabled = c.getBoolean("enabled", true);
        textMode = c.getString("mode", "font").equalsIgnoreCase("text");
        width = c.getInt("width", 180);
        ppd = c.getDouble("pixels-per-degree", 1.5);
        fadeStart = c.getDouble("fade.start", 0.20);
        fadeCurve = Math.max(0.1, c.getDouble("fade.curve", 1.6));
        fadeMin = c.getDouble("fade.min-alpha", 0.10);
        showCoords = c.getBoolean("show-coords", true);
        showSpawn = c.getBoolean("markers.spawn", true);
        showHome = c.getBoolean("markers.home", true);
        showDeath = c.getBoolean("markers.death", true);
        lockAngle = c.getInt("lock-angle", 4);
        maxDist = c.getInt("max-distance", 0);
        if (!enabled) for (Player p : Bukkit.getOnlinePlayers()) hide(p);
    }

    // ───────────── хранение ─────────────
    private void load() {
        global.clear(); personal.clear(); off.clear();
        if (fPois.exists()) for (Map<?, ?> m : YamlConfiguration.loadConfiguration(fPois).getMapList("pois")) { Poi p = fromMap(m); if (p != null) global.add(p); }
        if (fWay.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(fWay);
            for (String k : y.getKeys(false)) {
                try {
                    List<Poi> l = new ArrayList<>();
                    for (Map<?, ?> m : y.getMapList(k)) { Poi p = fromMap(m); if (p != null) l.add(p); }
                    personal.put(UUID.fromString(k), l);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (fPrefs.exists()) for (String s : YamlConfiguration.loadConfiguration(fPrefs).getStringList("off")) {
            try { off.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private static Poi fromMap(Map<?, ?> m) {
        try {
            return new Poi(String.valueOf(m.get("id")), String.valueOf(m.get("name")), String.valueOf(m.get("icon")),
                    String.valueOf(m.get("world")), ((Number) m.get("x")).doubleValue(), ((Number) m.get("y")).doubleValue(), ((Number) m.get("z")).doubleValue());
        } catch (Exception e) { return null; }
    }

    private static Map<String, Object> toMap(Poi p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id()); m.put("name", p.name()); m.put("icon", p.icon()); m.put("world", p.world());
        m.put("x", p.x()); m.put("y", p.y()); m.put("z", p.z());
        return m;
    }

    public void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("pois", global.stream().map(CompassManager::toMap).toList());
            y.save(fPois);
            YamlConfiguration w = new YamlConfiguration();
            for (var e : personal.entrySet()) w.set(e.getKey().toString(), e.getValue().stream().map(CompassManager::toMap).toList());
            w.save(fWay);
            YamlConfiguration pr = new YamlConfiguration();
            pr.set("off", off.stream().map(UUID::toString).toList());
            pr.save(fPrefs);
        } catch (IOException ex) { ex.printStackTrace(); }
    }

    // ───────────── показ ─────────────
    private void hide(Player p) {
        Bars b = bars.remove(p.getUniqueId());
        if (b != null) { p.hideBossBar(b.top()); p.hideBossBar(b.bottom()); }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        bars.remove(id); dynCache.remove(id); dynAge.remove(id);
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) hide(p);
        save();
    }

    @Override
    public void run() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (off.contains(id) || (plugin.map() != null && plugin.map().isOpen(id))) { if (bars.containsKey(id)) hide(p); continue; }
            Bars b = bars.get(id);
            if (b == null) {
                b = new Bars(BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS),
                        BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
                bars.put(id, b);
                p.showBossBar(b.top()); p.showBossBar(b.bottom());
            }
            render(p, b);
        }
    }

    private void render(Player p, Bars b) {
        Location l = p.getLocation();
        double heading = norm360(l.getYaw() + 180.0);
        List<Marker> ms = markers(p, l);
        if (textMode) { b.top().name(textStrip(heading, ms)); b.bottom().name(textBottom(heading, l, ms)); }
        else { b.top().name(strip(heading, ms)); b.bottom().name(bottomLine(l, ms)); }
    }

    // ───────────── маркеры ─────────────
    private List<Marker> markers(Player p, Location l) {
        String w = l.getWorld().getName();
        List<Poi> all = new ArrayList<>();
        for (Poi o : global) if (o.world().equals(w)) all.add(o);
        for (Poi o : personal.getOrDefault(p.getUniqueId(), List.of())) if (o.world().equals(w)) all.add(o);
        for (PoiProvider pr : providers) {
            try { for (Poi o : pr.pois(p)) if (o.world().equals(w)) all.add(o); } catch (Throwable ignored) {}
        }
        for (Poi o : dynamic(p, l.getWorld())) if (o.world().equals(w)) all.add(o);
        List<Marker> out = new ArrayList<>();
        for (Poi o : all) {
            double dx = o.x() - l.getX(), dz = o.z() - l.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (maxDist > 0 && dist > maxDist) continue;
            double bearing = norm360(Math.toDegrees(Math.atan2(dx, -dz)));
            double head = norm360(l.getYaw() + 180.0);
            out.add(new Marker(o, wrap180(bearing - head), dist));
        }
        return out;
    }

    // ───────────── API для полноэкранной карты ─────────────
    /** Все точки, видимые игроку в мире w: общие, личные, от других плагинов, спавн/кровать/смерть. Только из основного потока. */
    public List<Poi> allPois(Player p, World w) {
        String wn = w.getName();
        List<Poi> all = new ArrayList<>();
        for (Poi o : dynamic(p, w)) if (o.world().equals(wn)) all.add(o);
        for (Poi o : global) if (o.world().equals(wn)) all.add(o);
        for (PoiProvider pr : providers) {
            try { for (Poi o : pr.pois(p)) if (o.world().equals(wn)) all.add(o); } catch (Throwable ignored) {}
        }
        for (Poi o : personal.getOrDefault(p.getUniqueId(), List.of())) if (o.world().equals(wn)) all.add(o);
        return all;
    }

    public List<Poi> personalOf(UUID id) { return new ArrayList<>(personal.getOrDefault(id, List.of())); }
    public boolean isPersonal(UUID id, Poi poi) { return personal.getOrDefault(id, List.of()).contains(poi); }
    public int waypointLimit() { return plugin.cfg("compass").getInt("max-waypoints", 10); }

    /** «Точка 1», «Точка 2»… — первое свободное имя. */
    public String freeName(UUID id) {
        List<Poi> l = personal.getOrDefault(id, List.of());
        for (int i = 1; i < 1000; i++) {
            String n = "Точка " + i;
            if (l.stream().noneMatch(o -> o.name().equalsIgnoreCase(n))) return n;
        }
        return "Точка";
    }

    /** Поставить личную точку. Возвращает null при успехе или текст ошибки. */
    public String addPersonal(Player p, String name, String icon, Location loc) {
        List<Poi> l = personal.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        if (l.size() >= waypointLimit()) return "Слишком много точек (максимум " + waypointLimit() + "). Удали ненужные: Shift+ЛКМ на карте.";
        l.removeIf(o -> o.name().equalsIgnoreCase(name));
        l.add(new Poi(name.toLowerCase(Locale.ROOT), name, ICONS.contains(icon) ? icon : "wp", loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
        save();
        return null;
    }

    public boolean removePersonal(UUID id, Poi poi) {
        boolean ok = personal.getOrDefault(id, new ArrayList<>()).remove(poi);
        if (ok) save();
        return ok;
    }

    /** Переименовать личную точку. Возвращает null при успехе или текст ошибки. */
    public String renamePersonal(UUID id, Poi poi, String name) {
        List<Poi> l = personal.get(id);
        if (l == null || !l.contains(poi)) return "Точка уже удалена.";
        if (l.stream().anyMatch(o -> o != poi && !o.equals(poi) && o.name().equalsIgnoreCase(name))) return "Точка с таким названием уже есть.";
        int i = l.indexOf(poi);
        l.set(i, new Poi(name.toLowerCase(Locale.ROOT), name, poi.icon(), poi.world(), poi.x(), poi.y(), poi.z()));
        save();
        return null;
    }

    /** Спавн/кровать/смерть — обновляем раз в 2 секунды. */
    private List<Poi> dynamic(Player p, World w) {
        UUID id = p.getUniqueId();
        int age = dynAge.getOrDefault(id, 0);
        if (age > 0 && dynCache.containsKey(id) && lastWorld.getOrDefault(id, "").equals(w.getName())) {
            dynAge.put(id, age - 1);
            return dynCache.get(id);
        }
        List<Poi> l = new ArrayList<>();
        if (showSpawn) { Location s = w.getSpawnLocation(); l.add(new Poi("spawn", "Спавн", "spawn", w.getName(), s.getX(), s.getY(), s.getZ())); }
        if (showHome) {
            try {
                Location bed = p.getRespawnLocation();
                if (bed != null && bed.getWorld() != null && bed.getWorld().equals(w)) l.add(new Poi("home", "Кровать", "home", w.getName(), bed.getX(), bed.getY(), bed.getZ()));
            } catch (Throwable ignored) {}
        }
        if (showDeath) {
            Location d = p.getLastDeathLocation();
            if (d != null && d.getWorld() != null && d.getWorld().equals(w)) l.add(new Poi("death", "Смерть", "death", w.getName(), d.getX(), d.getY(), d.getZ()));
        }
        dynCache.put(id, l);
        lastWorld.put(id, w.getName());
        dynAge.put(id, 40);
        return l;
    }

    // ───────────── линейка ─────────────
    private static double norm360(double a) { a %= 360.0; return a < 0 ? a + 360.0 : a; }
    private static double wrap180(double a) { a = norm360(a); return a > 180 ? a - 360 : a; }

    /** Уровень прозрачности (0 = полностью виден) для метки на расстоянии x от левого края линейки. */
    private int fadeLevel(double x) {
        double half = width / 2.0;
        double t = (Math.abs(x - half) / half - fadeStart) / (1.0 - fadeStart);
        t = Math.max(0, Math.min(1, t));
        double target = 1.0 - Math.pow(t, fadeCurve) * (1.0 - fadeMin);       // 1.0 в центре -> fadeMin у края
        int best = 0; double bd = 9;
        for (int i = 0; i < alphaLevels.length; i++) { double d = Math.abs(alphaLevels[i] - target); if (d < bd) { bd = d; best = i; } }
        return best;
    }

    /** Символ метки для проверки (/hudtest): name — tick_m, card_N, poi_spawn…; level 0..6 — прозрачность. */
    public String sample(String name, int level) {
        Glyph g = glyphs.get(name);
        return g == null ? "?" : variant(g, Math.min(level, Math.max(0, alphaLevels.length - 1)));
    }

    private String variant(Glyph g, int level) { return level <= 0 ? g.ch() : String.valueOf((char) (g.ch().charAt(0) + 0x40 * level)); }

    private Component strip(double heading, List<Marker> ms) {
        double half = width / 2.0;
        List<Item> items = new ArrayList<>();
        double span = half / ppd;
        int start = (int) Math.floor((heading - span) / 5.0) * 5, end = (int) Math.ceil((heading + span) / 5.0) * 5;
        for (int d = start; d <= end; d += 5) {
            double rel = wrap180(d - heading);
            double x = half + rel * ppd;
            if (x < 0 || x > width) continue;
            int lv = fadeLevel(x);
            int deg = (int) norm360(d);
            String name; int rgb;
            if (deg % 90 == 0) { name = "card_" + "NESW".charAt(deg / 90); rgb = deg == 0 ? 0xFF5555 : 0xFFFFFF; }
            else if (deg % 45 == 0) { name = "inter_" + new String[]{"NE", "SE", "SW", "NW"}[(deg - 45) / 90]; rgb = 0xE0E0E0; }
            else if (deg % 15 == 0) { name = "tick_m"; rgb = 0xF0F0F0; }
            else { name = "tick_s"; rgb = 0xC8C8C8; }
            Glyph g = glyphs.get(name);
            if (g != null) items.add(new Item(x, variant(g, lv), g.w(), TextColor.color(rgb), 0));
        }
        for (Marker m : ms) {
            double x = half + m.rel() * ppd;
            Glyph g = glyphs.get("poi_" + (glyphs.containsKey("poi_" + m.poi().icon()) ? m.poi().icon() : "wp"));
            if (g == null || x < 0 || x > width) continue;
            items.add(new Item(x, variant(g, fadeLevel(x)), g.w(), TextColor.color(0xFFFFFF), 1));
        }
        Glyph c = glyphs.get("center");
        if (c != null) items.add(new Item(half, c.ch(), c.w(), TextColor.color(0xFFFFFF), 2));
        items.sort(Comparator.comparingInt(Item::order).thenComparingDouble(Item::cx));

        Component root = Component.empty();
        int cursor = 0;
        for (Item it : items) {
            int left = (int) Math.round(it.cx() - it.w() / 2.0);
            if (left < 0 || left + it.w() > width) continue;
            root = root.append(Component.text(spaces(left - cursor) + it.glyph()).font(COMPASS).color(it.color())
                    .shadowColor(net.kyori.adventure.text.format.ShadowColor.none()));   // без чёрной тени — иначе у прозрачных меток тень выдаёт «обрезанный» край
            cursor = left + it.w() + 1;
        }
        // назад к началу, затем строка курса цифрами над линейкой (обычный шрифт: цифры/буквы по 6 px, пробел 4)
        root = root.append(Component.text(spaces(-cursor)).font(COMPASS));
        int deg = (int) Math.round(heading) % 360;
        String dir = DIR[(int) Math.round(heading / 45.0) % 8];
        String head = dir + " " + String.format("%03d", deg);
        int tw = head.length() * 6 - 2;              // пробел короче: 4
        int tleft = (int) Math.round(half - tw / 2.0);
        root = root.append(Component.text(spaces(tleft)).font(COMPASS));
        root = root.append(Component.text(head).font(DEFAULT).color(TextColor.color(0xFFFFFF)));
        int after = width - (tleft + tw + 2);
        if (after != 0) root = root.append(Component.text(spaces(after)).font(COMPASS));
        return root;
    }


    // ───────────── ТЕКСТОВЫЙ режим (mode: text) — не требует ресурспака, только обычный шрифт ─────────────
    /** Лента из букв и точек каждые 15°: «· · NW · · N · · NE · ·». Яркость падает к краям (белый → серый → тёмно-серый). */
    private Component textStrip(double heading, List<Marker> ms) {
        StringBuilder sb = new StringBuilder();
        int cells = 6;                                      // 6 клеток по 15° в каждую сторону = ±90°
        int centerDeg = (int) (Math.round(heading / 15.0) * 15);
        for (int i = -cells; i <= cells; i++) {
            int d = (int) norm360(centerDeg + i * 15);
            int a = Math.abs(i);
            String col = a <= 1 ? "§f" : a <= 3 ? "§7" : a <= 4 ? "§8" : "§0";
            String cell;
            if (d % 90 == 0) cell = (d == 0 ? "§c§l" : col + "§l") + "NESW".charAt(d / 90);
            else if (d % 45 == 0) cell = col + new String[]{"NE", "SE", "SW", "NW"}[(d - 45) / 90];
            else cell = col + "·";
            for (Marker m : ms) {                           // маркеры точек — золотая звезда на ближайшей клетке
                double rel = wrap180(d - heading);
                if (Math.abs(wrap180(d - (heading + m.rel()))) < 7.5 && Math.abs(rel) <= 95) { cell = "§e§l✦"; break; }
            }
            sb.append(cell).append(i == cells ? "" : "  ");
        }
        return Component.text(sb.toString());
    }

    private Component textBottom(double heading, Location l, List<Marker> ms) {
        int deg = (int) Math.round(heading) % 360;
        String dir = DIR[(int) Math.round(heading / 45.0) % 8];
        Marker lock = null;
        for (Marker m : ms) if (Math.abs(m.rel()) <= lockAngle && (lock == null || Math.abs(m.rel()) < Math.abs(lock.rel()))) lock = m;
        if (lock != null) return Component.text("§e✦ §f" + lock.poi().name() + "  §7" + dist(lock.dist()));
        String c = showCoords ? "   §cX §f" + l.getBlockX() + "  §aY §f" + l.getBlockY() + "  §9Z §f" + l.getBlockZ() : "";
        return Component.text("§f▲ " + dir + " " + String.format("%03d", deg) + "°" + c);
    }

    private Component bottomLine(Location l, List<Marker> ms) {
        Marker lock = null;
        for (Marker m : ms) if (Math.abs(m.rel()) <= lockAngle && (lock == null || Math.abs(m.rel()) < Math.abs(lock.rel()))) lock = m;
        if (lock != null) {
            String ic = ICON_DEFAULT.getOrDefault(lock.poi().icon(), "\uE360");
            return Component.text("§f" + ic + " §f" + lock.poi().name() + "  §7" + dist(lock.dist()));
        }
        if (!showCoords) return Component.text(" ");
        return Component.text("§cX §f" + l.getBlockX() + "   §aY §f" + l.getBlockY() + "   §9Z §f" + l.getBlockZ());
    }

    private static String dist(double d) { return d < 1000 ? Math.round(d) + " м" : String.format(Locale.ROOT, "%.1f км", d / 1000); }

    /** Сдвиг курсора: положительные/отрицательные «пробелы» шрифта (U+F001.. = +1.., U+F201.. = -1..). */
    static String spaces(int px) {
        if (px == 0) return "";
        StringBuilder sb = new StringBuilder();
        int left = Math.abs(px), base = px > 0 ? 0xF000 : 0xF200;
        while (left > 0) { int n = Math.min(256, left); sb.append((char) (base + n)); left -= n; }
        return sb.toString();
    }

    // ───────────── команды ─────────────
    public boolean command(CommandSender s, String cmd, String[] a) {
        switch (cmd) {
            case "compass" -> {
                if (!(s instanceof Player p)) return true;
                boolean turnOff = a.length > 0 ? a[0].equalsIgnoreCase("off") : !off.contains(p.getUniqueId());
                if (turnOff) { off.add(p.getUniqueId()); hide(p); } else off.remove(p.getUniqueId());
                s.sendMessage("§6Компас: " + (turnOff ? "§cвыключен" : "§aвключен"));
                save();
            }
            case "waypoint" -> {
                if (!(s instanceof Player p)) return true;
                List<Poi> l = personal.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
                String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "list";
                switch (sub) {
                    case "add" -> {
                        if (a.length < 2) { s.sendMessage("§c/waypoint add <название> [значок: " + String.join("|", ICONS) + "]"); return true; }
                        String icon = a.length > 2 && ICONS.contains(a[a.length - 1]) ? a[a.length - 1] : "wp";
                        int end = a.length > 2 && ICONS.contains(a[a.length - 1]) ? a.length - 1 : a.length;
                        String name = String.join(" ", Arrays.copyOfRange(a, 1, end));
                        if (l.size() >= plugin.cfg("compass").getInt("max-waypoints", 10)) { s.sendMessage("§cСлишком много точек (лимит в config.yml)."); return true; }
                        l.removeIf(o -> o.name().equalsIgnoreCase(name));
                        Location loc = p.getLocation();
                        l.add(new Poi(name.toLowerCase(Locale.ROOT), name, icon, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
                        save(); s.sendMessage("§aТочка «" + name + "» поставлена.");
                    }
                    case "del" -> {
                        String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                        boolean ok = l.removeIf(o -> o.name().equalsIgnoreCase(name));
                        save(); s.sendMessage(ok ? "§aУдалено." : "§cНет такой точки.");
                    }
                    case "clear" -> { l.clear(); save(); s.sendMessage("§aВсе личные точки удалены."); }
                    default -> {
                        if (l.isEmpty()) s.sendMessage("§7Личных точек нет. §f/waypoint add <название> [значок]");
                        for (Poi o : l) s.sendMessage("§e" + o.name() + " §7(" + o.world() + " " + (int) o.x() + " " + (int) o.y() + " " + (int) o.z() + ")");
                    }
                }
            }
            case "hudpoi" -> {
                String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "list";
                switch (sub) {
                    case "add" -> {
                        if (!(s instanceof Player p)) return true;
                        if (a.length < 3) { s.sendMessage("§c/hudpoi add <id> <значок: " + String.join("|", ICONS) + "> [название...]  (в твоей позиции)"); return true; }
                        String icon = ICONS.contains(a[2]) ? a[2] : "wp";
                        String name = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : a[1];
                        global.removeIf(o -> o.id().equalsIgnoreCase(a[1]));
                        Location loc = p.getLocation();
                        global.add(new Poi(a[1], name, icon, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
                        save(); s.sendMessage("§aОбщая точка «" + name + "» добавлена (видна всем).");
                    }
                    case "del" -> {
                        boolean ok = a.length > 1 && global.removeIf(o -> o.id().equalsIgnoreCase(a[1]));
                        save(); s.sendMessage(ok ? "§aУдалено." : "§cНет такой точки.");
                    }
                    default -> {
                        if (global.isEmpty()) s.sendMessage("§7Общих точек нет.");
                        for (Poi o : global) s.sendMessage("§e" + o.id() + " §7— " + o.name() + " [" + o.icon() + "] " + o.world() + " " + (int) o.x() + " " + (int) o.y() + " " + (int) o.z());
                    }
                }
            }
            default -> { return false; }
        }
        return true;
    }
}
