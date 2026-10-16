package com.politichell.hud.map;

import com.politichell.hud.CompassManager.Poi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import su.nezushin.nminimap.NMinimap;
import su.nezushin.nminimap.api.events.AsyncFrameRenderEvent;
import su.nezushin.nminimap.api.events.AsyncMarkerRenderEvent;
import su.nezushin.nminimap.frames.FrameLayer;
import su.nezushin.nminimap.player.NMapPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Единственное место, где мы трогаем классы NMinimap. Загружается только если плагин установлен. */
final class NmBridge implements Listener {
    private final MapManager mgr;

    NmBridge(MapManager mgr) {
        this.mgr = mgr;
        NMinimap.getInstance();                  // упадёт сразу, если плагина нет / он выключен
    }

    boolean supportsFullscreen() {
        try { NMapPlayer.class.getMethod("setFullscreen", boolean.class); return true; } catch (Throwable t) { return false; }
    }

    private NMapPlayer find(Player p) {
        for (NMapPlayer np : NMinimap.getInstance().getPlayersWithMap()) {
            Player q = np.getPlayer();
            if (q != null && q.getUniqueId().equals(p.getUniqueId())) return np;
        }
        return null;
    }

    /** @return null — карта открыта; иначе причина отказа. */
    String open(Player p, int defaultScale) {
        NMapPlayer np = find(p);
        if (np == null) return "Карта ещё загружается — подожди пару секунд и нажми F снова.";
        if (np.isBedrockPlayer()) return "Карта не работает на Bedrock-клиенте.";
        if (!su.nezushin.nminimap.util.DisallowedWorldsUtil.isAllowed(p.getWorld())) return "В этом мире карта отключена.";
        np.setFullScale(defaultScale);
        np.setFullscreen(true);
        return null;
    }

    void close(Player p) { NMapPlayer np = find(p); if (np != null) np.setFullscreen(false); }
    int scale(Player p) { NMapPlayer np = find(p); return np == null ? 4 : np.getFullScale(); }
    void setScale(Player p, int s) { NMapPlayer np = find(p); if (np != null) np.setFullScale(s); }
    void setView(Player p, double x, double z) { NMapPlayer np = find(p); if (np != null) np.setView(x, z); }
    boolean isFullscreen(Player p) { NMapPlayer np = find(p); return np != null && np.isFullscreen(); }
    boolean minimapOn(Player p) { NMapPlayer np = find(p); return np != null && np.isEnabled(); }

    /** Состояние мини-карты игрока (для меню). */
    record Mini(boolean on, boolean right, boolean round, String frame, boolean radar, int scale) {}

    Mini mini(Player p) {
        NMapPlayer np = find(p);
        if (np == null) return new Mini(false, false, false, null, false, 2);
        return new Mini(np.isEnabled(), np.isRight(), np.isRound(), np.getFrame(), np.isRadarEnabled(), np.getScale());
    }

    List<String> frameNames() {
        try {
            List<String> l = new ArrayList<>();
            for (String n : NMinimap.getInstance().getFrameManager().getFrameNames()) if (!n.startsWith("_")) l.add(n);   // «_nearby» — служебная рамка со слоями-цифрами
            return l;
        } catch (Throwable t) { return List.of(); }
    }

    String miniSetting(Player p, String what, String arg) {
        NMapPlayer np = find(p);
        if (np == null) return "§cКарта ещё загружается.";
        switch (what) {
            case "mini" -> {
                boolean on = arg.isEmpty() ? !np.isEnabled() : arg.equals("on");
                np.setEnabled(on);
                return on ? "§aМини-карта включена" : "§7Мини-карта выключена. Полная карта (F) работает как раньше.";
            }
            case "radar" -> {
                boolean on = arg.isEmpty() ? !np.isRadarEnabled() : arg.equals("on");
                np.setRadarEnabled(on);
                return on ? "§aРадар мобов и игроков включён" : "§7Радар выключен";
            }
            case "frame" -> {
                var fm = NMinimap.getInstance().getFrameManager();
                if (arg.isEmpty()) {
                    String cur = np.getFrame() == null ? "нет" : np.getFrame();
                    return "§bРамка мини-карты: §f" + cur + " §7— доступны: §f" + String.join(", ", frameNames()) + ", none §7(/map frame <название>)";
                }
                if (arg.equals("none") || arg.equals("off")) { np.setFrame(null); return "§7Рамка мини-карты убрана"; }
                var fr = fm.getFrame(arg);
                if (fr == null || fr.name().startsWith("_") || (fr.usePermission() && !p.hasPermission("nminimap.frame." + fr.name())))
                    return "§cНет такой рамки. Доступны: §f" + String.join(", ", frameNames()) + ", none";
                np.setFrame(fr.name());
                return "§aРамка мини-карты: §f" + fr.name();
            }
            case "side" -> {
                if (!arg.equals("left") && !arg.equals("right")) return "§e/map side left|right";
                np.setRight(arg.equals("right"));
                return "§aМини-карта: " + (arg.equals("right") ? "справа" : "слева");
            }
            case "style" -> {
                if (!arg.equals("round") && !arg.equals("square")) return "§e/map style round|square";
                np.setRound(arg.equals("round"));
                return "§aМини-карта: " + (arg.equals("round") ? "круглая" : "квадратная");
            }
            case "zoom" -> {
                int z;
                try { z = Integer.parseInt(arg); } catch (NumberFormatException e) { return "§e/map zoom 1|2|4|8"; }
                if (z != 1 && z != 2 && z != 4 && z != 8) return "§e/map zoom 1|2|4|8";
                np.setScale(z);
                return "§aМасштаб мини-карты: §f1:" + z;
            }
            default -> { return "§cНеизвестно"; }
        }
    }

    /** Значки точек: на мини-карте и на полной. Вызывается из асинхронного потока NMinimap. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMarkers(AsyncMarkerRenderEvent e) {
        Player p = e.getPlayer().getPlayer();
        if (p == null) return;
        List<Poi> pts = mgr.markersFor(p);
        for (Poi o : pts) e.getMarkers().add(new PoiMarker("poi_" + o.icon(), o.x(), o.z()));
    }

    /**
     * Слот «игроков рядом» в рамках nova/frost/crimson (квадратная карта): к рамке игрока добавляются слои-цифры.
     * «-» — никого, 1…99 — число игроков в радиусе (map.yml → nearby). Цвет цифр: 1–3 белые, 4–9 янтарные, 10+ красные.
     * Вызывается из асинхронного потока NMinimap; число считает MapManager раз в секунду.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onFrame(AsyncFrameRenderEvent e) {
        if (!mgr.nearbyOn()) return;
        NMapPlayer np = e.getPlayer();
        if (np == null || np.isRound()) return;
        Player p = np.getPlayer();
        String fr = np.getFrame();
        if (p == null || fr == null || !mgr.nearbyFrame(fr)) return;
        int n = mgr.nearbyOf(p);
        List<String> names = new ArrayList<>();
        if (n <= 0) names.add("ph_nb_dash");
        else if (n < 10) names.add("ph_nb_a" + n + "_" + (n <= 3 ? "w" : "a"));
        else { names.add("ph_nb_a" + (n / 10) + "_r"); names.add("ph_nb_b" + (n % 10) + "_r"); }
        var fm = NMinimap.getInstance().getFrameManager();
        for (String name : names) {
            List<FrameLayer> v = fm.getLayers(name);
            if (v.isEmpty()) continue;
            FrameLayer l = v.get(0).copy();
            l.setZIndex(200);
            e.getLayers().add(l);
        }
    }
}
