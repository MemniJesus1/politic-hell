package com.politichell.hud;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Погода и игровое время для каждого игрока. Считается в главном потоке раз в секунду, плейсхолдеры читают кэш. */
public class InfoCache implements Runnable {
    public record Info(String icon, String weather, String time) {}

    private final Map<UUID, Info> cache = new ConcurrentHashMap<>();

    public Info get(UUID id) { return cache.getOrDefault(id, new Info(Glyphs.SUN, "—", "--:--")); }
    public void forget(UUID id) { cache.remove(id); }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            long t = w.getTime();
            boolean day = t < 12300 || t > 23850;
            String icon, name;
            if (w.isThundering()) { icon = Glyphs.STORM; name = "Гроза"; }
            else if (w.hasStorm()) {
                boolean cold = p.getLocation().getBlock().getTemperature() < 0.15;
                icon = cold ? Glyphs.SNOW : Glyphs.RAIN; name = cold ? "Снег" : "Дождь";
            } else if (t > 23000 || t < 1000) { icon = Glyphs.CLOUD_SUN; name = "Рассвет"; }
            else if (t > 11800 && t < 13800) { icon = Glyphs.CLOUD_SUN; name = "Закат"; }
            else if (day) { icon = Glyphs.SUN; name = "Ясно"; }
            else { icon = Glyphs.MOON; name = "Ночь"; }
            int minutes = (int) (((t + 6000) % 24000) * 60 / 1000);
            String time = String.format("%02d:%02d", minutes / 60, minutes % 60);
            cache.put(p.getUniqueId(), new Info(icon, name, time));
        }
    }
}
