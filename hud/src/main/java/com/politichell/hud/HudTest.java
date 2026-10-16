package com.politichell.hud;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** /hudtest — показывает в чате все картинки пака. Квадратики ▯ вместо картинок = включён НЕ тот ресурспак (или не обновился). */
public final class HudTest {
    private HudTest() {}

    public static void run(PoliticHellHud pl, CommandSender s) {
        s.sendMessage("§6══ Проверка ресурспака PoliticHellHUD v" + pl.getDescription().getVersion() + " ══");
        s.sendMessage("§7Должны быть КАРТИНКИ. Если видишь пустые квадратики ▯ — включён не тот пак: нужен §fPoliticHellHUD-v1.8-MINI§7.");
        s.sendMessage("§fСкорборд/иконки:  §f" + g("\uE350 \uE351 \uE352 \uE353 \uE354 \uE355 \uE356 \uE357"));
        StringBuilder rk = new StringBuilder();
        for (var r : pl.ranks().all()) rk.append(r.icon()).append(' ');
        s.sendMessage("§fРанги (донат):    §f" + rk);
        s.sendMessage("§fГолос:            §f" + Glyphs.VOICE_ON + "  " + Glyphs.VOICE_OFF + "   §7(обычный = голос есть, перечёркнутый = нет)");
        s.sendMessage("§fОтношения:        §f" + Glyphs.REL_ALLY + " " + Glyphs.REL_FRIEND + " " + Glyphs.REL_ENEMY + " " + Glyphs.REL_NEUTRAL + "   §7зелёный / голубой / красный / серый     §fAFK: §f" + Glyphs.ZZZ);
        s.sendMessage("§fПогода:           §f" + g("\uE310 \uE311 \uE312 \uE313 \uE314 \uE315 \uE316 \uE317 \uE318"));
        StringBuilder fl = new StringBuilder();
        int n = 0;
        for (var c : pl.countries().all()) { if (n++ >= 10) break; fl.append(c.glyph()).append(' '); }
        s.sendMessage("§fФлаги:            §f" + fl);

        // компас — шрифт politichell:compass; по строке на метку: 7 уровней прозрачности слева направо
        s.sendMessage("§fКомпас (7 уровней прозрачности: чёткий → растворяется):");
        for (String name : new String[]{"card_N", "card_E", "tick_m", "poi_spawn", "poi_wp"}) {
            Component row = Component.text("   ");
            for (int lv = 0; lv < 7; lv++) {
                String ch = pl.compass().sample(name, lv);
                row = row.append(Component.text(ch + "\uF005").font(Key.key("politichell", "compass")).color(NamedTextColor.WHITE));
            }
            row = row.append(Component.text("  " + name, NamedTextColor.DARK_GRAY));
            s.sendMessage(row);
        }
        s.sendMessage("§7Если иконки есть, а у компаса квадраты ▯ — пак старый (до v1.5). Если всё видно, а вверху экрана компаса нет: §f/compass on");
    }

    private static String g(String spaced) { return spaced; }
}
