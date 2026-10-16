package com.politichell.hud.map;

import com.politichell.hud.CompassManager.Poi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Меню карты — открывается правой кнопкой мыши, пока открыта полноэкранная карта (F).
 * <p>
 * Зачем так: ванильный клиент показывает курсор мыши только внутри окна (сундук и т. п.); а любое такое окно
 * затемняет мир, в котором нарисована карта. Поэтому карта остаётся «живой» (WASD, колесо, прицел), а всё,
 * что удобнее делать мышью — сдвиг, масштаб, точки, настройки мини-карты — вынесено в это меню.
 * Закрыл меню (Esc / E) — вернулся на карту, всё применилось.
 */
final class MapMenu implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAN_PX = 40;            // на сколько «пикселей карты» сдвигает одна стрелка
    private static final Map<String, Material> ICON_ITEM = new LinkedHashMap<>();
    static {
        ICON_ITEM.put("wp", Material.ORANGE_BANNER); ICON_ITEM.put("star", Material.NETHER_STAR); ICON_ITEM.put("home", Material.RED_BED);
        ICON_ITEM.put("spawn", Material.LIGHT_BLUE_BANNER); ICON_ITEM.put("death", Material.SKELETON_SKULL); ICON_ITEM.put("town", Material.BELL);
        ICON_ITEM.put("war", Material.IRON_SWORD); ICON_ITEM.put("crown", Material.GOLDEN_HELMET);
    }

    /** Окно меню. kind: MAIN — главное, LIST — список точек. */
    static final class Holder implements InventoryHolder {
        final String kind;
        int page;
        List<Poi> shown = List.of();
        Inventory inv;
        Holder(String kind) { this.kind = kind; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final MapManager mgr;

    MapMenu(MapManager mgr) { this.mgr = mgr; }

    // ───────────── вспомогательное ─────────────
    private static Component c(String s) { return MM.deserialize("<!italic>" + s); }

    private static ItemStack it(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.displayName(c(name));
        if (lore.length > 0) im.lore(Arrays.stream(lore).map(MapMenu::c).toList());
        im.addItemFlags(ItemFlag.values());
        i.setItemMeta(im);
        return i;
    }

    private static ItemStack glow(ItemStack i) {
        ItemMeta im = i.getItemMeta();
        im.setEnchantmentGlintOverride(true);
        i.setItemMeta(im);
        return i;
    }

    private static ItemStack bg() { return it(Material.GRAY_STAINED_GLASS_PANE, " "); }

    private static String dist(double d) { return d >= 10000 ? String.format(Locale.ROOT, "%.1f км", d / 1000.0) : (int) d + " м"; }

    private static void click(Player p, float pitch) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, pitch); }

    // ───────────── главное меню ─────────────
    void openMain(Player p, MapManager.Session s) {
        Holder h = new Holder("MAIN");
        h.inv = Bukkit.createInventory(h, 45, MM.deserialize("<gradient:#4fe0f2:#a98bff><bold>Карта</bold></gradient> <dark_gray>·</dark_gray> <gray>управление"));
        fillMain(p, s, h.inv);
        p.openInventory(h.inv);
        s.menu = true;
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.4f, 1.6f);
    }

    private void fillMain(Player p, MapManager.Session s, Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, bg());
        int step = PAN_PX * s.scale;
        String ic = MapManager.iconOrder().get(s.icon);
        Material icm = ICON_ITEM.getOrDefault(ic, Material.ORANGE_BANNER);
        double dme = Math.hypot(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z);

        inv.setItem(4, it(Material.MAP, "<gradient:#4fe0f2:#a98bff><bold>Центр карты</bold></gradient>",
                "<gray>X <yellow>" + (int) Math.floor(s.x) + "</yellow>  Z <yellow>" + (int) Math.floor(s.z) + "</yellow>",
                "<gray>от тебя: <white>" + dist(dme),
                "<gray>масштаб: <white>1:" + s.scale + " <dark_gray>(≈ " + (127 * s.scale) + " блоков в ширину)",
                "", "<dark_gray>Закрыть меню — Esc или E: вернёшься на карту"));

        // сдвиг
        inv.setItem(11, it(Material.SPECTRAL_ARROW, "<aqua>▲ На север", "<gray>ЛКМ — на <white>" + step + " м", "<gray>Shift+ЛКМ — на <white>" + step * 3 + " м"));
        inv.setItem(19, it(Material.SPECTRAL_ARROW, "<aqua>◀ На запад", "<gray>ЛКМ — на <white>" + step + " м", "<gray>Shift+ЛКМ — на <white>" + step * 3 + " м"));
        inv.setItem(20, it(Material.RECOVERY_COMPASS, "<green>◎ К себе", "<gray>Вернуть центр карты на твою позицию"));
        inv.setItem(21, it(Material.SPECTRAL_ARROW, "<aqua>▶ На восток", "<gray>ЛКМ — на <white>" + step + " м", "<gray>Shift+ЛКМ — на <white>" + step * 3 + " м"));
        inv.setItem(29, it(Material.SPECTRAL_ARROW, "<aqua>▼ На юг", "<gray>ЛКМ — на <white>" + step + " м", "<gray>Shift+ЛКМ — на <white>" + step * 3 + " м"));

        // масштаб
        inv.setItem(13, it(Material.LIME_DYE, "<green>＋ Приблизить", "<gray>Масштаб 1:1 · 1:2 · 1:4 · 1:8", "<gray>Сейчас: <white>1:" + s.scale));
        inv.setItem(22, it(Material.SPYGLASS, "<white>Масштаб 1:" + s.scale, "<gray>Один пиксель карты = <white>" + s.scale + "×" + s.scale + " <gray>блоков",
                "<gray>На экране ≈ <white>" + (127 * s.scale) + " <gray>блоков в ширину"));
        inv.setItem(31, it(Material.RED_DYE, "<red>－ Отдалить", "<gray>Масштаб 1:1 · 1:2 · 1:4 · 1:8", "<gray>Сейчас: <white>1:" + s.scale));

        // точки
        inv.setItem(15, glow(it(icm, "<gradient:#ffe28a:#ff9d4d><bold>Поставить точку здесь</bold></gradient>",
                "<gray>В центре карты: X <yellow>" + (int) Math.floor(s.x) + "</yellow> Z <yellow>" + (int) Math.floor(s.z) + "</yellow>",
                "<gray>Значок: <white>" + MapManager.ICON_RU.get(ic), "", "<yellow>ЛКМ — поставить")));
        inv.setItem(24, it(Material.ITEM_FRAME, "<white>Значок: <yellow>" + MapManager.ICON_RU.get(ic),
                "<gray>ЛКМ — следующий, ПКМ — предыдущий", "<dark_gray>" + String.join(" · ", MapManager.ICON_RU.values())));
        inv.setItem(16, it(Material.BOOK, "<white>Мои точки", "<gray>Все точки в этом мире списком:", "<gray>перейти, переименовать, удалить",
                "", "<yellow>ЛКМ — открыть"));
        inv.setItem(25, it(Material.NAME_TAG, "<white>Переименовать точку", "<gray>Ближайшую к центру карты (твою)", "<gray>Название напишешь в чат", "", "<yellow>ЛКМ — выбрать"));
        inv.setItem(34, it(Material.BARRIER, "<red>Удалить точку", "<gray>Ближайшую к центру карты (твою)", "<dark_gray>Общие и системные точки не удаляются", "", "<yellow>ЛКМ — удалить"));

        // мини-карта
        NmBridge.Mini m = mgr.nm().mini(p);
        inv.setItem(37, it(Material.ENDER_EYE, "<white>Мини-карта: " + (m.on() ? "<green>включена" : "<red>выключена"), "<gray>Маленькая карта в углу экрана", "", "<yellow>ЛКМ — " + (m.on() ? "выключить" : "включить")));
        inv.setItem(38, it(Material.PISTON, "<white>Сторона: <yellow>" + (m.right() ? "справа" : "слева"), "<gray>Угол экрана, где стоит мини-карта", "", "<yellow>ЛКМ — переставить"));
        inv.setItem(39, it(m.round() ? Material.CLAY_BALL : Material.BRICK, "<white>Форма: <yellow>" + (m.round() ? "круглая" : "квадратная"), "", "<yellow>ЛКМ — сменить"));
        inv.setItem(40, it(Material.PAINTING, "<white>Рамка: <yellow>" + (m.frame() == null ? "нет" : m.frame()), "<gray>nova · amethyst · frost · crimson · graphite · black · tech · gold · steel · dark · нет",
                "", "<yellow>ЛКМ — следующая, ПКМ — предыдущая"));
        inv.setItem(41, it(Material.ZOMBIE_HEAD, "<white>Радар: " + (m.radar() ? "<green>включён" : "<red>выключен"), "<gray>Мобы и игроки точками на мини-карте", "", "<yellow>ЛКМ — переключить"));
        inv.setItem(42, it(Material.AMETHYST_SHARD, "<white>Масштаб мини-карты: <yellow>1:" + m.scale(), "<gray>Отдельно от большой карты", "", "<yellow>ЛКМ — крупнее, ПКМ — мельче"));

        inv.setItem(36, it(Material.ARROW, "<white>← Назад к карте", "<gray>То же, что Esc"));
        inv.setItem(44, it(Material.OAK_DOOR, "<red>Закрыть карту", "<gray>То же, что F"));
    }

    // ───────────── список точек ─────────────
    void openList(Player p, MapManager.Session s, int page) {
        Holder h = new Holder("LIST");
        h.inv = Bukkit.createInventory(h, 54, MM.deserialize("<gradient:#ffe28a:#ff9d4d><bold>Мои точки</bold></gradient> <dark_gray>·</dark_gray> <gray>карта"));
        h.page = page;
        fillList(p, s, h);
        p.openInventory(h.inv);
        s.menu = true;
        click(p, 1.4f);
    }

    private void fillList(Player p, MapManager.Session s, Holder h) {
        Inventory inv = h.inv;
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, bg());
        List<Poi> all = new ArrayList<>(mgr.pointsFor(p.getUniqueId()).orElse(List.of()));
        all.sort(Comparator.comparingDouble(o -> Math.hypot(o.x() - s.x, o.z() - s.z)));
        int pages = Math.max(1, (all.size() + 44) / 45);
        h.page = Math.max(0, Math.min(pages - 1, h.page));
        int from = h.page * 45;
        h.shown = all.subList(Math.min(from, all.size()), Math.min(from + 45, all.size()));
        if (all.isEmpty()) {
            inv.setItem(22, it(Material.PAPER, "<gray>Точек в этом мире пока нет", "<gray>Поставь первую: на карте <white>ЛКМ<gray>,", "<gray>или в меню карты — «Поставить точку здесь»"));
        }
        for (int i = 0; i < h.shown.size(); i++) {
            Poi o = h.shown.get(i);
            boolean mine = mgr.plugin().compass().isPersonal(p.getUniqueId(), o);
            Material m = ICON_ITEM.getOrDefault(o.icon(), Material.ORANGE_BANNER);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>X <yellow>" + (int) o.x() + "</yellow>  Z <yellow>" + (int) o.z() + "</yellow>");
            lore.add("<gray>от центра карты: <white>" + dist(Math.hypot(o.x() - s.x, o.z() - s.z)));
            lore.add("<gray>от тебя: <white>" + dist(Math.hypot(o.x() - p.getLocation().getX(), o.z() - p.getLocation().getZ())));
            lore.add(mine ? "<aqua>Твоя точка" : "<yellow>Общая / системная");
            lore.add("");
            lore.add("<yellow>ЛКМ — показать на карте");
            if (mine) { lore.add("<yellow>ПКМ — переименовать"); lore.add("<red>Shift+ЛКМ — удалить"); }
            inv.setItem(i, it(m, "<white>" + o.name().replace("<", "‹"), lore.toArray(new String[0])));
        }
        inv.setItem(45, it(Material.ARROW, "<white>← В меню карты"));
        if (h.page > 0) inv.setItem(48, it(Material.SPECTRAL_ARROW, "<white>◀ Назад", "<gray>Страница " + h.page + " из " + pages));
        inv.setItem(49, it(Material.PAPER, "<white>Страница " + (h.page + 1) + " / " + pages, "<gray>Всего точек: <white>" + all.size(), "<gray>Сначала — ближайшие к центру карты"));
        if (h.page < pages - 1) inv.setItem(50, it(Material.SPECTRAL_ARROW, "<white>Дальше ▶", "<gray>Страница " + (h.page + 2) + " из " + pages));
        inv.setItem(53, it(Material.OAK_DOOR, "<red>Закрыть меню"));
    }

    // ───────────── клики ─────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        MapManager.Session s = mgr.session(p);
        if (s == null) { p.closeInventory(); return; }
        boolean left = e.getClick() == org.bukkit.event.inventory.ClickType.LEFT || e.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_LEFT;
        boolean right = e.getClick() == org.bukkit.event.inventory.ClickType.RIGHT || e.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT;
        boolean shift = e.isShiftClick();
        if (!left && !right) return;
        int slot = e.getRawSlot();
        if (h.kind.equals("MAIN")) clickMain(p, s, h, slot, left, right, shift);
        else clickList(p, s, h, slot, left, right, shift);
    }

    private void clickMain(Player p, MapManager.Session s, Holder h, int slot, boolean left, boolean right, boolean shift) {
        int step = PAN_PX * s.scale * (shift ? 3 : 1);
        NmBridge nm = mgr.nm();
        switch (slot) {
            case 11 -> { mgr.focus(p, s, s.x, s.z - step); click(p, 1.2f); }
            case 29 -> { mgr.focus(p, s, s.x, s.z + step); click(p, 1.2f); }
            case 19 -> { mgr.focus(p, s, s.x - step, s.z); click(p, 1.2f); }
            case 21 -> { mgr.focus(p, s, s.x + step, s.z); click(p, 1.2f); }
            case 20 -> { mgr.center(p, s); }
            case 13, 31 -> {
                int[] sc = MapManager.scales();
                int i = Arrays.binarySearch(sc, s.scale);
                if (i < 0) i = 2;
                int ni = Math.max(0, Math.min(sc.length - 1, i + (slot == 13 ? -1 : 1)));
                if (ni == i) { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.4f, 1.2f); return; }
                mgr.applyScale(p, s, sc[ni]);
                click(p, 0.8f + 0.3f * (sc.length - ni));
            }
            case 15 -> { mgr.placePoint(p, s); mgr.refreshSnapshot(p); }
            case 24 -> {
                int n = MapManager.iconOrder().size();
                s.icon = Math.floorMod(s.icon + (right ? -1 : 1), n);
                click(p, 1.6f);
            }
            case 16 -> { openList(p, s, 0); return; }
            case 25 -> { if (mgr.startRename(p, s)) p.closeInventory(); return; }
            case 34 -> mgr.removeAimed(p, s);
            case 37 -> { p.sendActionBar(Component.text(nm.miniSetting(p, "mini", ""))); click(p, 1.0f); }
            case 38 -> { var m = nm.mini(p); nm.miniSetting(p, "side", m.right() ? "left" : "right"); click(p, 1.0f); }
            case 39 -> { var m = nm.mini(p); nm.miniSetting(p, "style", m.round() ? "square" : "round"); click(p, 1.0f); }
            case 40 -> {
                List<String> names = new ArrayList<>(nm.frameNames());
                names.add("none");
                var m = nm.mini(p);
                int i = names.indexOf(m.frame() == null ? "none" : m.frame());
                if (i < 0) i = names.size() - 1;
                String next = names.get(Math.floorMod(i + (right ? -1 : 1), names.size()));
                nm.miniSetting(p, "frame", next);
                click(p, 1.3f);
            }
            case 41 -> { nm.miniSetting(p, "radar", ""); click(p, 1.0f); }
            case 42 -> {
                int[] sc = MapManager.scales();
                var m = nm.mini(p);
                int i = Math.max(0, Arrays.binarySearch(sc, m.scale()));
                int ni = Math.max(0, Math.min(sc.length - 1, i + (right ? 1 : -1)));
                nm.miniSetting(p, "zoom", String.valueOf(sc[ni]));
                click(p, 1.1f);
            }
            case 36 -> { p.closeInventory(); return; }
            case 44 -> { p.closeInventory(); mgr.close(p, true); return; }
            default -> { return; }
        }
        fillMain(p, s, h.inv);
        mgr.sendInfo(p, s);
    }

    private void clickList(Player p, MapManager.Session s, Holder h, int slot, boolean left, boolean right, boolean shift) {
        if (slot == 45) { openMain(p, s); return; }
        if (slot == 53) { p.closeInventory(); return; }
        if (slot == 48 && h.page > 0) { h.page--; fillList(p, s, h); click(p, 1.3f); return; }
        if (slot == 50) { h.page++; fillList(p, s, h); click(p, 1.3f); return; }
        if (slot < 0 || slot >= h.shown.size()) return;
        Poi o = h.shown.get(slot);
        boolean mine = mgr.plugin().compass().isPersonal(p.getUniqueId(), o);
        if (left && shift) {
            if (!mine) { p.sendActionBar(Component.text("§7«" + o.name() + "» — общая точка, её не удалить")); return; }
            if (mgr.plugin().compass().removePersonal(p.getUniqueId(), o)) {
                mgr.refreshSnapshot(p);
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8f, 1.0f);
                fillList(p, s, h);
            }
        } else if (left) {
            mgr.focus(p, s, o.x(), o.z());
            p.closeInventory();
            click(p, 1.5f);
            mgr.sendInfo(p, s);
        } else if (right && mine) {
            mgr.beginRename(p, s, o);
            p.closeInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        // если в этот же момент открывается другое окно меню — флаг вернёт openMain/openList
        MapManager.Session s = mgr.session(p);
        if (s == null) return;
        s.menu = false;
        s.fwd = s.back = s.left = s.right = s.sprint = false;
    }
}
