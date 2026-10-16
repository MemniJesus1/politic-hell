package com.politichell.hud;

import com.politichell.hud.cards.CardData;
import com.politichell.hud.cards.CardManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Админ-панель /phh: всё, что настраивается, — кнопками (без правки файлов руками).
 * Страницы: главная · предмет в руке · свои предметы · настройки (любой конфиг, ключ за ключом, с пояснениями) · возможности · состояние.
 * Значения правятся построчно (YamlPatch): комментарии в файлах не страдают, рядом остаётся копия .bak.
 */
public final class AdminPanel implements Listener, CommandExecutor {
    private final PoliticHellHud plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    private record Prompt(Consumer<String> done, Runnable back) {}

    interface Act { void click(Player p, ClickType t); }

    /** Окно панели: слот → действие. */
    static final class Page implements InventoryHolder {
        final Map<Integer, Act> acts = new HashMap<>();
        final Inventory inv;
        Page(String title, int rows) { inv = Bukkit.createInventory(this, rows * 9, LegacyComponentSerializer.legacySection().deserialize(title)); }
        @Override public @NotNull Inventory getInventory() { return inv; }
        Page set(int slot, ItemStack it, Act a) { inv.setItem(slot, it); if (a != null) acts.put(slot, a); else acts.remove(slot); return this; }
        Page fill() {
            ItemStack g = item(Material.BLACK_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, g);
            return this;
        }
    }

    public AdminPanel(PoliticHellHud plugin) { this.plugin = plugin; }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var c = plugin.getCommand("phh");
        if (c != null) c.setExecutor(this);
    }

    private CardManager cards() { return plugin.cards(); }

    // ═════════════ предметы-кнопки ═════════════
    static Component c(String legacy) { return LegacyComponentSerializer.legacySection().deserialize(legacy.replace('&', '§')).decoration(TextDecoration.ITALIC, false); }

    static ItemStack item(Material m, String name, String... lore) { return item(m, name, Arrays.asList(lore)); }

    static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        meta.displayName(c(name));
        if (!lore.isEmpty()) { List<Component> l = new ArrayList<>(); for (String x : lore) l.add(c(x)); meta.lore(l); }
        meta.addItemFlags(ItemFlag.values());
        s.setItemMeta(meta);
        return s;
    }

    /** Перенос длинного текста по словам (~34 символа), каждая строка получает цвет. */
    static List<String> wrap(String text, String color, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.length() + word.length() + 1 > w && cur.length() > 0) { out.add(color + cur); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) out.add(color + cur);
        return out;
    }

    private static Act nav(Runnable r) { return (p, t) -> r.run(); }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    // ═════════════ команда ═════════════
    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Панель — только из игры."); return true; }
        if (a.length > 0) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "card", "предмет" -> { card(p); return true; }
                case "files", "настройки" -> { files(p); return true; }
                case "status", "состояние" -> { status(p); return true; }
                case "features", "возможности" -> { features(p); return true; }
                case "items", "каталог" -> { catalog(p, 0); return true; }
                default -> { }
            }
        }
        main(p);
        return true;
    }

    // ═════════════ главная ═════════════
    void main(Player p) {
        Page g = new Page("§6§lPOLITIC HELL §8· §7панель", 5);
        g.set(4, item(Material.NETHER_STAR, "&6&lПанель управления", "&7Всё настраивается отсюда:", "&7без правки файлов руками.", "", "&8Версия плагина: " + plugin.getDescription().getVersion()), null);
        g.set(19, item(Material.NAME_TAG, "&e&lПредмет в руке", "&7Название, редкость, привязка,", "&7описание и срок — кнопками.", "", "&aЛКМ — открыть"), nav(() -> card(p)));
        g.set(21, item(Material.CHEST, "&e&lСвои предметы", "&7Каталог предметов из items.yml:", "&7клик — выдать себе.", "", "&aЛКМ — открыть"), nav(() -> catalog(p, 0)));
        g.set(23, item(Material.COMPARATOR, "&e&lНастройки", "&7Любой файл настроек: каждый ключ", "&7с пояснением, правка кликами.", "", "&aЛКМ — открыть"), nav(() -> files(p)));
        g.set(25, item(Material.BOOKSHELF, "&e&lВозможности", "&7Что умеет плагин, какие команды", "&7и в каком файле настраивается.", "", "&aЛКМ — открыть"), nav(() -> features(p)));
        g.set(29, item(Material.REPEATER, "&b&lПеречитать настройки", "&7Применить изменения файлов", "&7без перезапуска сервера.", "", "&aЛКМ — выполнить"), (pl, t) -> { reloadAll(pl); main(pl); });
        g.set(31, item(Material.SPYGLASS, "&b&lСостояние", "&7Какие плагины найдены и что", "&7они даёт серверу.", "", "&aЛКМ — открыть"), nav(() -> status(p)));
        g.set(33, item(Material.ENDER_EYE, "&b&lРежим подсказок", "&7Сейчас: &f" + (cards().full(p) ? "подробно" : "кратко"), "&7Это же — Shift+ПКМ по предмету.", "", "&aЛКМ — переключить"), (pl, t) -> { cards().toggleMode(pl); main(pl); });
        g.set(35, item(Material.PAINTING, "&b&lВид карточки", "&7Сейчас: &f" + cards().style(p).title, "&8" + cards().style(p).about, "", "&7Классика · Широкая · Тех. Для себя", "&7(так же: /tip style).", "", "&aЛКМ — следующий вид"), (pl, t) -> { cards().setStyle(pl, cards().style(pl).next()); main(pl); });
        g.set(40, item(Material.BARRIER, "&c&lЗакрыть"), (pl, t) -> pl.closeInventory());
        g.fill();
        p.openInventory(g.inv);
    }

    void reloadAll(Player p) {
        try { plugin.reloadAll(); p.sendMessage("§aНастройки перечитаны."); }
        catch (Throwable e) { p.sendMessage("§cОшибка при перечитывании: " + e); }
    }

    private Act back(Runnable r) { return (p, t) -> r.run(); }

    private void navRow(Page g, int rows, Runnable back) {
        int b = (rows - 1) * 9;
        g.set(b, item(Material.ARROW, "&e← Назад"), back(back));
        g.set(b + 8, item(Material.BARRIER, "&c✕ Закрыть"), (p, t) -> p.closeInventory());
    }

    // ═════════════ предмет в руке ═════════════
    private static final String[] BINDINGS = {"consumable", "personal", "locked", "temporary"};
    private static final String[] BIND_RU = {"Свободный", "Персональный", "Неотчуждаемый", "Временный"};
    private static final Material[] BIND_MAT = {Material.FEATHER, Material.NAME_TAG, Material.TRIPWIRE_HOOK, Material.CLOCK};
    private static final String[] ICONS = {"weapon", "tool", "armor", "food", "block", "resource", "potion", "magic", "misc", "custom"};
    private static final String[] ICON_RU = {"оружие", "инструмент", "броня", "еда", "блок", "ресурс", "зелье", "магия", "разное", "особый"};
    private static final Material[] ICON_MAT = {Material.IRON_SWORD, Material.IRON_PICKAXE, Material.IRON_CHESTPLATE, Material.BREAD, Material.STONE, Material.IRON_INGOT, Material.POTION, Material.ENCHANTED_BOOK, Material.STICK, Material.NETHER_STAR};

    private static Material rarityMat(String id) {
        return switch (id) {
            case "common" -> Material.GRAY_DYE; case "uncommon" -> Material.LIME_DYE; case "rare" -> Material.LIGHT_BLUE_DYE;
            case "epic" -> Material.PURPLE_DYE; case "legendary" -> Material.ORANGE_DYE; case "mythic" -> Material.RED_DYE;
            default -> Material.WHITE_DYE;
        };
    }

    private static int idx(String[] arr, String v) { for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i; return 0; }

    void card(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        Page g = new Page("§6§lПредмет §8· §7в руке", 5);
        if (held.getType().isAir()) {
            g.set(22, item(Material.BARRIER, "&cРука пуста", "&7Возьми предмет в руку и открой", "&7эту страницу снова: &f/phh предмет"), null);
            navRow(g, 5, () -> main(p));
            g.fill();
            p.openInventory(g.inv);
            return;
        }
        CardManager m = cards();
        CardData d = m.resolve(held.getType(), held.getItemMeta().getPersistentDataContainer());
        String[] rar = m.config.rarities.keySet().toArray(new String[0]);
        g.set(4, held.clone(), null);
        // название
        g.set(19, item(Material.NAME_TAG, "&e&lНазвание", "&7Сейчас: &f" + d.title, "", "&aЛКМ — ввести новое в чат", "&aПКМ — вернуть как было"),
                (pl, t) -> { if (t.isRightClick()) run(pl, "card clear name"); else ask(pl, "новое название предмета (цвета не нужны — градиент по редкости)", v -> run(pl, "card set name " + v), () -> card(pl)); });
        // редкость
        g.set(20, item(rarityMat(d.rarity.id()), "&e&lРедкость", "&7Сейчас: &f" + d.rarity.name(), "", "&aЛКМ — следующая", "&aПКМ — предыдущая", "&aShift — вернуть как было"),
                (pl, t) -> {
                    if (t.isShiftClick()) { run(pl, "card clear rarity"); return; }
                    int i = idx(rar, d.rarity.id()) + (t.isRightClick() ? -1 : 1);
                    run(pl, "card set rarity " + rar[(i + rar.length) % rar.length]);
                });
        // привязка
        int bi = idx(BINDINGS, d.binding.id);
        List<String> bl = new ArrayList<>(List.of("&7Сейчас: &f" + BIND_RU[bi], ""));
        bl.addAll(wrap(m.config.bindings.get(d.binding) != null ? m.config.bindings.get(d.binding).text() : "", "&8", 34));
        bl.addAll(List.of("", "&aЛКМ — следующая", "&aПКМ — предыдущая", "&aShift — вернуть как было"));
        g.set(21, item(BIND_MAT[bi], "&e&lПривязка", bl),
                (pl, t) -> {
                    if (t.isShiftClick()) { run(pl, "card clear binding"); return; }
                    int i = bi + (t.isRightClick() ? -1 : 1);
                    run(pl, "card set binding " + BINDINGS[(i + 4) % 4]);
                });
        // иконка
        int ii = idx(ICONS, d.icon);
        g.set(22, item(ICON_MAT[ii], "&e&lЗначок", "&7Сейчас: &f" + ICON_RU[ii], "&7Рисуется слева от названия.", "", "&aЛКМ — следующий", "&aПКМ — предыдущий", "&aShift — вернуть как было"),
                (pl, t) -> {
                    if (t.isShiftClick()) { run(pl, "card clear icon"); return; }
                    int i = ii + (t.isRightClick() ? -1 : 1);
                    run(pl, "card set icon " + ICONS[(i + ICONS.length) % ICONS.length]);
                });
        // описание
        List<String> dl = new ArrayList<>(List.of("&7Сейчас:"));
        for (String x : d.desc.subList(0, Math.min(3, d.desc.size()))) dl.addAll(wrap(x.replaceAll("&.", ""), "&f", 34));
        dl.addAll(List.of("", "&aЛКМ — ввести новое в чат", "&7(две строки разделяй знаком ||)", "&aПКМ — вернуть как было"));
        g.set(23, item(Material.WRITABLE_BOOK, "&e&lОписание", dl),
                (pl, t) -> { if (t.isRightClick()) run(pl, "card clear desc"); else ask(pl, "новое описание (перенос строки — ||)", v -> run(pl, "card set desc " + v), () -> card(pl)); });
        // срок
        String tm = m.timerText(d, System.currentTimeMillis());
        g.set(24, item(Material.CLOCK, "&e&lСрок жизни", "&7Сейчас: &f" + (tm == null ? "бессрочно" : tm), "&7После срока предмет исчезает.", "", "&aЛКМ — 1 час", "&aПКМ — 1 сутки", "&aShift+ЛКМ — свой срок (минуты)", "&aShift+ПКМ — убрать срок"),
                (pl, t) -> {
                    if (t == ClickType.SHIFT_RIGHT) run(pl, "card set ttl off");
                    else if (t == ClickType.SHIFT_LEFT) ask(pl, "срок в минутах (число)", v -> { try { run(pl, "card set ttl " + (long) (Double.parseDouble(v.replace(',', '.')) * 60)); } catch (NumberFormatException e) { pl.sendMessage("§cНужно число."); card(pl); } }, () -> card(pl));
                    else run(pl, "card set ttl " + (t.isRightClick() ? 86400 : 3600));
                });
        // событие
        g.set(25, item(Material.BELL, "&e&lСобытие-срок", "&7Предмет исчезнет, когда событие", "&7закончится (/card expire <событие>).", "&7Сейчас: &f" + (d.event == null ? "нет" : d.event), "", "&aЛКМ — ввести название в чат", "&aПКМ — убрать"),
                (pl, t) -> { if (t.isRightClick()) run(pl, "card set event off"); else ask(pl, "название события (латиницей, без пробелов)", v -> run(pl, "card set event " + v.replace(' ', '_')), () -> card(pl)); });
        // владелец
        g.set(31, item(Material.PLAYER_HEAD, "&e&lВладелец", "&7Для персональных предметов.", "&7Сейчас: &f" + (d.owner == null ? "не назначен" : Optional.ofNullable(Bukkit.getOfflinePlayer(d.owner).getName()).orElse("?")), "", "&aЛКМ — назначить тебя", "&aПКМ — ввести ник в чат", "&aShift — снять"),
                (pl, t) -> {
                    if (t.isShiftClick()) run(pl, "card set owner none");
                    else if (t.isRightClick()) ask(pl, "ник владельца", v -> run(pl, "card set owner " + v), () -> card(pl));
                    else run(pl, "card set owner " + pl.getName());
                });
        // правило для всех таких предметов
        g.set(29, item(Material.CRAFTING_TABLE, "&b&lПравило для всех «" + d.title + "»", "&7Сделать так со ВСЕМИ такими предметами", "&7на сервере: запишется в groups.yml.", "", "&aЛКМ — привязка + редкость как у этого", "&8(команда /card group)"),
                (pl, t) -> { run(pl, "card group " + held.getType().name().toLowerCase() + " " + d.binding.id + " " + d.rarity.id()); });
        g.set(33, item(Material.TNT, "&c&lСбросить все правки", "&7Предмет снова станет обычным", "&7для карточек (как в игре).", "", "&aShift+ЛКМ — выполнить"),
                (pl, t) -> { if (t == ClickType.SHIFT_LEFT) run(pl, "card clear all"); else pl.sendMessage("§7Для подтверждения нажми Shift+ЛКМ."); });
        navRow(g, 5, () -> main(p));
        g.fill();
        p.openInventory(g.inv);
    }

    private void run(Player p, String cmd) {
        p.performCommand(cmd);
        later(() -> { if (p.isOnline()) card(p); });
    }

    // ═════════════ каталог своих предметов ═════════════
    void catalog(Player p, int page) {
        CardManager m = cards();
        List<String> ids = new ArrayList<>(m.config.custom.keySet());
        int per = 36, pages = Math.max(1, (ids.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        Page g = new Page("§6§lСвои предметы §8· §7" + (page + 1) + "/" + pages, 6);
        final int pg = page;
        for (int i = 0; i < per && page * per + i < ids.size(); i++) {
            String id = ids.get(page * per + i);
            ItemStack it = m.create(id, 1, p);
            if (it == null) continue;
            g.set(i, it, (pl, t) -> {
                ItemStack give = m.create(id, t.isShiftClick() ? 64 : 1, pl);
                if (give != null) pl.getInventory().addItem(give).values().forEach(r -> pl.getWorld().dropItem(pl.getLocation(), r));
                pl.sendMessage("§aВыдано: §f" + id + (t.isShiftClick() ? " ×стопка" : ""));
            });
        }
        if (ids.isEmpty()) g.set(22, item(Material.PAPER, "&7Пока нет своих предметов", "&7Их описывают в файле", "&fcards/items.yml", "&7(страница «Настройки»)."), null);
        g.set(45, item(Material.ARROW, "&e← Назад"), back(() -> main(p)));
        if (page > 0) g.set(47, item(Material.SPECTRAL_ARROW, "&e‹ Предыдущая"), back(() -> catalog(p, pg - 1)));
        g.set(49, item(Material.BOOK, "&e&lКак пользоваться", "&7ЛКМ — взять один", "&7Shift — взять стопку", "&7Любой ванильный предмет:", "&f/card give <ник> <МАТЕРИАЛ>"), null);
        if (page < pages - 1) g.set(51, item(Material.SPECTRAL_ARROW, "&e Следующая ›"), back(() -> catalog(p, pg + 1)));
        g.set(53, item(Material.BARRIER, "&c✕ Закрыть"), (pl, t) -> pl.closeInventory());
        g.fill();
        p.openInventory(g.inv);
    }

    // ═════════════ файлы настроек ═════════════
    private record FileInfo(String path, Material icon, String title, String desc) {}

    private static final List<FileInfo> FILES = List.of(
            new FileInfo("hud.yml", Material.EMERALD, "Деньги и скорборд", "Плашки над сердцами, высота, анимация, стиль скорборда."),
            new FileInfo("gameplay.yml", Material.COMPASS, "Игровые правила", "Страна, AFK, новички, голос."),
            new FileInfo("compass.yml", Material.RECOVERY_COMPASS, "Компас", "Метки, дальность, растворение к краям."),
            new FileInfo("economy.yml", Material.GOLD_INGOT, "Экономика", "Монеты (Vault), донат, формат чисел."),
            new FileInfo("ranks.yml", Material.NETHER_STAR, "Ранги", "Иконки рангов и связь с группами LuckPerms."),
            new FileInfo("config.yml", Material.COMPARATOR, "Общее", "Служебные настройки плагина и обновлений."),
            new FileInfo("cards/cards.yml", Material.NAME_TAG, "Карточки: оформление", "Редкости, цвета, привязки, правила, режимы подсказки."),
            new FileInfo("cards/items.yml", Material.CHEST, "Карточки: свои предметы", "Описание собственных предметов сервера."),
            new FileInfo("cards/groups.yml", Material.CRAFTING_TABLE, "Карточки: группы", "Правила для целых групп предметов (привязка, редкость)."),
            new FileInfo("cards/vanilla.yml", Material.GRASS_BLOCK, "Карточки: ваниль", "Точечные правки ванильных предметов."));

    void files(Player p) {
        Page g = new Page("§6§lНастройки §8· §7файлы", 5);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int k = 0;
        for (FileInfo f : FILES) {
            File file = new File(plugin.getDataFolder(), f.path);
            if (!file.exists()) continue;
            g.set(slots[k++], item(f.icon, "&e&l" + f.title, join(wrap(f.desc, "&7", 34), List.of("", "&8" + f.path, "", "&aЛКМ — открыть"))), nav(() -> keys(p, f.path, "", 0)));
        }
        g.set(4, item(Material.WRITABLE_BOOK, "&6&lНастройки", "&7Выбери файл. Внутри каждый ключ", "&7объяснён, значения меняются кликом."), null);
        g.set(31, item(Material.LECTERN, "&b&lЕсть ещё", "&7Конфиги TAB, Towny, DeluxeMenus", "&7правятся отдельно — я их настраиваю", "&7через установщик (&f/hudupdate&7)."), null);
        navRow(g, 5, () -> main(p));
        g.fill();
        p.openInventory(g.inv);
    }

    @SafeVarargs
    private static <T> List<T> join(List<T>... lists) { List<T> out = new ArrayList<>(); for (List<T> l : lists) out.addAll(l); return out; }

    private static boolean decor(String s) { return s.matches("^[═─\\-=_*#\\s]*$"); }

    void keys(Player p, String file, String parent, int page) {
        File f = new File(plugin.getDataFolder(), file);
        List<YamlPatch.Node> all;
        try { all = YamlPatch.parse(YamlPatch.read(f)); }
        catch (Exception e) { p.sendMessage("§cНе удалось прочитать " + file + ": " + e.getMessage()); files(p); return; }
        List<YamlPatch.Node> list = new ArrayList<>();
        YamlPatch.Node self = null;
        for (YamlPatch.Node n : all) { if (n.parent().equals(parent)) list.add(n); if (n.path.equals(parent)) self = n; }
        int per = 36, pages = Math.max(1, (list.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        String shown = parent.isEmpty() ? "" : " › " + parent.substring(Math.max(0, parent.length() - 24));
        Page g = new Page("§6§l" + file.replace("cards/", "") + "§8" + shown + " §7" + (pages > 1 ? (page + 1) + "/" + pages : ""), 6);
        final int pg = page;
        for (int i = 0; i < per && page * per + i < list.size(); i++) {
            YamlPatch.Node n = list.get(page * per + i);
            g.set(i, nodeItem(n), nodeAct(p, file, parent, pg, n));
        }
        if (list.isEmpty()) g.set(22, item(Material.PAPER, "&7Здесь пусто"), null);
        String up = parent.contains(".") ? parent.substring(0, parent.lastIndexOf('.')) : "";
        g.set(45, item(Material.ARROW, "&e← Назад"), back(() -> { if (parent.isEmpty()) files(p); else keys(p, file, up, 0); }));
        if (page > 0) g.set(47, item(Material.SPECTRAL_ARROW, "&e‹ Предыдущая"), back(() -> keys(p, file, parent, pg - 1)));
        List<String> info = new ArrayList<>(List.of("&7Файл: &f" + file, "&7Раздел: &f" + (parent.isEmpty() ? "(верх)" : parent)));
        if (self != null) for (String cm : self.comments) if (!decor(cm)) info.addAll(wrap(cm, "&8", 34));
        info.addAll(List.of("", "&8Рядом хранится копия: " + new File(file).getName() + ".bak"));
        g.set(49, item(Material.BOOK, "&e&lГде я", info), null);
        if (page < pages - 1) g.set(51, item(Material.SPECTRAL_ARROW, "&e Следующая ›"), back(() -> keys(p, file, parent, pg + 1)));
        g.set(53, item(Material.BARRIER, "&c✕ Закрыть"), (pl, t) -> pl.closeInventory());
        g.fill();
        p.openInventory(g.inv);
    }

    private static String shortVal(String v) { return v.length() > 28 ? v.substring(0, 27) + "…" : v; }

    private ItemStack nodeItem(YamlPatch.Node n) {
        List<String> lore = new ArrayList<>();
        int cl = 0;
        for (String cm : n.comments) { if (decor(cm)) continue; if (cl++ >= 7) { lore.add("&8…"); break; } lore.addAll(wrap(cm, "&7", 36)); }
        if (!lore.isEmpty()) lore.add("");
        String key = n.key;
        switch (n.kind) {
            case SECTION -> {
                lore.add("&8Внутри раздела");
                lore.add("&aЛКМ — открыть");
                return item(Material.CHEST, "&6▸ &e" + key, lore);
            }
            case BOOL -> {
                boolean on = n.raw.equals("true");
                lore.add("&aЛКМ — " + (on ? "выключить" : "включить"));
                return item(on ? Material.LIME_DYE : Material.GRAY_DYE, "&e" + key + ": " + (on ? "&aвкл" : "&cвыкл"), lore);
            }
            case INT, DOUBLE -> {
                lore.add("&aЛКМ — больше, ПКМ — меньше");
                lore.add("&aShift — шаг ×10");
                lore.add("&aQ — ввести число в чат");
                return item(Material.REPEATER, "&e" + key + ": &f" + n.raw, lore);
            }
            case LIST -> {
                lore.add("&7Список (правится в файле):");
                int i = 0;
                for (String s : n.items) { if (i++ >= 5) { lore.add("&8  … ещё " + (n.items.size() - 5)); break; } lore.add("&f  " + shortVal(YamlPatch.unquote(s))); }
                return item(Material.BOOKSHELF, "&e" + key + " &8(список)", lore);
            }
            default -> {
                lore.add("&7Сейчас: &f" + shortVal(YamlPatch.unquote(n.raw)));
                lore.add("&aЛКМ — ввести новое в чат");
                return item(Material.PAPER, "&e" + key, lore);
            }
        }
    }

    private Act nodeAct(Player p, String file, String parent, int page, YamlPatch.Node n) {
        Runnable again = () -> keys(p, file, parent, page);
        File f = new File(plugin.getDataFolder(), file);
        return (pl, t) -> {
            switch (n.kind) {
                case SECTION -> keys(pl, file, n.path, 0);
                case LIST -> pl.sendMessage("§7«" + n.key + "» — список, его удобнее править в файле §f" + file);
                case BOOL -> write(pl, f, file, n, n.raw.equals("true") ? "false" : "true", false, again);
                case INT, DOUBLE -> {
                    if (t == ClickType.DROP || t == ClickType.CONTROL_DROP) {
                        ask(pl, "число для «" + n.key + "» (сейчас " + n.raw + ")", v -> {
                            String x = v.trim().replace(',', '.');
                            if (!x.matches("-?\\d+(\\.\\d+)?") || (n.kind == YamlPatch.Kind.INT && x.contains("."))) { pl.sendMessage("§cНужно " + (n.kind == YamlPatch.Kind.INT ? "целое число." : "число.")); again.run(); return; }
                            write(pl, f, file, n, x, false, again);
                        }, again);
                        return;
                    }
                    int sign = t.isRightClick() ? -1 : 1;
                    boolean big = t.isShiftClick();
                    String nv;
                    if (n.kind == YamlPatch.Kind.INT) {
                        long v = Long.parseLong(n.raw) + sign * (big ? 10L : 1L);
                        nv = Long.toString(v);
                    } else {
                        double cur = Double.parseDouble(n.raw), step = Math.abs(cur) < 1 ? 0.05 : Math.abs(cur) < 10 ? 0.1 : 1;
                        if (big) step *= 10;
                        double v = Math.round((cur + sign * step) * 1000.0) / 1000.0;
                        nv = Double.toString(v);
                    }
                    write(pl, f, file, n, nv, false, again);
                }
                default -> ask(pl, "новое значение для «" + n.key + "» (сейчас: " + YamlPatch.unquote(n.raw) + ")", v -> {
                    boolean quoted = n.raw.startsWith("\"") || n.raw.startsWith("'");
                    boolean need = quoted || v.isEmpty() || v.matches("^[\\[\\]{}&*!|>'\"%@`#,?:-].*") || v.contains(": ") || v.contains(" #") || v.matches("(?i)true|false|null|yes|no|on|off|~|-?\\d+(\\.\\d+)?");
                    write(pl, f, file, n, v, need, again);
                }, again);
            }
        };
    }

    private void write(Player p, File f, String file, YamlPatch.Node n, String value, boolean quote, Runnable again) {
        try {
            YamlPatch.set(f, n.path, value, quote);
            try { plugin.reloadAll(); } catch (Throwable e) { p.sendMessage("§cСохранено, но перечитать не вышло: " + e); }
            p.sendActionBar(Component.text("§a" + n.key + " = " + shortVal(value)));
        } catch (Exception e) {
            p.sendMessage("§cНе удалось сохранить: " + e.getMessage());
        }
        later(again);
    }

    // ═════════════ ввод через чат ═════════════
    private void ask(Player p, String what, Consumer<String> done, Runnable back) {
        prompts.put(p.getUniqueId(), new Prompt(done, back));
        p.closeInventory();
        p.sendMessage("");
        p.sendMessage("§6▶ §fНапиши в чат: §e" + what);
        p.sendMessage("§7Чтобы отменить, напиши §fотмена§7.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        later(() -> {
            if (msg.equalsIgnoreCase("отмена") || msg.equalsIgnoreCase("cancel")) { p.sendMessage("§7Отменено."); pr.back().run(); }
            else pr.done().accept(msg);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); }

    // ═════════════ клики ═════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Page pg)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Act a = pg.acts.get(e.getSlot());
        if (a == null) return;
        if (!p.hasPermission("politichellhud.admin")) { p.closeInventory(); return; }
        a.click(p, e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder(false) instanceof Page) e.setCancelled(true); }

    // ═════════════ возможности ═════════════
    private record Feature(Material icon, String name, String what, String cmds, String file) {}

    private static final List<Feature> FEATURES = List.of(
            new Feature(Material.EMERALD, "Деньги над сердцами", "Монеты (Vault) и донат двумя плашками, анимация изменения.", "/hud · /hudbalance · /hudeco", "hud.yml"),
            new Feature(Material.OAK_SIGN, "Скорборд", "Боковая панель: два вида, компактный и полный. Рисует TAB.", "/sbstyle full|compact · /sb", "hud.yml"),
            new Feature(Material.PAINTING, "Шапка TAB", "Три стиля рамки над списком игроков: колонны, панель (как скорборд), минимал.", "/tabstyle pillars|panel|min", "hud.yml"),
            new Feature(Material.PLAYER_HEAD, "Ники, ранги, страны", "Над головой: ранг, ник, страна, значок голоса, AFK, цвет свой/чужой.", "/country · /hudrank", "gameplay.yml"),
            new Feature(Material.RECOVERY_COMPASS, "Компас и метки", "Полоса компаса с метками, растворяющимися к краям.", "/compass · /waypoint · /hudpoi", "compass.yml"),
            new Feature(Material.ENDER_EYE, "Дипломатия", "Союзники голубые, враги красные. Берётся из Towny.", "/n ally add · /n enemy add · /hudrel", "gameplay.yml"),
            new Feature(Material.CLOCK, "AFK", "Игрок только помечается AFK, без кика.", "/afk", "gameplay.yml"),
            new Feature(Material.NAME_TAG, "Карточки предметов", "Название, редкость, привязка, описание и характеристики на каждом предмете.", "/tip · /card …", "cards/cards.yml"),
            new Feature(Material.CHEST, "Свои предметы", "Описание собственных предметов сервера.", "/card give <ник> <id>", "cards/items.yml"),
            new Feature(Material.CRAFTING_TABLE, "Группы предметов", "Привязка и редкость для целых групп (например, все алмазные).", "/card group <шаблон> <привязка>", "cards/groups.yml"),
            new Feature(Material.BELL, "Обновление и пак", "Ставит ресурспак, настройки TAB и меню.", "/hudupdate всё · /hudpack", null),
            new Feature(Material.LECTERN, "Меню", "Главное меню, город, страна, участок (DeluxeMenus).", "/menu · /gorod · /strana · /uchastok", null),
            new Feature(Material.REDSTONE_TORCH, "Проверка", "Тест-метки и NPC для проверки отображения.", "/hudtest · /hudnpc", null));

    void features(Player p) {
        Page g = new Page("§6§lВозможности §8· §7что и где", 5);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int k = 0;
        for (Feature f : FEATURES) {
            List<String> lore = new ArrayList<>(wrap(f.what, "&7", 34));
            lore.add("");
            lore.add("&8Команды:");
            lore.addAll(wrap(f.cmds, "&f", 34));
            if (f.file != null) { lore.add(""); lore.add("&8Файл: &f" + f.file); lore.add("&aЛКМ — открыть настройки"); }
            g.set(slots[k++], item(f.icon, "&e&l" + f.name, lore), f.file == null ? null : nav(() -> keys(p, f.file, "", 0)));
        }
        g.set(4, item(Material.BOOKSHELF, "&6&lВозможности", "&7Что умеет плагин.", "&7Клик — открыть его настройки."), null);
        navRow(g, 5, () -> main(p));
        g.fill();
        p.openInventory(g.inv);
    }

    // ═════════════ состояние ═════════════
    private record Dep(String name, Material icon, String gives) {}

    private static final List<Dep> DEPS = List.of(
            new Dep("TAB", Material.NAME_TAG, "таб-лист, скорборд, ники"),
            new Dep("PlaceholderAPI", Material.PAPER, "переменные %phhud_*% для TAB и меню"),
            new Dep("Vault", Material.GOLD_INGOT, "монеты (баланс экономики)"),
            new Dep("PlayerPoints", Material.DIAMOND, "донат-валюта"),
            new Dep("Towny", Material.BELL, "города, нации, казна, дипломатия"),
            new Dep("SiegeWar", Material.IRON_SWORD, "осады и войны в скорборде"),
            new Dep("PlasmoVoice", Material.NOTE_BLOCK, "значок голоса над головой"),
            new Dep("LuckPerms", Material.SHIELD, "ранги (группы → иконка)"),
            new Dep("DeluxeMenus", Material.LECTERN, "меню /menu /gorod /strana /uchastok"),
            new Dep("Essentials", Material.COMPASS, "РТП (/tpr), домашние точки, AFK"));

    void status(Player p) {
        Page g = new Page("§6§lСостояние §8· §7плагины", 5);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        int k = 0;
        for (Dep d : DEPS) {
            var pl = Bukkit.getPluginManager().getPlugin(d.name);
            boolean ok = pl != null && pl.isEnabled();
            g.set(slots[k++], item(d.icon, (ok ? "&a" : "&c") + "&l" + d.name,
                    ok ? List.of("&aработает &8· v" + pl.getDescription().getVersion(), "", "&7Даёт: &f" + d.gives)
                       : List.of("&cне найден", "", "&7Даёт: &f" + d.gives, "&8Без него эта часть не работает.")), null);
        }
        CardManager m = cards();
        g.set(28, item(Material.NAME_TAG, "&e&lКарточки", "&7Редкостей: &f" + m.config.rarities.size(), "&7Своих предметов: &f" + m.config.custom.size(), "&7Правок ванили: &f" + m.config.vanilla.size(), "&7Групп: &f" + m.config.groups.size()), null);
        g.set(30, item(Material.PLAYER_HEAD, "&e&lОнлайн", "&7Игроков: &f" + Bukkit.getOnlinePlayers().size() + "&7/&f" + Bukkit.getMaxPlayers(), "&7Сервер: &f" + Bukkit.getName() + " " + Bukkit.getMinecraftVersion()), null);
        g.set(32, item(Material.REPEATER, "&e&lЭкономика и связки", wrap(plugin.economyInfo(), "&7", 34)), null);
        navRow(g, 5, () -> main(p));
        g.fill();
        p.openInventory(g.inv);
    }
}
