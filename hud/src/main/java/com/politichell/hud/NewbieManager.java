package com.politichell.hud;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Новичок = наиграно меньше newbie.hours часов (статистика игрока). Голубая точка у всех зрителей.
 * Опционально (newbie.protect-pvp) — защита: новичка нельзя бить, и он сам не бьёт игроков.
 * Для других плагинов: PoliticHellHud.newbies().isNewbie(uuid) — можно запретить новичкам войну/захваты/осады.
 */
public class NewbieManager implements Runnable, Listener {
    private final PoliticHellHud plugin;
    private final Map<UUID, Long> leftMs = new ConcurrentHashMap<>();   // сколько осталось, мс (только новички)

    public NewbieManager(PoliticHellHud plugin) { this.plugin = plugin; }

    public boolean isNewbie(UUID id) { return leftMs.containsKey(id); }

    /** "2ч 15м" или "" */
    public String left(UUID id) {
        Long ms = leftMs.get(id);
        if (ms == null) return "";
        long min = Math.max(1, ms / 60000);
        return min >= 60 ? (min / 60) + "ч " + (min % 60) + "м" : min + "м";
    }

    /** Раз в секунду, главный поток (статистику безопасно читать только тут). */
    @Override
    @SuppressWarnings("deprecation")
    public void run() {
        double hours = plugin.cfg("gameplay").getDouble("newbie.hours", 3);
        if (hours <= 0) { leftMs.clear(); return; }
        long limitTicks = (long) (hours * 72000);
        for (Player p : Bukkit.getOnlinePlayers()) {
            long played = p.getStatistic(Statistic.PLAY_ONE_MINUTE);      // тики игрового времени
            if (played < limitTicks) leftMs.put(p.getUniqueId(), (limitTicks - played) * 50L);
            else leftMs.remove(p.getUniqueId());
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { leftMs.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!plugin.cfg("gameplay").getBoolean("newbie.protect-pvp", false)) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) d = shooter;
        if (!(d instanceof Player attacker) || attacker.equals(victim)) return;
        if (isNewbie(victim.getUniqueId())) {
            e.setCancelled(true);
            attacker.sendMessage("§bЭтот игрок — новичок, под защитой ещё " + left(victim.getUniqueId()));
        } else if (isNewbie(attacker.getUniqueId())) {
            e.setCancelled(true);
            attacker.sendMessage("§bНовички не участвуют в PvP ещё " + left(attacker.getUniqueId()));
        }
    }
}
