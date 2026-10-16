package com.politichell.hud.map;

import com.politichell.hud.CompassManager;
import com.politichell.hud.CompassManager.Poi;
import com.politichell.hud.PoliticHellHud;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.GameMode;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полноэкранная карта (клавиша F) поверх форка NMinimap («NMinimap-PH»).
 * <p>
 * Этот класс не зависит от NMinimap напрямую — всё общение через {@link NmBridge}, который грузится только
 * если плагин NMinimap установлен. Без него карта просто недоступна, остальной HUD работает.
 * <p>
 * Управление (ванильный клиент не отдаёт серверу «любые» клавиши, поэтому — то, что отдаёт):
 *   F — открыть/закрыть · WASD — двигать карту (Ctrl = быстро) · колесо мыши — масштаб ·
 *   Shift + колесо — сменить значок · ЛКМ — поставить точку под прицелом · Shift+ЛКМ — удалить ближайшую · ПКМ — меню карты (кнопки мышью) ·
 *   Пробел — вернуться к себе · Q — прыгнуть к следующей точке · Shift+Q — переименовать точку под прицелом.
 */
public class MapManager implements Listener, Runnable {
    public static final Map<String, String> ICON_RU = new LinkedHashMap<>();
    static {
        ICON_RU.put("wp", "Метка"); ICON_RU.put("star", "Звезда"); ICON_RU.put("home", "Дом"); ICON_RU.put("spawn", "Флаг");
        ICON_RU.put("death", "Череп"); ICON_RU.put("town", "Город"); ICON_RU.put("war", "Война"); ICON_RU.put("crown", "Корона");
    }
    private static final List<String> ICON_ORDER = new ArrayList<>(ICON_RU.keySet());
    private static final int[] SCALES = {1, 2, 4, 8};
    private static final NamespacedKey FREEZE_KEY = new NamespacedKey("politichellhud", "map_freeze");

    /** Состояние открытой карты одного игрока. */
    static final class Session {
        double x, z;
        int scale, icon;
        boolean fwd, back, left, right, sprint, jump, sneakInput;
        int lastHotbar, tick, clickCd;
        int cycle = -1;
        Poi aim;                    // точка под прицелом
        Poi renaming;               // ждём название в чате
        boolean menu;               // открыто меню карты (ПКМ): клавиши не двигают карту
        boolean tmpItem;            // в руку временно выдан предмет-заглушка (иначе ПКМ/Q с пустой рукой до сервера не доходят)
        long renameUntil;
        int clickDelay() { return clickCd; }
        void setClickDelay(int v) { clickCd = v; }
        void tickClick() { if (clickCd > 0) clickCd--; }
    }

    private final PoliticHellHud plugin;
    private NmBridge nm;
    private MapMenu menu;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Снимок точек для асинхронной отрисовки (обновляется в основном потоке). */
    private final Map<UUID, List<Poi>> snapshot = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> helpShown = new HashMap<>();
    private final File dataFile;
    private int tick;

    // config
    private boolean enabled, useSwapKey, closeOnDamage, freeze, showHelp;
    private int helpTimes, panSpeed, sprintMul, clickDelay, maxPointDist;
    private double reachPx;

    public MapManager(PoliticHellHud plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "map-data.yml");
    }

    // ───────────── жизненный цикл ─────────────
    public boolean enable() {
        reload();
        if (Bukkit.getPluginManager().getPlugin("NMinimap") == null) {
            plugin.getLogger().info("Полноэкранная карта: плагин NMinimap не найден — карта (F) отключена. Установка: docs/Карта.md");
            return false;
        }
        try {
            nm = new NmBridge(this);
        } catch (Throwable t) {
            plugin.getLogger().warning("Полноэкранная карта: NMinimap установлен, но это не сборка «NMinimap-PH» (" + t + "). Положи NMinimap-PH-*.jar из архива PoliticHell.");
            nm = null;
            return false;
        }
        if (!nm.supportsFullscreen()) {
            plugin.getLogger().warning("Полноэкранная карта: в установленном NMinimap нет режима fullscreen — нужна сборка NMinimap-PH.");
            nm = null;
            return false;
        }
        if (YamlConfiguration.loadConfiguration(dataFile).contains("help")) {
            var y = YamlConfiguration.loadConfiguration(dataFile);
            for (String k : y.getConfigurationSection("help").getKeys(false)) {
                try { helpShown.put(UUID.fromString(k), y.getInt("help." + k)); } catch (IllegalArgumentException ignored) {}
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(nm, plugin);
        menu = new MapMenu(this);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this, 20L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateNearby, 40L, 20L);
        // NMinimap собирает свой пак при старте; если к нашему паку он ещё не добавлен — пересобрать (на первом запуске)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (plugin.packs() != null && plugin.packs().enabled() && !plugin.packs().status().contains("NMinimap")
                    && new File(plugin.getDataFolder().getParentFile(), "NMinimap/built-pack.zip").isFile())
                plugin.packs().reloadAndSend(null);
        }, 100L);
        plugin.getLogger().info("Полноэкранная карта: NMinimap-PH подключён (F — открыть).");
        return true;
    }

    public void reload() {
        var c = plugin.cfg("map");
        enabled = c.getBoolean("enabled", true);
        useSwapKey = c.getBoolean("open-with-f", true);
        closeOnDamage = c.getBoolean("close-on-damage", true);
        freeze = c.getBoolean("freeze-player", true);
        showHelp = c.getBoolean("help.show", true);
        helpTimes = c.getInt("help.times", 3);
        panSpeed = Math.max(1, c.getInt("pan-speed", 30));
        sprintMul = Math.max(1, c.getInt("pan-sprint-multiplier", 3));
        clickDelay = Math.max(1, c.getInt("click-delay-ticks", 5));
        reachPx = Math.max(2, c.getDouble("select-radius-px", 7));
        maxPointDist = c.getInt("max-point-distance", 0);
        nearbyEnabled = c.getBoolean("nearby.enabled", true);
        nearbyRadius = Math.max(4, c.getDouble("nearby.radius", 64));
        nearbyFrames.clear();
        for (String f : c.getStringList("nearby.frames")) nearbyFrames.add(f.toLowerCase(Locale.ROOT));
        if (nearbyFrames.isEmpty()) nearbyFrames.addAll(List.of("nova", "amethyst", "frost", "crimson"));
    }

    // ───────────── слот «игроков рядом» в рамке мини-карты ─────────────
    private volatile boolean nearbyEnabled = true;
    private volatile double nearbyRadius = 64;
    private final Set<String> nearbyFrames = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> nearby = new ConcurrentHashMap<>();

    boolean nearbyOn() { return nearbyEnabled; }
    boolean nearbyFrame(String frame) { return nearbyFrames.contains(frame.toLowerCase(Locale.ROOT)); }
    int nearbyOf(Player p) { return nearby.getOrDefault(p.getUniqueId(), 0); }

    /** Раз в секунду: сколько игроков в радиусе у каждого (тот же мир; не считаются наблюдатели, скрытые от игрока и vanish). Максимум 99. */
    void updateNearby() {
        if (!nearbyEnabled) { nearby.clear(); return; }
        double r2 = nearbyRadius * nearbyRadius;
        nearby.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            int n = 0;
            for (Player o : l.getWorld().getPlayers()) {
                if (o == p || o.getGameMode() == org.bukkit.GameMode.SPECTATOR || !p.canSee(o) || o.hasMetadata("vanished")) continue;
                if (o.getLocation().distanceSquared(l) <= r2) n++;
            }
            nearby.put(p.getUniqueId(), Math.min(99, n));
        }
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) close(p, false);
        }
        sessions.clear();
        saveData();
    }

    private void saveData() {
        try {
            var y = new YamlConfiguration();
            for (var e : helpShown.entrySet()) y.set("help." + e.getKey(), e.getValue());
            y.save(dataFile);
        } catch (IOException ignored) {}
    }

    public boolean available() { return nm != null && enabled; }
    public boolean isOpen(UUID id) { return sessions.containsKey(id); }
    public boolean isOpen(Player p) { return sessions.containsKey(p.getUniqueId()); }
    Optional<List<Poi>> pointsFor(UUID id) { return Optional.ofNullable(snapshot.get(id)); }
    PoliticHellHud plugin() { return plugin; }
    NmBridge nm() { return nm; }
    Session session(Player p) { return sessions.get(p.getUniqueId()); }
    MapMenu menu() { return menu; }
    static List<String> iconOrder() { return ICON_ORDER; }
    static int[] scales() { return SCALES; }

    // ───────────── предмет-заглушка в пустой руке ─────────────
    // Ванильный клиент не шлёт серверу ПКМ «по воздуху» и клавишу Q, если в руке пусто. Чтобы меню (ПКМ) и Q работали всегда,
    // на время карты в пустую выбранную ячейку кладётся бумага «Карта»; при закрытии она исчезает (и при входе — на случай сбоя).
    private static final NamespacedKey TMP_KEY = new NamespacedKey("politichellhud", "map_tmp_item");

    static boolean isTmp(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(TMP_KEY, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    private void giveTmp(Player p, Session s) {
        var inv = p.getInventory();
        var cur = inv.getItem(inv.getHeldItemSlot());
        if (cur != null && !cur.getType().isAir()) return;
        var it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        var im = it.getItemMeta();
        im.displayName(Component.text("Карта").color(net.kyori.adventure.text.format.NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        im.getPersistentDataContainer().set(TMP_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        inv.setItem(inv.getHeldItemSlot(), it);
        s.tmpItem = true;
    }

    static void takeTmp(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isTmp(inv.getItem(i))) inv.setItem(i, null);
        if (isTmp(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    // ───────────── открыть / закрыть ─────────────
    public void toggle(Player p) { if (isOpen(p)) close(p, true); else open(p); }

    public void open(Player p) {
        if (!enabled) { p.sendMessage("§cПолноэкранная карта выключена админом."); return; }
        if (nm == null) { p.sendMessage("§cКарта не установлена на сервере (нужен плагин NMinimap-PH)."); return; }
        if (isOpen(p)) return;
        if (!p.hasPermission("politichellhud.map")) { p.sendMessage("§cУ тебя нет доступа к карте."); return; }
        if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) return;
        String why = nm.open(p, plugin.cfg("map").getInt("default-scale", 4));
        if (why != null) { p.sendMessage("§c" + why); return; }
        Session s = new Session();
        s.x = p.getLocation().getX(); s.z = p.getLocation().getZ();
        s.scale = nm.scale(p);
        s.lastHotbar = p.getInventory().getHeldItemSlot();
        String ic = plugin.cfg("map").getString("default-icon", "wp");
        s.icon = Math.max(0, ICON_ORDER.indexOf(ic));
        sessions.put(p.getUniqueId(), s);
        giveTmp(p, s);
        refreshSnapshot(p);
        if (freeze) freeze(p, true);
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
        sendHelp(p);
        sendInfo(p, s);
    }

    public void close(Player p, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        snapshot.remove(p.getUniqueId());
        if (s == null) return;
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof MapMenu.Holder) p.closeInventory();
        takeTmp(p);
        if (nm != null) nm.close(p);
        freeze(p, false);
        p.sendActionBar(Component.empty());
        if (sound) p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 0.8f);
    }

    private void freeze(Player p, boolean on) {
        try {
            for (Attribute a : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.FLYING_SPEED}) {
                AttributeInstance ai = p.getAttribute(a);
                if (ai == null) continue;
                ai.removeModifier(FREEZE_KEY);
                if (on) ai.addTransientModifier(new AttributeModifier(FREEZE_KEY, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Карта: не удалось заморозить игрока: " + t);
        }
    }

    private void sendHelp(Player p) {
        if (!showHelp) return;
        int n = helpShown.getOrDefault(p.getUniqueId(), 0);
        if (n >= helpTimes) return;
        helpShown.put(p.getUniqueId(), n + 1);
        saveData();
        p.sendMessage("§8▎ §bКарта§7: §fWASD §7— двигать (Ctrl — быстро) · §fколесо §7— масштаб · §fShift+колесо §7— значок");
        p.sendMessage("§8▎ §fЛКМ §7— поставить точку · §fПКМ §7— §bменю карты §7(кнопки мышью) · §fShift+ЛКМ §7— удалить · §fПробел §7— к себе · §fQ §7— к след. точке · §fF §7— закрыть");
    }

    // ───────────── события игрока ─────────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (isOpen(p)) { e.setCancelled(true); close(p, true); return; }
        if (!useSwapKey || nm == null || !enabled) return;
        if (!p.hasPermission("politichellhud.map")) return;
        e.setCancelled(true);
        open(p);
    }

    @EventHandler
    public void onInput(org.bukkit.event.player.PlayerInputEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        var in = e.getInput();
        boolean jumpNow = in.isJump();
        if (jumpNow && !s.jump) center(e.getPlayer(), s);
        s.fwd = in.isForward(); s.back = in.isBackward(); s.left = in.isLeft(); s.right = in.isRight();
        s.sprint = in.isSprint(); s.jump = jumpNow; s.sneakInput = in.isSneak();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHotbar(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        int d = Math.floorMod(e.getNewSlot() - e.getPreviousSlot(), 9);
        int dir = d <= 4 ? 1 : -1;               // колесо вниз (следующий слот) = отдалить / следующий значок
        if (p.isSneaking() || s.sneakInput) {
            s.icon = Math.floorMod(s.icon + dir, ICON_ORDER.size());
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
        } else {
            int i = Arrays.binarySearch(SCALES, s.scale);
            if (i < 0) i = 2;
            int ni = Math.max(0, Math.min(SCALES.length - 1, i + dir));
            if (ni != i) {
                s.scale = SCALES[ni];
                nm.setScale(p, s.scale);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4f, 0.8f + 0.3f * (SCALES.length - ni));
            }
        }
        sendInfo(p, s);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        if (s.clickDelay() > 0) return;
        s.setClickDelay(clickDelay);
        if (p.isSneaking() || s.sneakInput) removeAimed(p, s); else placePoint(p, s);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) && s.clickDelay() == 0) {
            s.setClickDelay(clickDelay);
            if (menu != null) menu.openMain(p, s);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (p.isSneaking() || s.sneakInput) startRename(p, s); else nextPoint(p, s);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.renaming == null) return;
        e.setCancelled(true);
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Poi target = s.renaming;
        s.renaming = null;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("отмена") || text.isEmpty()) { p.sendMessage("§7Переименование отменено."); return; }
            String name = text.length() > 24 ? text.substring(0, 24) : text;
            name = name.replace('§', ' ').replace('&', ' ').trim();
            String err = plugin.compass().renamePersonal(p.getUniqueId(), target, name);
            if (err != null) p.sendMessage("§c" + err);
            else { p.sendMessage("§aТочка переименована: §f" + name); refreshSnapshot(p); }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        if (!freeze || !isOpen(e.getPlayer())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getZ() != t.getZ()) {
            Location n = new Location(f.getWorld(), f.getX(), t.getY(), f.getZ(), t.getYaw(), t.getPitch());
            e.setTo(n);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!closeOnDamage || !(e.getEntity() instanceof Player p) || !isOpen(p)) return;
        if (e.getFinalDamage() <= 0) return;
        close(p, false);
        p.sendActionBar(Component.text("§cКарта закрыта — ты получил урон"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isOpen(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockDamage(BlockDamageEvent e) { if (isOpen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent e) { if (isOpen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent e) { if (isOpen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onConsume(PlayerItemConsumeEvent e) { if (isOpen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) { if (isOpen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onInvOpen(InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof MapMenu.Holder) return;
        if (e.getPlayer() instanceof Player p && isOpen(p)) close(p, false);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickWhileOpen(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !isOpen(p)) return;
        if (e.getInventory().getHolder() instanceof MapMenu.Holder) return;     // меню карты разбирает MapMenu
        e.setCancelled(true);                                                     // собственный инвентарь на карте не трогаем
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) { e.getDrops().removeIf(MapManager::isTmp); e.getItemsToKeep().removeIf(MapManager::isTmp); close(e.getEntity(), false); }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        snapshot.remove(e.getPlayer().getUniqueId());
        if (s != null) { if (nm != null) nm.close(e.getPlayer()); freeze(e.getPlayer(), false); takeTmp(e.getPlayer()); }
    }
    @EventHandler public void onGm(PlayerGameModeChangeEvent e) { if (e.getNewGameMode() == GameMode.SPECTATOR && isOpen(e.getPlayer())) close(e.getPlayer(), false); }
    @EventHandler public void onTp(PlayerTeleportEvent e) {
        if (isOpen(e.getPlayer()) && (e.getTo() == null || e.getFrom().getWorld() != e.getTo().getWorld())) close(e.getPlayer(), false);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { freeze(e.getPlayer(), false); takeTmp(e.getPlayer()); }   // страховка: убрать остатки модификатора

    // ───────────── действия на карте ─────────────
    void center(Player p, Session s) {
        s.x = p.getLocation().getX(); s.z = p.getLocation().getZ(); s.cycle = -1;
        nm.setView(p, s.x, s.z);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.3f, 1.3f);
    }

    private void nextPoint(Player p, Session s) {
        List<Poi> pts = snapshot.getOrDefault(p.getUniqueId(), List.of());
        if (pts.isEmpty()) { p.sendActionBar(Component.text("§7Точек в этом мире нет")); return; }
        s.cycle = (s.cycle + 1) % pts.size();
        Poi o = pts.get(s.cycle);
        s.x = o.x(); s.z = o.z();
        nm.setView(p, s.x, s.z);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        sendInfo(p, s);
    }

    void placePoint(Player p, Session s) {
        if (plugin.compass() == null) return;
        Poi near = nearest(p, s, false);
        if (near != null) {
            p.sendActionBar(Component.text("§7Здесь уже есть точка «§f" + near.name() + "§7». Удалить: §fShift+ЛКМ"));
            return;
        }
        String icon = ICON_ORDER.get(s.icon);
        if (maxPointDist > 0 && Math.hypot(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z) > maxPointDist) {
            p.sendActionBar(Component.text("§cСлишком далеко от тебя (максимум " + maxPointDist + " м)"));
            return;
        }
        World w = p.getWorld();
        double y = surfaceY(w, s.x, s.z, p.getLocation().getY());
        Location loc = new Location(w, Math.floor(s.x) + 0.5, y, Math.floor(s.z) + 0.5);
        String name = plugin.compass().freeName(p.getUniqueId());
        String err = plugin.compass().addPersonal(p, name, icon, loc);
        if (err != null) { p.sendActionBar(Component.text("§c" + err)); p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.5f, 1f); return; }
        refreshSnapshot(p);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        p.sendMessage("§8▎ §aТочка «§f" + name + "§a» (" + ICON_RU.get(icon) + ") — X " + (int) loc.getX() + " Z " + (int) loc.getZ() + ". §7Переименовать: §fShift+Q §7(или §fПКМ → Переименовать§7)");
    }

    void removeAimed(Player p, Session s) {
        Poi o = nearest(p, s, true);
        if (o == null) {
            Poi any = nearest(p, s, false);
            p.sendActionBar(Component.text(any != null ? "§7«" + any.name() + "» — системная точка, её не удалить" : "§7Под прицелом нет твоей точки"));
            return;
        }
        if (plugin.compass().removePersonal(p.getUniqueId(), o)) {
            refreshSnapshot(p);
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8f, 1.0f);
            p.sendActionBar(Component.text("§aТочка «§f" + o.name() + "§a» удалена"));
        }
    }

    boolean startRename(Player p, Session s) {
        Poi o = nearest(p, s, true);
        if (o == null) { p.sendActionBar(Component.text("§7Наведи прицел на свою точку")); return false; }
        beginRename(p, s, o);
        return true;
    }

    void beginRename(Player p, Session s, Poi o) {
        s.renaming = o;
        s.renameUntil = System.currentTimeMillis() + 60_000;
        p.sendMessage("§8▎ §bНапиши в чат новое название для «§f" + o.name() + "§b» (до 24 символов) или §fотмена§b.");
    }

    /** Перевести центр карты в точку. */
    void focus(Player p, Session s, double x, double z) {
        s.x = clamp(x); s.z = clamp(z); s.cycle = -1;
        nm.setView(p, s.x, s.z);
    }

    void applyScale(Player p, Session s, int sc) {
        s.scale = sc;
        nm.setScale(p, sc);
    }

    /** Ближайшая точка в радиусе select-radius-px от центра. personalOnly — только личные. */
    Poi nearest(Player p, Session s, boolean personalOnly) {
        double reach = reachPx * s.scale;
        Poi best = null; double bd = Double.MAX_VALUE;
        for (Poi o : snapshot.getOrDefault(p.getUniqueId(), List.of())) {
            if (personalOnly && !plugin.compass().isPersonal(p.getUniqueId(), o)) continue;
            double d = Math.hypot(o.x() - s.x, o.z() - s.z);
            if (d <= reach && d < bd) { bd = d; best = o; }
        }
        return best;
    }

    private double surfaceY(World w, double x, double z, double fallback) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        try {
            if (w.isChunkLoaded(bx >> 4, bz >> 4)) return w.getHighestBlockYAt(bx, bz) + 1;
        } catch (Throwable ignored) {}
        return fallback;
    }

    void refreshSnapshot(Player p) {
        if (plugin.compass() == null) return;
        snapshot.put(p.getUniqueId(), List.copyOf(plugin.compass().allPois(p, p.getWorld())));
    }

    /** Для маркеров NMinimap (в т.ч. на мини-карте): точки игрока, если снимок уже есть. */
    List<Poi> markersFor(Player p) { return snapshot.getOrDefault(p.getUniqueId(), List.of()); }

    // ───────────── покадровая задача ─────────────
    @Override
    public void run() {
        tick++;
        if (nm == null) return;
        // снимок точек для мини-карты — у всех, у кого она включена (раз в 2 секунды)
        if (tick % 40 == 0) for (Player p : Bukkit.getOnlinePlayers()) if (!isOpen(p) && nm.minimapOn(p)) refreshSnapshot(p);
        for (var en : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            Session s = en.getValue();
            if (p == null || !p.isOnline()) { sessions.remove(en.getKey()); continue; }
            if (!nm.isFullscreen(p)) { close(p, false); continue; }   // карту выключили извне (например /minimap disable)
            s.tick++;
            s.tickClick();
            if (s.renaming != null && System.currentTimeMillis() > s.renameUntil) s.renaming = null;
            if (s.menu) { s.fwd = s.back = s.left = s.right = s.sprint = false; if (s.tick % 4 == 0) { s.aim = nearest(p, s, false); sendInfo(p, s); } continue; }
            double step = Math.max(1, panSpeed / 20.0) * s.scale * (s.sprint ? sprintMul : 1);
            double dx = (s.right ? 1 : 0) - (s.left ? 1 : 0), dz = (s.back ? 1 : 0) - (s.fwd ? 1 : 0);
            if (dx != 0 || dz != 0) {
                double n = Math.sqrt(dx * dx + dz * dz);
                s.x = clamp(s.x + dx / n * step); s.z = clamp(s.z + dz / n * step);
                s.cycle = -1;
                nm.setView(p, s.x, s.z);
            }
            if (s.tick % 4 == 0) { s.aim = nearest(p, s, false); sendInfo(p, s); }
            if (s.tick % 40 == 0) refreshSnapshot(p);
        }
    }

    static double clamp(double v) { return Math.max(-29_999_000, Math.min(29_999_000, v)); }

    // ───────────── нижняя строка с координатами ─────────────
    void sendInfo(Player p, Session s) {
        Location pl = p.getLocation();
        int cx = (int) Math.floor(s.x), cz = (int) Math.floor(s.z);
        double dist = Math.hypot(pl.getX() - s.x, pl.getZ() - s.z);
        String y = "—";
        try {
            if (p.getWorld().isChunkLoaded(cx >> 4, cz >> 4)) y = String.valueOf(p.getWorld().getHighestBlockYAt(cx, cz));
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        sb.append("§b✦ §fX §e").append(cx).append(" §fZ §e").append(cz).append(" §7(Y≈").append(y).append(")");
        sb.append(" §8│ §7от тебя §f").append(fmtDist(dist));
        sb.append(" §8│ §7масштаб §f1:").append(s.scale);
        sb.append(" §8│ §7значок §f").append(ICON_RU.get(ICON_ORDER.get(s.icon)));
        Poi a = s.aim != null ? s.aim : nearest(p, s, false);
        sb.append(" §8│ §7ПКМ — меню");
        if (a != null) sb.append(" §8│ §b«").append(a.name()).append("» §7").append(fmtDist(Math.hypot(pl.getX() - a.x(), pl.getZ() - a.z())));
        p.sendActionBar(Component.text(sb.toString()));
    }

    private static String fmtDist(double d) {
        if (d >= 10000) return String.format(Locale.ROOT, "%.1f км", d / 1000.0);
        return (int) d + " м";
    }

    // ───────────── команды ─────────────
    public boolean command(CommandSender sender, Command cmd, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только из игры"); return true; }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "", "open", "toggle", "открыть" -> toggle(p);
            case "help", "помощь" -> {
                if (nm == null) { p.sendMessage("§cКарта не установлена на сервере."); return true; }
                p.sendMessage("§b§lКарта §7— управление:");
                p.sendMessage("§8▎ §fF §7— открыть/закрыть   §fWASD §7— двигать (Ctrl — быстро)   §fПробел §7— к себе");
                p.sendMessage("§8▎ §fколесо §7— масштаб 1:1…1:8   §fShift+колесо §7— значок точки");
                p.sendMessage("§8▎ §fЛКМ §7— поставить точку под прицелом   §fShift+ЛКМ §7— удалить   §fПКМ §7— меню карты (мышью)");
                p.sendMessage("§8▎ §fQ §7— к следующей точке   §fShift+Q §7— переименовать точку");
                p.sendMessage("§8▎ §7Мини-карта в углу: §f/map mini on|off§7, §f/map side left|right§7, §f/map style round|square§7, §f/map zoom 1|2|4|8§7, §f/map frame <рамка>");
                p.sendMessage("§8▎ §7Свои точки: §f/waypoint list§7. Значки: §f" + String.join(" ", ICON_RU.keySet()));
            }
            case "mini", "side", "style", "zoom", "radar", "frame" -> {
                if (nm == null) { p.sendMessage("§cКарта не установлена на сервере."); return true; }
                String arg = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                p.sendMessage(nm.miniSetting(p, sub, arg));
            }
            case "reload" -> {
                if (!p.hasPermission("politichellhud.admin")) { p.sendMessage("§cНет прав"); return true; }
                reload(); p.sendMessage("§amap.yml перечитан.");
            }
            default -> p.sendMessage("§e/map §7— открыть · §e/map help §7— управление · §e/map mini|side|style|zoom §7— мини-карта");
        }
        return true;
    }

    public List<String> complete(String[] a) {
        if (a.length == 1) return List.of("help", "mini", "side", "style", "zoom", "radar", "frame");
        if (a.length == 2) switch (a[0].toLowerCase(Locale.ROOT)) {
            case "mini", "radar" -> { return List.of("on", "off"); }
            case "side" -> { return List.of("left", "right"); }
            case "style" -> { return List.of("round", "square"); }
            case "zoom" -> { return List.of("1", "2", "4", "8"); }
            case "frame" -> { List<String> l = new ArrayList<>(nm != null ? nm.frameNames() : List.of()); l.add("none"); return l; }
            default -> { }
        }
        return List.of();
    }
}
