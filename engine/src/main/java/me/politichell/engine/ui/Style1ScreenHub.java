package me.politichell.engine.ui;

import me.politichell.engine.PoliticHellEngine;
import me.politichell.engine.Style1Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * POLITICHELL ENGINE 1.16.0 — Движок экранных 3D-меню «Стиль 1.1».
 *
 * Вкладки: [⚜ ОБЛОЖКА] [⚔ АРСЕНАЛ 3D] [🛡 ЭКИПИРОВКА] [📦 ЯЩИКИ] [◈ ШТАБ & ЭПОХИ].
 *
 * Управление: мышь → виртуальный курсор; ЛКМ — клик; колесо — скролл/зум;
 * Shift — закрыть; F (swap-hand) — открыть/закрыть ПДА.
 */
public final class Style1ScreenHub implements Listener, CommandExecutor {

    public enum Tab { HUB, ARSENAL, WARDROBE, SUPPLY_CRATE, TECH_TREE }

    // ─────────────── ДАННЫЕ ───────────────

    private static final String[] TAB_TITLES = {
        "⚜ ОБЛОЖКА", "⚔ АРСЕНАЛ 3D", "🛡 ЭКИПИРОВКА", "📦 ЯЩИКИ", "◈ ШТАБ"
    };
    private static final double[] TAB_X = { -0.50, -0.25, 0.0, 0.25, 0.50 };

    private static final String[] HUB_ACTIONS = {
        "▶ В БОЙ (на спавн)",
        "🗺 Карта фронтов",
        "🏛 Парламент",
        "◆ Магазин поддержки",
        "⚙ Настройки"
    };
    private static final String[] HUB_SUBS = {
        "телепорт на спавн мира",
        "HOI4-слой: в Phase 1",
        "выборы/законы: Phase 2",
        "косметика · без pay-to-win",
        "HUD/миникарта/курсор: Phase 1"
    };

    private static final String[][] WEAPONS = {
        { "Офицерский Клинок", "Ближний бой · Эпоха 3", "NETHERITE_SWORD",
          "Закалённая сталь: пробивает лёгкую броню.",
          "Гарда Стиля 1: +10% к смене позиции.", "Редкий", "8 000 ₴" },
        { "Полевой Топор Инженера", "Инженерное · разбор укреплений", "NETHERITE_AXE",
          "Бронебойное лезвие: +40% урона по баррикадам.",
          "Длинное топорище: замах и баланс.", "Промышленный", "6 500 ₴" },
        { "Штурмовой Арбалет М1", "Стрелковое · осада", "CROSSBOW",
          "Усиленная дуга: пробивает тяжёлую броню.",
          "Приклад с упором: −25% разброса.", "Промышленный", "9 000 ₴" },
        { "Винтовка «Кар-73»", "Стрелковое · Эпоха 5 (макет)", "IRON_HOE",
          "Нарезной ствол: кучность на 80 м.",
          "Ствольная коробка: быстрая смена обоймы.", "Штучный", "22 000 ₴" },
        { "Противотанковый Молот", "Тяжёлое · прорыв", "MACE",
          "Ударное ядро: ломает укрепления и технику.",
          "Стальная рукоять с амортизацией.", "Редкий", "14 000 ₴" }
    };

    private static final String[][] SETS = {
        { "Разведчик «Призрак»",    "Лёгкая броня · мобильность",       "LEATHER" },
        { "Штурмовая форма",        "Средняя защита · подсумок",         "IRON" },
        { "Гвардейская броня",      "Тяжёлая · от осколков",             "DIAMOND" },
        { "Имперский Джаггернаут",  "Сверхтяжёлый комплект",             "NETHERITE" },
        { "Парадная форма",         "Косметический костюм",              "CHAINMAIL" }
    };

    private record CrateDef(String id, String title, String desc, int price, double[] w) {}

    private static final CrateDef[] CRATES = {
        new CrateDef("field", "📦 Полевой ящик", "Медикаменты, расходники, патроны",
            500, new double[]{ 0.55, 0.28, 0.12, 0.04, 0.01, 0.0 }),
        new CrateDef("officer", "📦 Офицерский контейнер", "Вооружение с карточками редкости",
            2500, new double[]{ 0.30, 0.35, 0.22, 0.10, 0.03, 0.0 }),
        new CrateDef("trophy", "📦 Трофейный «Орды»", "Редкое трофейное оружие",
            5000, new double[]{ 0.10, 0.10, 0.24, 0.35, 0.18, 0.03 }),
        new CrateDef("imperial", "📦 Имперский Арсенал", "Гарантированно редкое",
            10000, new double[]{ 0.0, 0.08, 0.25, 0.40, 0.24, 0.03 })
    };

    private static final String[] RARITY_NAMES = {
        "Обычный", "Необычный", "Редкий", "Эпик", "Легендарный", "★ Мифик"
    };
    private static final TextColor[] RARITY_COLORS = {
        NamedTextColor.WHITE, NamedTextColor.GREEN, NamedTextColor.AQUA,
        NamedTextColor.LIGHT_PURPLE, NamedTextColor.GOLD, NamedTextColor.DARK_RED
    };

    /** name | material | rarityIndex | note */
    private static final String[][] POOL = {
        { "Полевой бинт",           "PAPER",                    "0", "+15 HP за 2 сек" },
        { "Паёк хелли",             "COOKED_BEEF",              "0", "плотный, сытный" },
        { "Обойма 9×18",            "IRON_NUGGET",              "0", "30 патронов" },
        { "Стрелы ×16",             "ARROW",                    "0", "унифиц. болты" },
        { "Компенсатор отдачи",     "REDSTONE_TORCH",           "1", "обвес: ствол" },
        { "Оптика 4×",              "SPYGLASS",                 "1", "обвес: прицел" },
        { "Расширенный магазин",    "BOOK",                     "1", "обвес: магазин" },
        { "Стальной жилет",         "CHAINMAIL_CHESTPLATE",     "1", "броня Т2" },
        { "Винтовка «Кар-73»",      "IRON_HOE",                 "2", "Эпоха 5 · 7.62×39" },
        { "Револьвер «Империал»",   "FLINT_AND_STEEL",          "2", "Эпоха 3 · кобурный" },
        { "Пулемёт (стаци.)",       "BOW",                      "2", "осадная точка" },
        { "СВД «Гвардиец»",         "NETHERRACK",               "3", "Эпоха 6 · марксменка" },
        { "Джаггер-нагрудник",      "GOLDEN_CHESTPLATE",        "3", "броня Т4" },
        { "Чертёж: ДОТ",            "MAP",                      "3", "инженерная доктрина" },
        { "ПТРК «Корнет»",          "TRIDENT",                  "4", "Эпоха 7 · тандерболт" },
        { "Имперская Печать",       "NETHER_STAR",              "4", "артефакт державы" }
    };

    private static final String[][] TREE = {
        { "Век I: Железо",  "Ремесло",        "Земледелие",      "Литьё",      "Гончарное" },
        { "Век II: Форт",   "Каменные форты", "Осадные мосты",   "Религия",    "Провиант" },
        { "Век III: Порох", "Мушкеты",        "Артиллерия",      "Банки",      "Телеграф" },
        { "Век IV: Индуст.", "Заводы",        "Рельсы",          "Логистика",  "Карбид" },
        { "Век V: Война",   "Винтовки",       "Пулемёты",        "Пропаганда", "Партизаны" }
    };
    private static final int[][] TREE_COST = {
        { 0, 3000, 3000, 2000, 2000 },
        { 8000, 12000, 12000, 6000, 6000 },
        { 20000, 30000, 40000, 15000, 15000 },
        { 60000, 90000, 120000, 50000, 50000 },
        { 150000, 200000, 250000, 80000, 80000 }
    };

    private static final int PITY_GOAL = 40;

    // ─────────────── СЕССИЯ ───────────────

    public static class Session {
        Player player;
        Location anchorEye;
        Vector forward = new Vector(0, 0, 1);
        Vector right = new Vector(1, 0, 0);
        Vector up = new Vector(0, 1, 0);

        double cx = 0, cy = 0;
        Tab tab = Tab.HUB;
        int sel = 0;
        int hovered = -1;
        boolean rolling = false;
        double zoom = 1.0;

        TextDisplay canvasBg;
        TextDisplay cursor;
        TextDisplay detailPanel;
        TextDisplay partTooltip;
        ItemDisplay model3D;
        Mannequin soldier;

        final List<TextDisplay> tabs = new ArrayList<>();
        final List<TextDisplay> list = new ArrayList<>();
        final List<TextDisplay> treeNodes = new ArrayList<>();
        final List<String> treeIds = new ArrayList<>();
        final List<Entity> spawned = new ArrayList<>();
        BukkitTask animTask;
    }

    private final PoliticHellEngine plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastRoll = new HashMap<>();
    private final Random rnd = new Random();

    public Style1ScreenHub(PoliticHellEngine plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ─────────────── ЧИСТКА «ПРИЗРАКОВ» ───────────────

    private NamespacedKey tagKey() {
        return new NamespacedKey(plugin, "phhud_ui_entity");
    }

    public void purgeOrphans() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(tagKey(), PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoaded(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (entity.getPersistentDataContainer().has(tagKey(), PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
    }

    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) close(p.getUniqueId());
    }

    // ─────────────── КОМАНДЫ ───────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[PoliticHellEngine] only players");
            return true;
        }
        Tab tab = switch (label.toLowerCase(Locale.ROOT)) {
            case "arsenal" -> Tab.ARSENAL;
            case "equip"   -> Tab.WARDROBE;
            case "crates"  -> Tab.SUPPLY_CRATE;
            case "strana"  -> Tab.TECH_TREE;
            default        -> Tab.HUB;
        };
        open(player, tab);
        return true;
    }

    // ─────────────── ОТКРЫТИЕ / ЗАКРЫТИЕ ───────────────

    public void open(Player player, Tab tab) {
        close(player.getUniqueId());

        Session s = new Session();
        s.player = player;
        s.tab = tab;

        Location loc = player.getLocation().clone();
        loc.setPitch(0);
        loc.setYaw((float) (Math.round(loc.getYaw() / 90.0) * 90.0));
        player.teleport(loc);

        Location eye = loc.clone().add(0, player.getEyeHeight(), 0);
        s.anchorEye = eye;
        Vector f = s.anchorEye.getDirection().setY(0).normalize();
        s.forward = f;
        s.right = f.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        s.up = new Vector(0, 1, 0);

        s.canvasBg = spawnText(s, 0, -0.36, 1.02, d -> {
            d.text(Component.text(" ".repeat(55) + "\n" + "\n".repeat(18)));
            d.setBackgroundColor(Style1Theme.BG_DARK);
            d.setTransformation(scale(2.65f, 1.52f, 1f));
        });

        for (int i = 0; i < 5; i++) {
            int idx = i;
            s.tabs.add(spawnText(s, TAB_X[idx], 0.34, 0.96, d -> {
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setTransformation(scale(0.50f, 0.50f, 0.50f));
            }));
        }

        for (int i = 0; i < 5; i++) {
            double y = 0.16 - (i * 0.125);
            s.list.add(spawnText(s, -0.36, y, 0.96, d -> {
                d.setAlignment(TextDisplay.TextAlignment.LEFT);
                d.setLineWidth(175);
                d.setTransformation(scale(0.54f, 0.54f, 0.54f));
            }));
        }

        s.detailPanel = spawnText(s, 0.30, 0.17, 0.96, d -> {
            d.setAlignment(TextDisplay.TextAlignment.LEFT);
            d.setLineWidth(195);
            d.setBackgroundColor(Style1Theme.BG_CARD);
            d.setTransformation(scale(0.52f, 0.52f, 0.52f));
        });

        s.model3D = spawnItem(s, 0.30, -0.10, 0.90);

        s.partTooltip = spawnText(s, 0.48, -0.10, 0.87, d -> {
            d.setAlignment(TextDisplay.TextAlignment.LEFT);
            d.setLineWidth(150);
            d.setBackgroundColor(Style1Theme.TRANSPARENT_BG);
            d.setTransformation(scale(0.45f, 0.45f, 0.45f));
            d.text(Component.empty());
        });

        s.cursor = spawnText(s, 0, 0, 0.84, d -> {
            d.text(Component.text("↖", Style1Theme.GOLD, TextDecoration.BOLD));
            d.setBackgroundColor(Style1Theme.TRANSPARENT_BG);
            d.setTransformation(scale(0.85f, 0.85f, 0.85f));
            d.setInterpolationDuration(1);
        });

        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.5f, 1.4f);
        player.sendActionBar(Component.text(
            "Мышь — курсор · ЛКМ — выбор · Колесо — скролл · Shift — закрыть",
            Style1Theme.GOLD));

        s.animTask = new BukkitRunnable() {
            float angle = 0f;
            @Override
            public void run() {
                if (!player.isOnline()) {
                    close(player.getUniqueId());
                    return;
                }
                angle += 0.045f;
                if (!s.rolling && s.model3D != null && s.model3D.isValid()) {
                    s.model3D.setInterpolationDelay(0);
                    s.model3D.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0), new AxisAngle4f(),
                        new Vector3f(0.38f, 0.38f, 0.38f),
                        new AxisAngle4f(angle, 0, 1, 0)));
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);

        sessions.put(player.getUniqueId(), s);
        render(s);
    }

    public void close(UUID id) {
        Session s = sessions.remove(id);
        if (s == null) return;
        if (s.animTask != null) s.animTask.cancel();
        for (Entity e : s.spawned) e.remove();
    }

    // ─────────────── РЕНДЕР ───────────────

    private void render(Session s) {
        for (int i = 0; i < 5; i++) {
            boolean active = (s.tab.ordinal() == i);
            boolean hov = (s.hovered == i);
            TextDisplay b = s.tabs.get(i);
            b.setBackgroundColor(active ? Style1Theme.BG_CARD_HOVER
                    : (hov ? Style1Theme.BG_CARD : Style1Theme.BG_DIM));
            b.text(Component.text(" " + TAB_TITLES[i] + " ",
                    active ? Style1Theme.GOLD : (hov ? NamedTextColor.WHITE : Style1Theme.STEEL),
                    active ? Set.of(TextDecoration.BOLD) : Set.of()));
        }

        if (s.tab != Tab.HUB && s.tab != Tab.WARDROBE && s.soldier != null) {
            s.soldier.remove();
            s.soldier = null;
        }
        if (s.tab != Tab.TECH_TREE) clearTree(s);
        s.partTooltip.setBackgroundColor(Style1Theme.TRANSPARENT_BG);
        s.partTooltip.text(Component.empty());

        switch (s.tab) {
            case HUB -> renderHub(s);
            case ARSENAL -> renderArsenal(s);
            case WARDROBE -> renderWardrobe(s);
            case SUPPLY_CRATE -> renderCrate(s);
            case TECH_TREE -> renderTree(s);
        }
    }

    private void renderHub(Session s) {
        for (int i = 0; i < 5; i++) {
            TextDisplay b = s.list.get(i);
            boolean hov = (s.hovered == i);
            b.setBackgroundColor(hov || s.sel == i ? Style1Theme.BG_CARD_HOVER : Style1Theme.BG_DIM);
            b.text(Component.text("▌ ", Style1Theme.GOLD)
                .append(Component.text(HUB_ACTIONS[i] + "\n", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  " + HUB_SUBS[i], Style1Theme.STEEL)));
        }

        spawnSoldier(s, 0.02, -0.32, 0.92);
        if (s.soldier != null) {
            EntityEquipment eq = s.soldier.getEquipment();
            EntityEquipment src = s.player.getEquipment();
            if (eq != null && src != null) {
                eq.setHelmet(src.getHelmet());
                eq.setChestplate(src.getChestplate());
                eq.setLeggings(src.getLeggings());
                eq.setBoots(src.getBoots());
            }
        }

        double coins = plugin.getCoins(s.player);
        String nation = plugin.territoryBridge().nationNameOf(s.player.getUniqueId());
        s.detailPanel.text(Component.text("ПОРТРЕТ БОЙЦА\n", Style1Theme.GOLD, TextDecoration.BOLD)
            .append(Component.text("Гражданин: " + s.player.getName() + "\n", NamedTextColor.WHITE))
            .append(Component.text("Держава: "
                + (nation == null ? "Вольный стрелок" : nation) + "\n", NamedTextColor.WHITE))
            .append(Component.text("Класс: — (Phase 2, ClassEngine)\n", Style1Theme.STEEL))
            .append(Component.text("Баланс: " + String.format(Locale.US, "%,.0f", coins) + " ₴\n",
                Style1Theme.STEEL))
            .append(Component.text("Онлайн: " + Bukkit.getOnlinePlayers().size() + " / "
                + Bukkit.getMaxPlayers(), Style1Theme.STEEL)));
        s.model3D.setItemStack(new ItemStack(Material.AIR));
    }

    private void renderArsenal(Session s) {
        for (int i = 0; i < 5; i++) {
            TextDisplay b = s.list.get(i);
            boolean sel = (s.sel == i);
            boolean hov = (s.hovered == 5 + i);
            b.setBackgroundColor(sel ? Style1Theme.BG_CARD_HOVER
                    : (hov ? Style1Theme.BG_CARD : Style1Theme.BG_DIM));
            b.text(Component.text("▌ ", sel ? Style1Theme.GOLD : Style1Theme.STEEL)
                .append(Component.text(WEAPONS[i][0] + "\n", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  " + WEAPONS[i][1], Style1Theme.STEEL)));
        }

        String[] cur = WEAPONS[s.sel];
        Material m = Material.matchMaterial(cur[2]);
        if (m != null) s.model3D.setItemStack(new ItemStack(m));
        s.detailPanel.text(Component.text("⚜ " + cur[0] + "\n", Style1Theme.GOLD, TextDecoration.BOLD)
            .append(Component.text(cur[1] + "\n", Style1Theme.STEEL))
            .append(Component.text("Редкость: " + cur[5] + "\n", Style1Theme.STEEL))
            .append(Component.text("Стоимость: " + cur[6] + "\n\n", Style1Theme.STEEL))
            .append(Component.text("Наведи курсор на верх / низ модели для разбора узлов.", NamedTextColor.GRAY)));
    }

    private void renderWardrobe(Session s) {
        for (int i = 0; i < 5; i++) {
            TextDisplay b = s.list.get(i);
            boolean sel = (s.sel == i);
            b.setBackgroundColor(sel ? Style1Theme.BG_CARD_HOVER : Style1Theme.BG_DIM);
            b.text(Component.text("🛡 " + SETS[i][0] + "\n",
                    sel ? Style1Theme.GOLD : NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text("  " + SETS[i][1], Style1Theme.STEEL)));
        }

        spawnSoldier(s, 0.30, -0.32, 0.92);
        applySetToMannequin(s.soldier, s.sel);

        s.detailPanel.text(Component.text("ЭКИПИРОВКА БОЙЦА\n", Style1Theme.GOLD, TextDecoration.BOLD)
            .append(Component.text(SETS[s.sel][0] + "\n", NamedTextColor.WHITE))
            .append(Component.text(SETS[s.sel][1] + "\n\n", Style1Theme.STEEL))
            .append(Component.text("[ЛКМ по строке — примерка на манекен]", NamedTextColor.GRAY)));
        s.model3D.setItemStack(new ItemStack(Material.AIR));
    }

    private void renderCrate(Session s) {
        double coins = plugin.getCoins(s.player);
        for (int i = 0; i < CRATES.length; i++) {
            CrateDef c = CRATES[i];
            TextDisplay b = s.list.get(i);
            boolean sel = (s.sel == i);
            b.setBackgroundColor(sel ? Style1Theme.BG_CARD_HOVER : Style1Theme.BG_DIM);
            b.text(Component.text(c.title() + "\n",
                    sel ? Style1Theme.GOLD : NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text("  Цена: " + c.price() + " ₴ · " + c.desc(), Style1Theme.STEEL)));
        }
        int idx = s.sel < CRATES.length ? s.sel : CRATES.length - 1;
        int pity = plugin.getPity(s.player.getUniqueId(), CRATES[idx].id());
        s.detailPanel.text(Component.text("ВОЕННЫЙ ЯЩИК\n", Style1Theme.GOLD, TextDecoration.BOLD)
            .append(Component.text("Баланс: " + String.format(Locale.US, "%,.0f", coins) + " ₴\n",
                Style1Theme.STEEL))
            .append(Component.text("Pity «гарантии Эпика»: " + pity + "/" + PITY_GOAL + "\n",
                Style1Theme.STEEL))
            .append(Component.text("[ЛКМ по ящику слева — запуск 3D-вскрытия]", NamedTextColor.GRAY)));
        if (!s.rolling) s.model3D.setItemStack(new ItemStack(Material.ENDER_CHEST));
    }

    private void renderTree(Session s) {
        String[] eraLines = {
            "Век I  · Железо",
            "Век II · Форт",
            "Век III· Порох",
            "Век IV · Индустрия",
            "Век V  · Война"
        };
        for (int i = 0; i < 5; i++) {
            TextDisplay b = s.list.get(i);
            b.setBackgroundColor(Style1Theme.BG_DIM);
            b.text(Component.text("◈ " + eraLines[i], Style1Theme.STEEL));
        }
        s.model3D.setItemStack(new ItemStack(Material.ANVIL));

        clearTree(s);
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                String id = row + "_" + col;
                boolean done = plugin.isResearched(id);
                boolean unlocked = plugin.isResearched((row - 1) + "_" + col) || row == 0;
                float sc = (float) (0.44 * s.zoom);
                double px = nx(col) * s.zoom;
                double py = ny(row) * s.zoom;
                final int r = row;
                final int c = col;
                s.treeNodes.add(spawnText(s, px, py, 0.95, d -> {
                    d.setAlignment(TextDisplay.TextAlignment.CENTER);
                    d.setLineWidth(110);
                    d.setBackgroundColor(done ? Style1Theme.BG_CARD_HOVER
                            : (unlocked ? Style1Theme.BG_CARD : Style1Theme.BG_DIM));
                    d.setTransformation(scale(sc, sc, 1f));
                    d.text(Component.text(
                            done ? "✔ " : "◈ ",
                            done ? Style1Theme.GOLD : (unlocked ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY))
                        .append(Component.text(TREE[r][c] + "\n",
                            done ? Style1Theme.GOLD : (unlocked ? NamedTextColor.WHITE : NamedTextColor.GRAY),
                            TextDecoration.BOLD))
                        .append(Component.text(TREE_COST[r][c] + " ₴",
                            done ? NamedTextColor.DARK_GRAY : Style1Theme.STEEL)));
                }));
                s.treeIds.add(id);
            }
        }

        s.detailPanel.text(Component.text("ШАБ И РЕСЁРЧ\n", Style1Theme.GOLD, TextDecoration.BOLD)
            .append(Component.text("Казна: " + String.format(Locale.US, "%,.0f", plugin.getCoins(s.player))
                + " ₴\n", Style1Theme.STEEL))
            .append(Component.text("ЛКМ по открытой ячейке — исследование.\n", NamedTextColor.GRAY))
            .append(Component.text("Колесо — зум древа.", NamedTextColor.GRAY)));
    }

    // ─────────────── МАНЕКЕН ───────────────

    private void spawnSoldier(Session s, double x, double y, double depth) {
        if (s.soldier != null && s.soldier.isValid()) {
            Location loc = getScreenLoc(s, x, y, depth);
            loc.setDirection(s.forward.clone().multiply(-1));
            s.soldier.teleport(loc);
            return;
        }
        Location loc = getScreenLoc(s, x, y, depth);
        loc.setDirection(s.forward.clone().multiply(-1));
        s.soldier = s.player.getWorld().spawn(loc, Mannequin.class, m -> {
            m.setGravity(false);
            m.setInvulnerable(true);
            m.setPersistent(false);
            m.setVisibleByDefault(false);
            m.getPersistentDataContainer().set(tagKey(), PersistentDataType.BYTE, (byte) 1);
            applyProfileSilently(m, s.player);
        });
        s.player.showEntity(plugin, s.soldier);
        s.spawned.add(s.soldier);
    }

    private void applySetToMannequin(Mannequin man, int idx) {
        EntityEquipment eq = man.getEquipment();
        if (eq == null) return;
        eq.setHelmet(pieceArmor(SETS[idx][2] + "_HELMET"));
        eq.setChestplate(pieceArmor(SETS[idx][2] + "_CHESTPLATE"));
        eq.setLeggings(pieceArmor(SETS[idx][2] + "_LEGGINGS"));
        eq.setBoots(pieceArmor(SETS[idx][2] + "_BOOTS"));
    }

    private ItemStack pieceArmor(String name) {
        Material m = Material.matchMaterial(name);
        return m == null ? null : new ItemStack(m);
    }

    private void applyProfileSilently(Entity man, Player p) {
        for (java.lang.reflect.Method m : man.getClass().getMethods()) {
            if (!m.getName().equals("setProfile") || m.getParameterCount() != 1) continue;
            Class<?> pt = m.getParameterTypes()[0];
            try {
                if (pt.getSimpleName().equals("ResolvableProfile")) {
                    Class<?> rp = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
                    Object builder = rp.getMethod("resolvableProfile").invoke(null);
                    builder.getClass().getMethod("name", String.class).invoke(builder, p.getName());
                    Object built = builder.getClass().getMethod("build").invoke(builder);
                    m.invoke(man, built);
                    break;
                } else if (pt.getSimpleName().equals("PlayerProfile")) {
                    Class<?> pp = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
                    m.invoke(man, pp.getMethod("createProfile",
                            java.util.UUID.class, String.class)
                            .invoke(null, p.getUniqueId(), p.getName()));
                    break;
                }
            } catch (Throwable ignored) {
                // тихий отказ: манекен останется без skin — не критично.
            }
        }
    }

    // ─────────────── КЕЙСЫ ───────────────

    private void startRoll(Session s, int crateIdx) {
        if (crateIdx < 0 || crateIdx >= CRATES.length) return;
        CrateDef cr = CRATES[crateIdx];

        long now = System.currentTimeMillis();
        Long last = lastRoll.get(s.player.getUniqueId());
        if (last != null && now - last < 6000L) {
            long wait = (6000L - (now - last)) / 1000L + 1;
            s.player.sendActionBar(Component.text("⏳ Подожди " + wait + " c до следующего ящика",
                    NamedTextColor.RED));
            return;
        }
        double coins = plugin.getCoins(s.player);
        if (coins < cr.price()) {
            s.player.sendActionBar(Component.text("Не хватает ₴: нужно " + cr.price(), NamedTextColor.RED));
            return;
        }
        plugin.addCoins(s.player, -cr.price());
        lastRoll.put(s.player.getUniqueId(), now);

        String rollId = UUID.randomUUID().toString();
        plugin.getLogger().info("[PoliticHellEngine] crate_roll_id=" + rollId
                + " player=" + s.player.getName() + " crate=" + cr.id());

        s.rolling = true;
        s.player.playSound(s.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.6f);

        int rolled = rollRarity(cr.w());
        int finalRarity;
        int pity = plugin.getPity(s.player.getUniqueId(), cr.id());
        if (rolled < 3) {
            pity++;
            if (pity >= PITY_GOAL) {
                plugin.setPity(s.player.getUniqueId(), cr.id(), 0);
                finalRarity = 3;
                plugin.getLogger().info("[PoliticHellEngine] PITY ACTIVATED for " + s.player.getName()
                        + " crate=" + cr.id());
            } else {
                plugin.setPity(s.player.getUniqueId(), cr.id(), pity);
                finalRarity = rolled;
            }
        } else {
            plugin.setPity(s.player.getUniqueId(), cr.id(), 0);
            finalRarity = rolled;
        }

        ItemStack prize = buildPrize(finalRarity, cr, rollId);

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (!sessions.containsKey(s.player.getUniqueId())) {
                    cancel();
                    return;
                }
                t++;
                ItemStack flash = piece(POOL[rnd.nextInt(POOL.length)][1]);
                s.model3D.setItemStack(flash);
                float sc = 0.28f + (float) Math.sin(t * 0.4) * 0.10f;
                s.model3D.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0), new AxisAngle4f(),
                    new Vector3f(sc, sc, sc),
                    new AxisAngle4f(t * 0.6f, 0, 1, 0)));
                s.player.playSound(s.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT,
                        0.30f, 1.5f + t * 0.03f);

                if (t >= 26) {
                    cancel();
                    s.rolling = false;
                    s.model3D.setItemStack(prize);
                    s.model3D.setGlowing(true);
                    s.model3D.setGlowColorOverride(Color.fromRGB(0xF7, 0xC2, 0x4F));
                    s.player.playSound(s.player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
                    s.player.sendActionBar(Component.text("roll_id " + rollId + " · Редкость: "
                            + RARITY_NAMES[finalRarity], RARITY_COLORS[finalRarity]));
                    s.player.getInventory().addItem(prize).values()
                            .forEach(rest -> s.player.getWorld().dropItem(s.player.getLocation(), rest));
                    render(s);
                }
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    private int rollRarity(double[] w) {
        double r = rnd.nextDouble();
        double cum = 0.0;
        for (int i = 0; i < w.length; i++) {
            cum += w[i];
            if (r <= cum) return i;
        }
        return 2;
    }

    private ItemStack piece(String mat) {
        Material m = Material.matchMaterial(mat);
        return new ItemStack(m == null ? Material.PAPER : m);
    }

    private ItemStack buildPrize(int rarity, CrateDef cr, String rollId) {
        List<String[]> band = new ArrayList<>();
        for (String[] p : POOL) {
            if (p[2].equals(String.valueOf(rarity))) band.add(p);
        }
        String[] picked = band.get(rnd.nextInt(band.size()));
        Material m = Material.matchMaterial(picked[1]);
        ItemStack it = new ItemStack(m == null ? Material.PAPER : m);

        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(picked[0], RARITY_COLORS[rarity])
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Редкость: " + RARITY_NAMES[rarity], RARITY_COLORS[rarity]));
            lore.add(Component.text(picked[3], Style1Theme.STEEL));
            lore.add(Component.text("Ящик: " + cr.title(), NamedTextColor.DARK_GRAY));
            lore.add(Component.text("roll_id: " + rollId, NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ─────────────── КУРСОР И ВВОД ───────────────

    @EventHandler
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;

        float dYaw = e.getTo().getYaw() - e.getFrom().getYaw();
        float dPitch = e.getTo().getPitch() - e.getFrom().getPitch();
        boolean rotated = Math.abs(dYaw) > 0.01f || Math.abs(dPitch) > 0.01f;

        if (rotated) {
            org.bukkit.Location from = e.getFrom();
            org.bukkit.Location to = e.getTo().clone();
            to.setX(from.getX());
            to.setY(from.getY());
            to.setZ(from.getZ());
            e.setTo(from);
            s.cx = clamp(s.cx + dYaw * 0.012, -0.62, 0.62);
            s.cy = clamp(s.cy - dPitch * 0.012, -0.38, 0.38);
            s.cursor.teleport(getScreenLoc(s, s.cx, s.cy, 0.84));
            updateHover(s);
        }
    }

    private void updateHover(Session s) {
        int found = -1;

        for (int i = 0; i < 5; i++) {
            if (Math.abs(s.cx - TAB_X[i]) < 0.12 && Math.abs(s.cy - 0.34) < 0.055) {
                found = i;
                break;
            }
        }
        if (found == -1) {
            for (int i = 0; i < 5; i++) {
                double y = 0.16 - (i * 0.125);
                if (s.cx >= -0.58 && s.cx <= -0.06 && Math.abs(s.cy - (y + 0.02)) < 0.055) {
                    found = 5 + i;
                    break;
                }
            }
        }
        if (found == -1 && s.tab == Tab.TECH_TREE) {
            for (int i = 0; i < 25; i++) {
                int row = i / 5, col = i % 5;
                double nx = nx(col) * s.zoom;
                double ny = ny(row) * s.zoom;
                if (Math.abs(s.cx - nx) < 0.075 * s.zoom && Math.abs(s.cy - ny) < 0.055 * s.zoom) {
                    found = 10 + i;
                    break;
                }
            }
        }
        if (found != s.hovered) {
            s.hovered = found;
            if (found != -1) {
                s.player.playSound(s.player.getLocation(), Sound.UI_BUTTON_CLICK, 0.25f, 1.8f);
            }
            render(s);
            return;
        }

        if (s.tab == Tab.ARSENAL && s.cx >= 0.16 && s.cx <= 0.46
                && Math.abs(s.cy - (-0.08)) < 0.18) {
            String[] cur = WEAPONS[s.sel];
            s.model3D.setGlowing(true);
            if (s.cy >= -0.08) {
                s.model3D.setGlowColorOverride(Color.fromRGB(0xF7, 0xC2, 0x4F));
                s.partTooltip.setBackgroundColor(Style1Theme.BG_CARD_HOVER);
                s.partTooltip.text(Component.text("🟠 Верх: боевая часть\n",
                        Style1Theme.GOLD, TextDecoration.BOLD)
                    .append(Component.text(cur[3], NamedTextColor.WHITE)));
            } else {
                s.model3D.setGlowColorOverride(Color.AQUA);
                s.partTooltip.setBackgroundColor(Style1Theme.BG_CARD_HOVER);
                s.partTooltip.text(Component.text("🔵 Низ: рукоять/основа\n",
                        TextColor.color(0x58B0F7), TextDecoration.BOLD)
                    .append(Component.text(cur[4], NamedTextColor.WHITE)));
            }
        } else if (s.model3D != null && !s.rolling) {
            s.model3D.setGlowing(false);
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_AIR) {
            return;
        }
        e.setCancelled(true);
        int h = s.hovered;

        if (h >= 0 && h < 5) {
            s.tab = Tab.values()[h];
            s.sel = 0;
            s.zoom = 1.0;
            s.player.playSound(s.player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            render(s);
            return;
        }

        if (h >= 5 && h < 10) {
            int idx = h - 5;
            s.sel = idx;
            s.player.playSound(s.player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
            switch (s.tab) {
                case HUB -> hubAction(s, idx);
                case SUPPLY_CRATE -> {
                    startRoll(s, idx);
                    render(s);
                }
                case WARDROBE -> render(s);
                default -> {
                    plugin.getLogger().info("[PoliticHellEngine] future action idx=" + idx);
                    render(s);
                }
            }
            return;
        }

        if (h >= 10 && h < 35 && s.tab == Tab.TECH_TREE) {
            int idx = h - 10;
            int row = idx / 5, col = idx % 5;
            String id = row + "_" + col;
            int cost = TREE_COST[row][col];

            if (plugin.isResearched(id)) {
                s.player.sendActionBar(Component.text("◈ Уже исследовано: " + TREE[row][col],
                        Style1Theme.STEEL));
                return;
            }
            boolean unlocked = plugin.isResearched((row - 1) + "_" + col) || row == 0;
            if (!unlocked) {
                s.player.sendActionBar(Component.text("✗ Сначала предыдущий век в колонке.",
                        NamedTextColor.RED));
                return;
            }
            double coins = plugin.getCoins(s.player);
            if (coins < cost) {
                s.player.sendActionBar(Component.text("Нужно казны: " + cost + " ₴", NamedTextColor.RED));
                return;
            }
            plugin.addCoins(s.player, -cost);
            plugin.setResearched(id);
            s.player.playSound(s.player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            s.player.sendActionBar(Component.text("✔ Исследовано: " + TREE[row][col] + " · " + cost + " ₴",
                    Style1Theme.GOLD));
            plugin.getLogger().info("[PoliticHellEngine] researched node=" + id
                    + " player=" + s.player.getName());
            render(s);
        }
    }

    private void hubAction(Session s, int idx) {
        switch (idx) {
            case 0 -> {
                close(s.player.getUniqueId());
                s.player.teleport(s.player.getWorld().getSpawnLocation());
                s.player.sendActionBar(Component.text("▶ Выдвинулся на фронт!", NamedTextColor.GREEN));
            }
            default -> s.player.sendActionBar(Component.text(
                    "Модуль «" + HUB_ACTIONS[idx].split(" ")[0] + "» — Phase 1. Скоро.",
                    NamedTextColor.GRAY));
        }
    }

    @EventHandler
    public void onScroll(PlayerItemHeldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        int diff = e.getNewSlot() - e.getPreviousSlot();
        if (diff == 8) diff = -1;
        if (diff == -8) diff = 1;

        if (s.tab == Tab.TECH_TREE) {
            s.zoom = clamp(s.zoom + (diff > 0 ? 0.12 : -0.12), 0.6, 1.6);
            s.player.playSound(s.player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.4f, 1.5f);
            render(s);
            return;
        }
        s.sel = clampInt(s.sel + (diff > 0 ? 1 : -1), 0, 4);
        s.player.playSound(s.player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.4f, 1.5f);
        render(s);
    }

    @EventHandler
    public void onShift(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && sessions.containsKey(e.getPlayer().getUniqueId())) {
            close(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (sessions.containsKey(p.getUniqueId())) {
            // внутри меню F = закрыть ПДА
            e.setCancelled(true);
            close(p.getUniqueId());
        } else if (p.isSneaking()) {
            // Shift + F вне меню = открыть ПДА (обычный F работает как оффхенд)
            e.setCancelled(true);
            open(p, Tab.HUB);
        }
    }

    @EventHandler
    public void onCombatDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) {
            close(p.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        close(e.getPlayer().getUniqueId());
    }

    // ─────────────── ГЕОМЕТРИЯ / УТИЛИТЫ ───────────────

    private double nx(int col) { return -0.30 + col * 0.15; }
    private double ny(int row) { return 0.08 - row * 0.13; }

    private Location getScreenLoc(Session s, double x, double y, double depth) {
        Location loc = s.anchorEye.clone()
                .add(s.forward.clone().multiply(depth))
                .add(s.right.clone().multiply(x))
                .add(s.up.clone().multiply(y));
        loc.setDirection(s.forward.clone().multiply(-1));
        return loc;
    }

    private TextDisplay spawnText(Session s, double x, double y, double depth,
                                  java.util.function.Consumer<TextDisplay> cfg) {
        Location loc = getScreenLoc(s, x, y, depth);
        TextDisplay td = s.player.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(tagKey(), PersistentDataType.BYTE, (byte) 1);
            cfg.accept(d);
        });
        s.player.showEntity(plugin, td);
        s.spawned.add(td);
        return td;
    }

    private ItemDisplay spawnItem(Session s, double x, double y, double depth) {
        Location loc = getScreenLoc(s, x, y, depth);
        ItemDisplay id = s.player.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(tagKey(), PersistentDataType.BYTE, (byte) 1);
        });
        s.player.showEntity(plugin, id);
        s.spawned.add(id);
        return id;
    }

    private void clearTree(Session s) {
        for (Entity e : s.treeNodes) {
            e.remove();
            s.spawned.remove(e);
        }
        s.treeNodes.clear();
        s.treeIds.clear();
    }

    private Transformation scale(float x, float y, float z) {
        return new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(),
                new Vector3f(x, y, z), new AxisAngle4f());
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
