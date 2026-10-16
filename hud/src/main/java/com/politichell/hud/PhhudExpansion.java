package com.politichell.hud;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Плейсхолдеры PoliticHellHUD (для TAB, DeluxeMenus и др.):
 *
 *  Деньги:   %phhud_coins% %phhud_coins_formatted% %phhud_coins_short%   (то же для donate)
 *  Страна:   %phhud_flag% (флаг, ПУСТО если игрок его не выбрал)  %phhud_flag_pad% (флаг или пустое место той же ширины)
 *            %phhud_flag_or_map% (флаг или иконка карты)   %phhud_country% (название, любой текст)   %phhud_country_id% (ключ, для сортировки)
 *            %phhud_nation_tag[_N]% (" [флаг] Название" для ника, пусто если страны нет)
 *  Ник:      %phhud_name% (то же, что %player_name%, но работает без расширения Player)
 *  Ранг:     %phhud_rank% (значок привилегии, по умолчанию «Игрок»)  _label  _color  _id  _order (00 = старший)
 *  Голос:    %phhud_voice% (зелёные/серые полоски)   %phhud_voice_text%
 *  Мир:      %phhud_weather_icon%  %phhud_weather%  %phhud_time%  %phhud_clock_icon%
 *  Статус:   %phhud_afk% (значок Zzz, если AFK или спит; иначе пусто)  %phhud_status% ("", AFK, Спит)
 *            %phhud_name_color% (§7 если AFK/спит, иначе §f)
 *  Отношения (РЕЛЯЦИОННЫЕ, зритель→цель):
 *            %rel_phhud_relation% (зелёная — своя страна, голубая — союзник/союз наций/новичок, красная — враг, серая — нейтрал; для себя пусто)
 *            %rel_phhud_relation_color% (§a / §c / §7)   — слов нет, только цвет/иконка
 *  Новичок:  %phhud_newbie% ("да" или пусто)  %phhud_newbie_left% (сколько осталось: "2ч 15м")
 *  ЗАГЛУШКИ под будущие плагины (пока "—"):  %phhud_town%  %phhud_nation%  %phhud_war%  %phhud_role%
 */
public class PhhudExpansion extends PlaceholderExpansion implements Relational {
    private final PoliticHellHud plugin;

    public PhhudExpansion(PoliticHellHud plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "phhud"; }
    @Override public @NotNull String getAuthor() { return "PoliticHell"; }
    @Override public @NotNull String getVersion() { return "1.2"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        UUID id = player.getUniqueId();
        String key = params.toLowerCase();

        // ── деньги ──
        String[] parts = key.split("_");
        Currency c = Currency.byId(parts[0]);
        if (c != null) {
            long v = PoliticHellHud.provider().get(id, c);
            String mode = parts.length > 1 ? parts[1] : "raw";
            return switch (mode) {
                case "formatted" -> HudRenderer.format(v, 1000);
                case "short" -> HudRenderer.format(v, 0);
                case "compact" -> HudRenderer.compact(v, 48, false);   // 3M142K — для скорборда/меню (обычный шрифт)
                default -> Long.toString(v);
            };
        }

        var tw = plugin.integrations() != null ? plugin.integrations().towny(id) : null;
        String town = tw != null ? tw.town() : "", nation = tw != null ? tw.nation() : "", role = tw != null ? tw.role() : "";
        String cn = plugin.countryName(id);          // название страны (любой текст) или null
        String fg = plugin.flagGlyph(id);            // глиф флага или null
        // ── плашки-фоны (картинка plate_name.png) ──
        //   %phhud_namebar_N%  — строка скорборда «[ранг] Ник» на плашке (N = макс. ширина строки в px, по умолчанию 104)
        //   %phhud_tabbg%      — фон под строку игрока в таб-листе (ставится В НАЧАЛО tabprefix)
        //   %phhud_tagbg%      — фон под ник над головой (в начало tagprefix)
        java.util.regex.Matcher nb = NAMEBAR.matcher(key);
        if (nb.matches()) {
            int max = nb.group(1) == null ? 104 : Integer.parseInt(nb.group(1));
            String rk = plugin.ranks().get(id).icon();
            String nm = fit(player.getName() == null ? "" : player.getName(), max - TextWidth.of(rk) - 4);
            return Glyphs.bg(TextWidth.of(rk) + 4 + TextWidth.of(nm)) + "§f" + rk + " " + nameColor() + nm;
        }
        if (key.equals("tabbg") || key.equals("tagbg")) {
            String nm = player.getName() == null ? "" : player.getName();
            boolean afkOn = plugin.afk().isAfk(id) || plugin.afk().isSleeping(id);
            int w = TextWidth.of(plugin.ranks().get(id).icon()) + 4 + TextWidth.of(nm)
                    + (afkOn ? TextWidth.of(Glyphs.ZZZ) : 0) + 4 + TextWidth.of(plugin.voice().hasVoice(id) ? Glyphs.VOICE_ON : Glyphs.VOICE_OFF);
            if (key.equals("tabbg")) w += TextWidth.of(fg != null ? fg : plugin.countries().noneGlyph()) + 4;
            else {
                w += TextWidth.of(Glyphs.REL_NEUTRAL);                                             // точка отношения (у себя её нет — запас 8 px)
                if (cn != null || fg != null) w += 4 + (fg != null ? TextWidth.of(fg) + 4 : 0) + TextWidth.of(cut(cn != null ? cn : "", 10));
            }
            return Glyphs.bg(w);
        }
        // «Для ников»: %phhud_nation_tag% / %phhud_nation_tag_10% -> " [флаг] Название" либо пусто, если страны нет
        java.util.regex.Matcher tm = TAG.matcher(key);
        if (tm.matches()) {
            if (cn == null && fg == null) return "";
            int n = tm.group(1) == null ? 12 : Integer.parseInt(tm.group(1));
            return " §f" + (fg != null ? fg + " " : "") + "§7" + (cn != null ? cut(cn, n) : "");
        }
        // «короткие» значения для узких строк: %phhud_country_short% (11 символов) или %phhud_country_short_8% (8 символов)
        java.util.regex.Matcher sm = SHORT.matcher(key);
        if (sm.matches()) {
            int n = sm.group(2) == null ? 11 : Integer.parseInt(sm.group(2));
            String v = switch (sm.group(1)) {
                case "country" -> cn != null ? cn : "—";
                case "name" -> player.getName() == null ? "" : player.getName();
                case "town" -> dash(town);
                case "nation" -> dash(nation);
                case "role" -> dash(role);
                default -> war(tw);
            };
            return cut(v, n);
        }
        return switch (key) {
            case "name" -> player.getName() == null ? "" : player.getName();           // ник игрока (замена %player_name%, не требует расширения Player)
            case "flag" -> fg != null ? fg : "";                                      // нет флага — пусто
            case "flag_pad" -> fg != null ? fg : plugin.countries().noneGlyph();     // нет флага — пустое место такой же ширины (ровные ники в табе)
            case "flag_or_map" -> fg != null ? fg : "\uE350";                        // нет флага — иконка «карта»
            case "country" -> cn != null ? cn : "—";
            case "country_id" -> { String k = plugin.countryKey(id); yield k != null ? k : "zz"; }   // для сортировки
            case "rank" -> plugin.ranks().get(id).icon();                              // значок привилегии (по умолчанию «Игрок»)
            case "rank_label" -> plugin.ranks().get(id).label();
            case "rank_color" -> plugin.ranks().get(id).color().replace('&', '§');
            case "rank_id" -> plugin.ranks().get(id).id();
            case "rank_order" -> String.format("%02d", plugin.ranks().get(id).order());   // 00 — самый старший (для сортировки таба)
            case "card" -> {          // «карточка игрока»: полупрозрачная плашка + [ранг] Ник [голос] поверх неё. Для шапки таба, меню, скорборда
                String body = plugin.ranks().get(id).icon() + " " + nameColor() + player.getName() + " §f" + (plugin.voice().hasVoice(id) ? Glyphs.VOICE_ON : Glyphs.VOICE_OFF);
                int pad = 5, total = Glyphs.CARD_W + 1;
                int rest = total - pad - TextWidth.of(body);
                yield "§f" + Glyphs.CARD + CompassManager.spaces(-total + pad) + "§f" + body + (rest > 0 ? CompassManager.spaces(rest) : "");
            }
            case "voice" -> plugin.voice().hasVoice(id) ? Glyphs.VOICE_ON : Glyphs.VOICE_OFF;
            case "afk" -> plugin.afk().isAfk(id) || plugin.afk().isSleeping(id) ? Glyphs.ZZZ : "";
            case "sb" -> {            // стиль скорборда: full / compact — по нему TAB выбирает, какой показать
                String m = plugin.players().getSb(id);
                yield m != null ? m : plugin.cfg("hud").getString("scoreboard.default", "compact").toLowerCase(java.util.Locale.ROOT);
            }
            case "tabstyle" -> plugin.tabStyleOf(id);          // стиль шапки TAB: pillars / panel / min — по нему TAB выбирает design
            case "status" -> plugin.afk().status(id);
            case "name_color" -> nameColor();                      // ник всегда серый (отношение показывает точка)
            case "newbie" -> plugin.newbies().isNewbie(id) ? "да" : "";
            case "newbie_left" -> plugin.newbies().left(id);
            case "voice_text" -> plugin.voice().hasVoice(id) ? "вкл" : "выкл";
            case "weather_icon" -> plugin.info().get(id).icon();
            case "clock_icon" -> Glyphs.CLOCK;
            case "wind_icon" -> Glyphs.WIND;
            case "fog_icon" -> Glyphs.FOG;
            case "weather" -> plugin.info().get(id).weather();
            case "time" -> plugin.info().get(id).time();
            // будущие плагины:
            case "town" -> dash(town);
            case "nation" -> dash(nation);
            case "role" -> dash(role);
            case "war" -> war(tw);                  // SiegeWar: осада твоего города
            case "tbank" -> tw != null ? tw.bank() : "—";             // казна города
            case "nbank" -> tw != null ? tw.nationBank() : "—";       // казна нации
            case "residents" -> tw != null ? tw.residents() : "—";    // жители онлайн/всего
            case "plots" -> tw != null ? tw.plots() : "—";            // участки занято/максимум
            default -> null;
        };
    }

    private static String war(Integrations.Towny t) {
        if (t == null || t.town().isEmpty()) return "—";
        return t.sieged() ? "Осада!" : "Мир";
    }

    private static final java.util.regex.Pattern NAMEBAR = java.util.regex.Pattern.compile("namebar(?:_(\\d+))?");

    /** Цвет ника (gameplay.yml → nametag.name-color, по умолчанию серый). */
    private String nameColor() { return plugin.cfg("gameplay").getString("nametag.name-color", "&7").replace('&', '§'); }

    /** Обрезает текст так, чтобы он влез в maxPx пикселей. */
    private static String fit(String s, int maxPx) {
        while (s.length() > 1 && TextWidth.of(s) > maxPx) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("nation_tag(?:_(\\d+))?");
    private static final java.util.regex.Pattern SHORT = java.util.regex.Pattern.compile("(country|name|town|nation|role|war)_short(?:_(\\d+))?");

    private static String dash(String s) { return s == null || s.isBlank() ? "—" : s; }

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    /** Реляционные плейсхолдеры: one = кто смотрит, two = на кого смотрят. Использование: %rel_phhud_relation% */
    @Override
    public String onPlaceholderRequest(Player one, Player two, String identifier) {
        if (one == null || two == null) return "";
        Relations.Type t;
        if (one.getUniqueId().equals(two.getUniqueId())) t = Relations.Type.SELF;
        else if (plugin.newbies().isNewbie(two.getUniqueId())) t = Relations.Type.NEWBIE;   // новичок — голубой для всех
        else t = plugin.relations().between(plugin.countryKey(one.getUniqueId()), plugin.countryKey(two.getUniqueId()));
        return switch (identifier.toLowerCase()) {
            case "relation" -> switch (t) {
                case SAME -> Glyphs.REL_ALLY;
                case TREATY, UNION, NEWBIE -> Glyphs.REL_FRIEND;
                case ENEMY -> Glyphs.REL_ENEMY;
                case NEUTRAL -> Glyphs.REL_NEUTRAL;
                case SELF -> ""; };
            case "relation_color" -> switch (t) {
                case SAME -> "§a"; case TREATY, UNION, NEWBIE -> "§b"; case ENEMY -> "§c"; case NEUTRAL, SELF -> "§7"; };
            default -> null;
        };
    }
}
