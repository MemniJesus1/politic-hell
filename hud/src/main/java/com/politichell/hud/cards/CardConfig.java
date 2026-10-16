package com.politichell.hud.cards;

import com.politichell.hud.PoliticHellHud;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryType;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Все настройки карточек: cards.yml, items.yml (свои предметы), vanilla.yml (правки ванильных), встроенные описания и русские названия. */
public final class CardConfig {
    public record BindStyle(String name, TextColor color, String text) {}
    public record Custom(String id, Material base, String name, String rarity, Binding binding, String icon, List<String> desc, List<String> stats,
                         boolean autoStats, List<String> attributes, long ttl, String event, String itemModel, int cmd, boolean glow, boolean unbreakable, String bindOn) {}
    /** Правка ванильного предмета; null-поля = «авто». */
    public record Override(String name, String rarity, Binding binding, String icon, List<String> desc, List<String> stats, String bindOn) {}
    public record Note(String rarity, String desc) {}
    /** Массовое правило из groups.yml: все предметы, подходящие под шаблон/категорию. null-поля = не менять. */
    public record Group(java.util.regex.Pattern pat, List<java.util.regex.Pattern> except, String category, String rarity, Binding binding, String icon, String text, String bindOn) {}

    private final PoliticHellHud plugin;
    public final File dir;
    public int gen = 0;                                   // версия настроек: меняется при reload → предметы обновляют оформление

    public boolean enabled = true, foreign = false, hideAttributes = true, showOwner = true, unbreakableBound = true, dividerGlyph = true, toggleShiftRight = true;
    public boolean defaultFull = false;
    public CardStyle defaultStyle = CardStyle.CLASSIC;
    /** Цвета плашек по разделам (desc / stats / ench / effects / info) — стили «Широкая» и «Терминал». */
    public final Map<String, TextColor> tints = new HashMap<>();
    public int scanTicks = 10, compactLines = 2, width = 126;
    public TextColor descColor, statLabel, statValue, statPlus, statMinus, hintColor;
    public String stylePrefix = "politichell:card_", denyMessage = "&cЭтот предмет привязан к тебе — так нельзя";
    public boolean bypassCreative = false, blockEntityGive = true, blockBlockInsert = true, tridentOwnerOnly = true;
    public final Set<InventoryType> personalAllowed = EnumSet.noneOf(InventoryType.class);
    public final Map<String, Rarity> rarities = new LinkedHashMap<>();
    public final Map<Binding, BindStyle> bindings = new EnumMap<>(Binding.class);
    public final Map<String, Custom> custom = new LinkedHashMap<>();
    public final Map<String, Override> vanilla = new HashMap<>();
    public final Map<String, Note> notes = new HashMap<>();
    public final List<Group> groups = new ArrayList<>();
    private final Map<Material, Override> ovCache = new HashMap<>();
    public final Map<String, String> ruNames = new HashMap<>();

    public CardConfig(PoliticHellHud plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "cards");
    }

    private YamlConfiguration yaml(String name, boolean withDefaults) {
        File f = new File(dir, name);
        if (!f.exists()) plugin.saveResource("cards/" + name, false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        if (withDefaults) {
            try (InputStream in = plugin.getResource("cards/" + name)) {
                if (in != null) y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (Exception ignored) {}
        }
        return y;
    }

    /** pickup | equip | use — когда свободный предмет сам привязывается; none/пусто — никогда. */
    static String bindOn(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "pickup", "подбор", "подбор предмета", "take" -> "pickup";
            case "equip", "надеть", "надевание", "wear" -> "equip";
            case "use", "использование", "использовать" -> "use";
            case "none", "", "нет", "off" -> "none";
            default -> null;
        };
    }

    private static TextColor col(String s, String def) {
        TextColor c = s == null ? null : TextColor.fromHexString(s.trim());
        return c != null ? c : TextColor.fromHexString(def);
    }

    private static List<String> strs(ConfigurationSection s, String key) {
        if (s == null || !s.contains(key)) return null;
        if (s.isList(key)) return new ArrayList<>(s.getStringList(key));
        String v = s.getString(key);
        return v == null || v.isBlank() ? List.of() : new ArrayList<>(List.of(v.split("\\|\\|")));   // «||» — перенос в одной строке
    }

    public void load() {
        dir.mkdirs();
        gen++;
        ovCache.clear();
        YamlConfiguration c = yaml("cards.yml", true);
        enabled = c.getBoolean("enabled", true);
        scanTicks = Math.max(2, c.getInt("scan-ticks", 10));
        foreign = c.getBoolean("decorate-foreign", false);
        defaultFull = "full".equalsIgnoreCase(c.getString("detail.default", "compact"));
        toggleShiftRight = "shift-right".equalsIgnoreCase(c.getString("detail.toggle-click", "shift-right"));
        compactLines = Math.max(1, c.getInt("detail.compact-lines", 2));
        width = Math.max(60, c.getInt("layout.width", 126));
        dividerGlyph = !"text".equalsIgnoreCase(c.getString("layout.divider", "glyph"));
        defaultStyle = CardStyle.parse(c.getString("layout.style", "classic"), CardStyle.CLASSIC);
        tints.clear();
        for (String k : new String[]{"desc", "stats", "ench", "effects", "info"})
            tints.put(k, col(c.getString("layout.plates." + k), switch (k) { case "desc" -> "#4A78E0"; case "stats" -> "#36B4C8"; case "ench" -> "#9A66F0"; case "effects" -> "#48C078"; default -> "#8A90AE"; }));
        descColor = col(c.getString("layout.desc-color"), "#B4C6EA");
        statLabel = col(c.getString("layout.stat-label"), "#9DB4D8");
        statValue = col(c.getString("layout.stat-value"), "#FFFFFF");
        statPlus = col(c.getString("layout.stat-plus"), "#6BE07A");
        statMinus = col(c.getString("layout.stat-minus"), "#FF6B6B");
        hintColor = col(c.getString("layout.hint-color"), "#6E6E7A");
        stylePrefix = c.getString("layout.tooltip-style-prefix", "politichell:card_");
        hideAttributes = c.getBoolean("layout.hide-vanilla-attributes", true);
        showOwner = c.getBoolean("layout.show-owner", true);
        unbreakableBound = c.getBoolean("rules.unbreakable-bound", true);
        denyMessage = c.getString("rules.deny-message", denyMessage);
        bypassCreative = c.getBoolean("rules.bypass-creative", false);
        blockEntityGive = c.getBoolean("rules.block-entity-give", true);
        blockBlockInsert = c.getBoolean("rules.block-block-insert", true);
        tridentOwnerOnly = c.getBoolean("rules.trident-owner-only", true);
        personalAllowed.clear();
        for (String s : c.getStringList("rules.personal-allowed")) {
            try { personalAllowed.add(InventoryType.valueOf(s.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("cards.yml: неизвестный тип окна «" + s + "» в rules.personal-allowed"); }
        }
        rarities.clear();
        ConfigurationSection rs = c.getConfigurationSection("rarities");
        if (rs != null) for (String id : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(id);
            if (r == null) continue;
            List<TextColor> stops = new ArrayList<>();
            for (String h : r.getStringList("stops")) { TextColor t = TextColor.fromHexString(h.trim()); if (t != null) stops.add(t); }
            if (stops.isEmpty()) stops.add(TextColor.fromHexString("#FFFFFF"));
            if (stops.size() == 1) stops.add(stops.get(0));
            rarities.put(id.toLowerCase(Locale.ROOT), new Rarity(id.toLowerCase(Locale.ROOT), r.getString("name", id), stops));
        }
        if (!rarities.containsKey("common")) rarities.put("common", new Rarity("common", "Обычный", List.of(TextColor.fromHexString("#F4F4F8"), TextColor.fromHexString("#A9AAB4"))));
        bindings.clear();
        for (Binding b : Binding.values()) {
            ConfigurationSection s = c.getConfigurationSection("bindings." + b.id);
            bindings.put(b, new BindStyle(s != null ? s.getString("name", b.id) : b.id, col(s != null ? s.getString("color") : null, "#9EA3B0"),
                    s != null ? s.getString("text", "") : ""));
        }

        // ── русские названия и встроенные описания ──
        if (ruNames.isEmpty()) {
            try (InputStream in = plugin.getResource("cards/ru_names.json")) {
                if (in != null) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    var m = java.util.regex.Pattern.compile("\"([A-Z0-9_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
                    while (m.find()) ruNames.put(m.group(1), m.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
                }
            } catch (Exception e) { plugin.getLogger().warning("cards/ru_names.json: " + e); }
        }
        notes.clear();
        try (InputStream in = plugin.getResource("cards/vanilla_notes.yml")) {
            if (in != null) {
                YamlConfiguration n = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String k : n.getKeys(false)) { ConfigurationSection s = n.getConfigurationSection(k); if (s != null) notes.put(k.toUpperCase(Locale.ROOT), new Note(s.getString("rarity"), s.getString("desc"))); }
            }
        } catch (Exception e) { plugin.getLogger().warning("cards/vanilla_notes.yml: " + e); }

        // ── свои предметы ──
        custom.clear();
        YamlConfiguration it = yaml("items.yml", false);
        ConfigurationSection is = it.getConfigurationSection("items");
        if (is != null) for (String id : is.getKeys(false)) {
            ConfigurationSection s = is.getConfigurationSection(id);
            if (s == null) continue;
            Material base = Material.matchMaterial(s.getString("base", "PAPER"));
            if (base == null || !base.isItem()) { plugin.getLogger().warning("items.yml: предмет «" + id + "» — неизвестный материал base: " + s.getString("base")); continue; }
            List<String> desc = strs(s, "description"), stats = strs(s, "stats"), attrs = strs(s, "attributes");
            custom.put(id.toLowerCase(Locale.ROOT), new Custom(id.toLowerCase(Locale.ROOT), base, s.getString("name"), s.getString("rarity"),
                    Binding.parse(s.getString("binding"), Binding.CONSUMABLE), s.getString("icon"),
                    desc == null ? List.of() : desc, stats == null ? List.of() : stats, s.getBoolean("auto-stats", true), attrs == null ? List.of() : attrs,
                    s.getLong("ttl", 0), s.getString("event"), s.getString("item-model"), s.getInt("custom-model-data", 0),
                    s.getBoolean("glow", false), s.getBoolean("unbreakable", false), bindOn(s.getString("bind-on"))));
        }

        // ── правки ванильных (vanilla.yml) ──
        vanilla.clear();
        File vf = new File(dir, "vanilla.yml");
        if (!vf.exists()) {
            try {
                java.nio.file.Files.writeString(vf.toPath(), "# Правки ОБЫЧНЫХ предметов Minecraft. Заполнить всеми предметами сразу: /card export\n"
                        + "# Формат:  МАТЕРИАЛ: { name: \"Название\", rarity: rare, binding: consumable, icon: weapon, desc: [\"строка\", \"строка\"], stats: [\"Урон: 8\"] }\n"
                        + "# Пишешь только то, что хочешь изменить; остальное подставится само. Пример:\n"
                        + "# DIAMOND_SWORD:\n#   rarity: epic\n#   desc: [\"Меч, проверенный в боях.\"]\n", StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        YamlConfiguration v = YamlConfiguration.loadConfiguration(vf);
        for (String k : v.getKeys(false)) {
            ConfigurationSection s = v.getConfigurationSection(k);
            if (s == null) continue;
            vanilla.put(k.toUpperCase(Locale.ROOT), new Override(s.getString("name"), s.getString("rarity"),
                    s.contains("binding") ? Binding.parse(s.getString("binding"), Binding.CONSUMABLE) : null, s.getString("icon"), strs(s, "desc"), strs(s, "stats"), bindOn(s.getString("bind-on"))));
        }
        loadGroups();
    }

    private static java.util.regex.Pattern glob(String g) {
        String[] parts = g.trim().toUpperCase(Locale.ROOT).split("\\*", -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) { if (i > 0) b.append(".*"); if (!parts[i].isEmpty()) b.append(java.util.regex.Pattern.quote(parts[i])); }
        return java.util.regex.Pattern.compile(b.toString());
    }

    private void loadGroups() {
        groups.clear();
        YamlConfiguration y = yaml("groups.yml", false);
        for (Map<?, ?> m : y.getMapList("groups")) {
            try {
                Object mt = m.get("match");
                List<String> pats = new ArrayList<>();
                if (mt instanceof List<?> l) for (Object o : l) pats.add(String.valueOf(o)); else if (mt != null) pats.add(String.valueOf(mt));
                String cat = m.get("category") == null ? null : String.valueOf(m.get("category")).toLowerCase(Locale.ROOT);
                if (pats.isEmpty() && cat == null) continue;
                // несколько шаблонов склеиваем в один через |
                java.util.regex.Pattern pat = pats.isEmpty() ? null : java.util.regex.Pattern.compile(String.join("|", pats.stream().map(x -> "(?:" + glob(x).pattern() + ")").toList()));
                List<java.util.regex.Pattern> ex = new ArrayList<>();
                if (m.get("except") instanceof List<?> l) for (Object o : l) ex.add(glob(String.valueOf(o)));
                Binding b = m.get("binding") == null ? null : Binding.parse(String.valueOf(m.get("binding")), null);
                groups.add(new Group(pat, ex, cat, m.get("rarity") == null ? null : String.valueOf(m.get("rarity")).toLowerCase(Locale.ROOT), b,
                        m.get("icon") == null ? null : String.valueOf(m.get("icon")).toLowerCase(Locale.ROOT), m.get("text") == null ? null : String.valueOf(m.get("text")), bindOn(m.get("bind-on") == null ? null : String.valueOf(m.get("bind-on")))));
            } catch (Exception e) { plugin.getLogger().warning("groups.yml: плохое правило " + m + " — " + e.getMessage()); }
        }
    }

    /** Итоговые правки предмета: vanilla.yml (точечные) → массовые правила groups.yml → авто. Точечное всегда сильнее массового. */
    public Override ov(Material m) {
        Override cached = ovCache.get(m);
        if (cached != null || ovCache.containsKey(m)) return cached;
        Override ex = vanilla.get(m.name());
        String rar = null, ic = null, bo = null; Binding bd = null;
        String n = m.name(), cat = null;
        for (Group g : groups) {
            if (g.pat() != null && !g.pat().matcher(n).matches()) continue;
            if (g.category() != null) { if (cat == null) cat = VanillaInfo.category(m); if (!g.category().equals(cat)) continue; }
            boolean skip = false;
            for (var e : g.except()) if (e.matcher(n).matches()) { skip = true; break; }
            if (skip) continue;
            if (g.rarity() != null) rar = g.rarity();
            if (g.icon() != null) ic = g.icon();
            if (g.binding() != null) bd = g.binding();
            if (g.bindOn() != null) bo = g.bindOn().equals("none") ? "" : g.bindOn();
        }
        Override r;
        if (rar == null && ic == null && bd == null && bo == null) r = ex;
        else if (ex == null) r = new Override(null, rar, bd, ic, null, null, bo == null || bo.isEmpty() ? null : bo);
        else r = new Override(ex.name(), ex.rarity() != null ? ex.rarity() : rar, ex.binding() != null ? ex.binding() : bd, ex.icon() != null ? ex.icon() : ic, ex.desc(), ex.stats(), ex.bindOn() != null ? ex.bindOn() : bo == null || bo.isEmpty() ? null : bo);
        ovCache.put(m, r);
        return r;
    }

    public Rarity rarity(String id) {
        Rarity r = id == null ? null : rarities.get(id.toLowerCase(Locale.ROOT));
        return r != null ? r : rarities.get("common");
    }
}
