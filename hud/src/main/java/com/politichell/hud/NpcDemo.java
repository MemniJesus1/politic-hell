package com.politichell.hud;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Тестовые НПС для просмотра дизайна ников ("этап B": многострочный ник из текстового дисплея, виден сквозь стены).
 * Это только ПРЕВЬЮ дизайна: НПС = Mannequin (ванильная кукла 1.21.9+), над ним TextDisplay со стопкой строк.
 * Снизу вверх:  имя-строка [точка][флаг] Ник [Zzz][голос]  /  инфо (страна · статус)  /  привилегия.
 * НПС не сохраняются между перезапусками. Убрать всех:  /hudnpc clear
 */
public class NpcDemo {
    public static final String TAG = "phhud_npc";

    public enum Rel { SAME, ALLY, UNION, NEWBIE, ENEMY, NEUTRAL }

    private final PoliticHellHud plugin;

    public NpcDemo(PoliticHellHud plugin) { this.plugin = plugin; }

    private static String dot(Rel r) {
        return switch (r) {
            case SAME -> "§f" + Glyphs.REL_ALLY;
            case ALLY, UNION, NEWBIE -> "§f" + Glyphs.REL_FRIEND;
            case ENEMY -> "§f" + Glyphs.REL_ENEMY;
            case NEUTRAL -> "§f" + Glyphs.REL_NEUTRAL;
        };
    }

    /** Над НПС (снизу вверх): строка ника «● [ранг] Ник [Zzz][голос]», над ней — «[флаг] Страна» (если страна есть). Слов «союзник/враг» нет — только цвет точки. */
    public void spawn(Location where, String name, String countryName, String flagId, Rel rel, boolean afk, boolean voice, String rankId) {
        World w = where.getWorld();
        Mannequin m = w.spawn(where, Mannequin.class, e -> {
            e.setImmovable(true);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setPersistent(false);
            e.setCustomNameVisible(false);
            e.setDescription(null);          // убираем надпись "NPC" под именем
            e.addScoreboardTag(TAG);
            try { e.setProfile(ResolvableProfile.resolvableProfile().name("Steve").build()); } catch (Throwable ignored) {}
        });

        Countries.Country c = plugin.countries().get(flagId);
        RankManager.Rank rk = rankId != null ? plugin.ranks().byId(rankId) : null;
        if (rk == null) rk = plugin.ranks().byId("player");

        String nick = plugin.cfg("gameplay").getString("nametag.name-color", "&7").replace('&', '§') + name;   // ник серый
        String body = dot(rel) + " §f" + (rk != null ? rk.icon() : "") + " " + nick
                + (afk ? " §f" + Glyphs.ZZZ : "") + " §f" + (voice ? Glyphs.VOICE_ON : Glyphs.VOICE_OFF);
        String country = (countryName != null && !countryName.isBlank() || c != null)
                ? " §f" + (c != null ? c.glyph() + " " : "") + "§7" + (countryName != null ? countryName : "") : "";
        String full = body + country;
        List<String> lines = new ArrayList<>();
        lines.add(Glyphs.bg(TextWidth.of(full)) + full);       // всё в одну строку на плашке — как ник над игроком

        double y = plugin.cfg("gameplay").getDouble("npc.y-offset", 2.05);
        Location tl = where.clone().add(0, y, 0);
        w.spawn(tl, TextDisplay.class, t -> {
            t.text(LegacyComponentSerializer.legacySection().deserialize(String.join("\n", lines)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(true);               // виден сквозь стены
            t.setShadowed(true);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setLineWidth(400);
            t.setPersistent(false);
            t.addScoreboardTag(TAG);
        });
    }

    /** Шесть НПС в ряд: свой / союзник / новичок / враг / нейтрал / союз наций — у каждого своя страна с придуманным названием, ранг, часть без флага. */
    public void demo(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0).normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        Location base = p.getLocation().clone().add(dir.clone().multiply(5));
        base.setY(p.getLocation().getY());
        List<String> ids = new ArrayList<>(plugin.countries().ids());
        String myName = plugin.countryName(p.getUniqueId()), myFlag = plugin.players().getFlag(p.getUniqueId());   // как у тебя: нация Towny и флаг, если выбран
        Object[][] rows = {
                // ник, название страны, флаг (null = без флага), отношение, AFK, голос, ранг
                // ники обычные (по ним не видно, кто кто) — отношение показывает ТОЛЬКО цветная точка. Порядок: свой, союзник, новичок, враг, нейтрал, союз
                {"Alex_01", myName, myFlag, Rel.SAME, false, true, "vip"},
                {"Mira", "Империя Бублика", pick(ids, "esp", 1), Rel.ALLY, false, true, "player"},
                {"Tolik", "Новая Атлантида", null, Rel.NEWBIE, false, false, "player"},
                {"Bob_Kill", "Орден Утят", pick(ids, "fra", 3), Rel.ENEMY, false, true, "moderator"},
                {"Dasha", null, null, Rel.NEUTRAL, true, true, "player"},
                {"Ivan", "Союз Трёх Морей", pick(ids, "lux", 5), Rel.UNION, false, false, "premium"},
        };
        for (int i = 0; i < rows.length; i++) {
            double off = (i - (rows.length - 1) / 2.0) * 1.7;
            Location l = base.clone().add(side.clone().multiply(off));
            l.setDirection(dir.clone().multiply(-1));
            Object[] r = rows[i];
            spawn(l, (String) r[0], (String) r[1], (String) r[2], (Rel) r[3], (Boolean) r[4], (Boolean) r[5], (String) r[6]);
        }
    }

    private String pick(List<String> ids, String want, int idx) {
        if (ids.contains(want)) return want;
        return ids.isEmpty() ? "" : ids.get(idx % ids.size());
    }

    public int clear() {
        int n = 0;
        for (World w : plugin.getServer().getWorlds())
            for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG)) { e.remove(); n++; }
        return n;
    }
}
