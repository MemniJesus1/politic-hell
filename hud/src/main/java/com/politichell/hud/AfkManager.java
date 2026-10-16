package com.politichell.hud;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** AFK по бездействию (движение / чат / команды / клики) + ручной /afk. Спящий в кровати показывается тем же значком Zzz. */
public class AfkManager implements Listener, Runnable {
    private final PoliticHellHud plugin;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    private final Set<UUID> manual = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> manualAt = new ConcurrentHashMap<>();
    private final Set<UUID> sleeping = ConcurrentHashMap.newKeySet();

    public AfkManager(PoliticHellHud plugin) { this.plugin = plugin; }

    public boolean isAfk(UUID id) { return afk.contains(id); }
    public boolean isSleeping(UUID id) { return sleeping.contains(id); }

    /** "" | "AFK" | "Спит" */
    public String status(UUID id) {
        if (sleeping.contains(id)) return "Спит";
        if (afk.contains(id)) return "AFK";
        return "";
    }

    /** Ручной /afk. Выйти из ручного AFK можно так же, как из обычного: подвигаться или нажать что угодно (через 2 секунды после включения). */
    public boolean toggleManual(UUID id) {
        if (manual.remove(id)) { leave(id, null); return false; }
        manual.add(id); afk.add(id); manualAt.put(id, System.currentTimeMillis());
        return true;
    }

    /** Любое действие игрока с клиента: движение мыши/клавиш, клик, смена слота, чат, команда… */
    private void touch(UUID id) {
        last.put(id, System.currentTimeMillis());
        if (!afk.contains(id)) return;
        if (manual.contains(id) && System.currentTimeMillis() - manualAt.getOrDefault(id, 0L) < 2000) return;   // сама команда /afk не считается
        manual.remove(id);
        leave(id, null);
    }

    private void leave(UUID id, Player known) {
        boolean was = afk.remove(id);
        last.put(id, System.currentTimeMillis());
        if (!was) return;
        Player p = known != null ? known : Bukkit.getPlayer(id);
        if (p != null && plugin.cfg("gameplay").getBoolean("afk.messages", true)) p.sendActionBar(net.kyori.adventure.text.Component.text("§aС возвращением! Режим AFK выключен"));
    }

    /** Из других плагинов (EssentialsX): значок включается и выключается вместе с их AFK. */
    void externalAfk(UUID id, boolean on) {
        if (on) { afk.add(id); } else { manual.remove(id); afk.remove(id); last.put(id, System.currentTimeMillis()); }
    }

    /** Раз в секунду, главный поток. */
    @Override
    public void run() {
        long limit = plugin.cfg("gameplay").getLong("afk.seconds", 120) * 1000L;
        boolean msg = plugin.cfg("gameplay").getBoolean("afk.messages", true);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (p.isSleeping()) sleeping.add(id); else sleeping.remove(id);
            last.putIfAbsent(id, now);                       // игроки, которые были на сервере до запуска плагина, тоже должны становиться AFK
            if (!afk.contains(id) && now - last.get(id) > limit) {
                afk.add(id);
                if (msg) p.sendActionBar(net.kyori.adventure.text.Component.text("§7Вы AFK — подвигайтесь или нажмите любую клавишу, чтобы вернуться"));
            }
        }
    }

    /** AFK не кикает: если кто-то (EssentialsX auto-afk-kick, vanilla idle-timeout) пытается выгнать за бездействие — отменяем, игрок просто остаётся AFK. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        if (!plugin.cfg("gameplay").getBoolean("afk.never-kick", true)) return;
        String why = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.reason()).toLowerCase();
        boolean idle = e.getCause() == PlayerKickEvent.Cause.IDLING
                || why.contains("idl") || why.contains("afk") || why.contains("inactiv") || why.contains("бездейств") || why.contains("афк") || why.contains("неактив");
        if (!idle) return;
        e.setCancelled(true);
        afk.add(e.getPlayer().getUniqueId());
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { last.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); afk.remove(e.getPlayer().getUniqueId()); manual.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        last.remove(id); afk.remove(id); manual.remove(id); manualAt.remove(id); sleeping.remove(id);
    }

    // ── что считается «игрок жив» ──
    // Сдвиг на месте от воды, поршней, отбрасывания и т. п. НЕ считается: иначе AFK-фермы у воды никогда не были бы AFK.
    // Считаются только действия самого игрока: поворот мыши, нажатие клавиш движения/прыжка/приседания, клики, слоты, предметы, чат, команды.
    @EventHandler public void onMove(PlayerMoveEvent e) {
        var f = e.getFrom(); var t = e.getTo();
        if (t == null) return;
        boolean turned = Math.abs(f.getYaw() - t.getYaw()) > 0.5f || Math.abs(f.getPitch() - t.getPitch()) > 0.5f;
        if (turned) touch(e.getPlayer().getUniqueId());
    }
    @EventHandler public void onInput(PlayerInputEvent e) {
        var in = e.getInput();
        if (in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump() || in.isSneak() || in.isSprint()) touch(e.getPlayer().getUniqueId());
    }
    @EventHandler public void onSneak(PlayerToggleSneakEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onSprint(PlayerToggleSprintEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onSwing(PlayerAnimationEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onUse(PlayerInteractEntityEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage().toLowerCase();
        if (!(m.equals("/afk") || m.startsWith("/afk "))) touch(e.getPlayer().getUniqueId());
    }
    @EventHandler public void onInteract(PlayerInteractEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onBreak(BlockBreakEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onClick(InventoryClickEvent e) { touch(e.getWhoClicked().getUniqueId()); }
    @EventHandler public void onInvClose(org.bukkit.event.inventory.InventoryCloseEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler public void onTab(PlayerBedLeaveEvent e) { touch(e.getPlayer().getUniqueId()); }

    /** EssentialsX: если у них игрок ушёл в AFK (/afk или авто) — значок тоже; вернулся — значок исчезает. Через отражение: Essentials может быть не установлен. */
    @SuppressWarnings("unchecked")
    public void hookEssentials() {
        try {
            Class<? extends org.bukkit.event.Event> ev = (Class<? extends org.bukkit.event.Event>) Class.forName("net.ess3.api.events.AfkStatusChangeEvent");
            var getAffected = ev.getMethod("getAffected");
            var getValue = ev.getMethod("getValue");
            Bukkit.getPluginManager().registerEvent(ev, this, EventPriority.MONITOR, (l, event) -> {
                try {
                    if (!ev.isInstance(event)) return;
                    Object user = getAffected.invoke(event);
                    boolean on = (boolean) getValue.invoke(event);
                    Object base = user.getClass().getMethod("getBase").invoke(user);
                    if (base instanceof Player p) externalAfk(p.getUniqueId(), on);
                } catch (Throwable ignored) {}
            }, plugin, true);
            plugin.getLogger().info("AFK: подключён EssentialsX (значок Zzz показывается и при их AFK)");
        } catch (ClassNotFoundException ignored) {
            // Essentials не установлен — ничего страшного
        } catch (Throwable t) {
            plugin.getLogger().warning("AFK: не удалось подключить EssentialsX: " + t);
        }
    }
}
