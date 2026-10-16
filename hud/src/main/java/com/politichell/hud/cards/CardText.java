package com.politichell.hud.cards;

import com.politichell.hud.TextWidth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Рисует карточку предмета в одном из трёх видов (CardStyle): классика, широкая (две колонки), терминал (плашки-блоки).
 * Плашки — картинки-глифы пака (tools/cards_art.py), их ширина фиксирована: см. COL / GAP / TECH_W. Выравнивание — по ТОЧНЫМ ширинам шрифта Minecraft.
 */
public final class CardText {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final Map<String, String> ICONS = Map.of("weapon", "\ue3a0", "tool", "\ue3a1", "armor", "\ue3a2", "food", "\ue3a3", "block", "\ue3a4",
            "resource", "\ue3a5", "potion", "\ue3a6", "magic", "\ue3a7", "misc", "\ue3a8", "custom", "\ue3a9");
    private static final String DIVIDER = "\ue3b0";
    private static final Set<String> BOLD = Set.of("epic", "legendary", "mythic");

    /** Ширины плашек в паке (px). Менять можно только вместе с tools/cards_art.py → PLATES, затем пересобрать пак. */
    public static final int COL = 112, GAP = 4, FULL = COL * 2 + GAP, TECH_W = 156;
    private static final char GLASS_COL = '\ue3c0', GLASS_FULL = '\ue3c4', TECH = '\ue3c8';   // + 0 top, 1 mid, 2 bot, 3 solo

    private final CardConfig c;

    public CardText(CardConfig c) { this.c = c; }

    private record Row(Component c, int w) {}
    private record Block(String kind, String title, List<Row> rows) {}
    private record PRow(Row row, TextColor tint, int block) {}

    // ───────────── мелочи ─────────────
    private static Component noItalic(Component x) { return x.decoration(TextDecoration.ITALIC, false); }
    private static Component line(Component x) { return noItalic(Component.empty().append(x)); }

    static String spaces(int px) {
        if (px == 0) return "";
        StringBuilder sb = new StringBuilder();
        int left = Math.abs(px), base = px > 0 ? 0xF000 : 0xF200;
        while (left > 0) { int n = Math.min(256, left); sb.append((char) (base + n)); left -= n; }
        return sb.toString();
    }

    private static Component sp(int px) { return px == 0 ? Component.empty() : Component.text(spaces(px)); }

    private static int widthOf(String s) { return TextWidth.ofLegacy(s, false); }

    private static TextColor mix(TextColor a, TextColor b, float t) { return TextColor.lerp(t, a, b); }

    private static final TextColor WHITE = TextColor.fromHexString("#FFFFFF");

    // ───────────── градиент и название ─────────────
    public static Component gradient(String text, List<TextColor> stops, boolean bold) {
        int n = text.length();
        Component out = Component.empty();
        int segs = stops.size() - 1;
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            double t = n <= 1 ? 0 : (double) i / (n - 1);
            double pos = t * segs;
            int a = Math.min(segs - 1, (int) Math.floor(pos));
            TextColor col = TextColor.lerp((float) (pos - a), stops.get(a), stops.get(a + 1));
            Component cc = Component.text(String.valueOf(ch), col);
            if (bold) cc = cc.decorate(TextDecoration.BOLD);
            out = out.append(cc);
        }
        return noItalic(out);
    }

    public Component title(CardData d) { return title(d, CardStyle.CLASSIC, false, null); }

    public Component title(CardData d, CardStyle style, boolean full, String timer) {
        String ic = ICONS.getOrDefault(d.icon, ICONS.get("misc"));
        boolean bold = BOLD.contains(d.rarity.id());
        Component icon = Component.text(ic + " ", NamedTextColor.WHITE);
        Component name = gradient(d.title, d.rarity.stops(), bold);
        if (style == CardStyle.CLASSIC) return line(icon.append(name));
        // с плашкой: [иконка Название ……… Редкость]
        int plateW = style == CardStyle.TECH ? TECH_W : split(plateBlocks(d, full, timer, COL - 8, true)).single() ? COL : FULL;
        int nameW = 0;
        for (char ch : d.title.toCharArray()) nameW += TextWidth.adv(ch) + (bold ? 1 : 0);
        int iconW = TextWidth.adv(ic.charAt(0)) + TextWidth.adv(' ');
        int padL = style == CardStyle.TECH ? 7 : 4;
        int rw = widthOf(d.rarity.name());
        Component content = icon.append(name);
        int w = iconW + nameW;
        if (padL + w + 10 + rw + 4 <= plateW) {
            content = content.append(sp(plateW - padL - w - rw - 4)).append(Component.text(d.rarity.name(), d.rarity.accent()));
            w = plateW - padL - 4;
        }
        TextColor tint = mix(d.rarity.accent(), WHITE, 0.0f);
        return line(plated(style, plateW, 3, tint, new Row(content, w), padL));
    }

    // ───────────── плашки ─────────────
    private static char plateChar(CardStyle st, int plateW, int kind) {
        char base = st == CardStyle.TECH ? TECH : plateW == FULL ? GLASS_FULL : GLASS_COL;
        return (char) (base + kind);
    }

    /** Строка ровно plateW шириной: плашка, шаг назад, отступ, содержимое, добор. */
    private Component plated(CardStyle st, int plateW, int kind, TextColor tint, Row r, int padL) {
        Component plate = Component.text(String.valueOf(plateChar(st, plateW, kind))).color(tint).shadowColor(ShadowColor.none());
        int rest = Math.max(0, plateW - padL - r.w());
        return Component.empty().append(plate).append(sp(-(plateW + 1))).append(sp(padL)).append(r.c()).append(sp(rest));
    }

    // ───────────── перенос по ширине ─────────────
    public List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder cur = new StringBuilder();
            String carry = "";
            for (String w : para.split(" ")) {
                String trial = cur.length() == 0 ? w : cur + " " + w;
                if (cur.length() > 0 && widthOf(trial) > width) {
                    out.add(cur.toString());
                    carry = lastCodes(cur.toString(), carry);
                    cur = new StringBuilder(carry).append(w);
                } else cur = new StringBuilder(trial);
            }
            out.add(cur.toString());
        }
        return out;
    }

    /** Активные цветовые/форматные коды в конце строки — чтобы перенос не терял цвет. */
    private static String lastCodes(String s, String before) {
        StringBuilder act = new StringBuilder(before);
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '&') continue;
            char k = Character.toLowerCase(s.charAt(i + 1));
            if (k == 'r') act.setLength(0);
            else if ("0123456789abcdef".indexOf(k) >= 0) { act.setLength(0); act.append("&").append(k); }
            else if (k == 'x' && i + 13 < s.length()) { act.setLength(0); act.append(s, i, i + 14); i += 13; }
            else if ("lonmk".indexOf(k) >= 0) act.append("&").append(k);
        }
        return act.toString();
    }

    // ───────────── строки ─────────────
    private Row row(String legacy, TextColor base) {
        return new Row(Component.text("", base).append(LEGACY.deserialize(legacy)), widthOf(legacy));
    }

    private Row rowPlain(String text, TextColor col, boolean bold) {
        Component x = Component.text(text, col);
        if (bold) x = x.decorate(TextDecoration.BOLD);
        return new Row(x, TextWidth.ofLegacy(text, bold));
    }

    private List<Row> wrapRows(String legacy, TextColor base, int width) {
        List<Row> out = new ArrayList<>();
        for (String w : wrap(legacy, width)) out.add(row(w, base));
        return out;
    }

    /** Одна характеристика → строки. justify: «Подпись ········ значение» прижато к краям (плашки). */
    private List<Row> statRows(String s, int width, boolean justify) {
        List<Row> out = new ArrayList<>();
        int i = s.indexOf(':');
        if (justify && i > 0) {
            String lab = s.substring(0, i), val = s.substring(i + 1).stripLeading();
            String codes = lastCodes(lab, "");
            String vraw = val;
            int lw = widthOf(lab), vw = widthOf(codes + vraw);
            if (lw + vw + 6 <= width) {
                TextColor vc = vraw.startsWith("+") ? c.statPlus : vraw.startsWith("-") || vraw.startsWith("−") ? c.statMinus : c.statValue;
                Component comp = Component.text("", c.statValue).append(Component.text("", c.statLabel).append(LEGACY.deserialize(lab)))
                        .append(sp(width - lw - vw)).append(Component.text("", vc).append(LEGACY.deserialize(codes + vraw)));
                out.add(new Row(comp, width));
                return out;
            }
        }
        if (s.contains("&")) {
            boolean first = true;
            for (String w : wrap(s, width)) { out.add(row((first ? "" : "  ") + w, c.statValue)); first = false; }
            return out;
        }
        if (i < 0) { out.addAll(wrapRows(s, c.statValue, width)); return out; }
        String label = s.substring(0, i).trim(), val = s.substring(i + 1).trim();
        TextColor vc = val.startsWith("+") ? c.statPlus : val.startsWith("-") || val.startsWith("−") ? c.statMinus : c.statValue;
        out.add(new Row(Component.text(label + ": ", c.statLabel).append(LEGACY.deserialize(val).colorIfAbsent(vc)), widthOf(label + ": " + val)));
        return out;
    }

    private Row hintRow(boolean full) {
        String k = c.toggleShiftRight ? "Shift+ПКМ" : "/tip";
        return rowPlain(full ? k + " — кратко" : k + " — подробнее", c.hintColor, false);
    }

    // ───────────── блоки содержимого ─────────────
    private List<Row> descRows(CardData d, boolean full, int iw) {
        List<Row> body = new ArrayList<>();
        for (String s : d.desc) for (String w : wrap(s, iw)) body.add(row(w, c.descColor));
        return body;
    }

    private boolean cutDesc(CardData d, boolean full, int iw) {
        int n = 0;
        for (String s : d.desc) n += wrap(s, iw).size();
        return !full && n > c.compactLines;
    }

    /** Описание: кратко — compact-lines строк с «…», подробно — целиком. */
    private List<Row> descLimited(CardData d, boolean full, int iw) {
        List<String> lines = new ArrayList<>();
        for (String s : d.desc) lines.addAll(wrap(s, iw));
        boolean cut = !full && lines.size() > c.compactLines;
        int n = full ? lines.size() : Math.min(lines.size(), c.compactLines);
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(row(lines.get(i) + (cut && i == n - 1 ? "…" : ""), c.descColor));
        return out;
    }

    private List<Block> statBlocks(CardData d, int iw, boolean justify) {
        List<Block> out = new ArrayList<>();
        String title = "Характеристики", kind = "stats";
        List<Row> cur = new ArrayList<>();
        for (String s : d.stats) {
            if (s.startsWith("##")) {
                if (!cur.isEmpty()) out.add(new Block(kind, title, cur));
                cur = new ArrayList<>();
                title = s.substring(2);
                kind = title.startsWith("Зачарован") || title.startsWith("Книга") ? "ench" : "effects";
                continue;
            }
            cur.addAll(statRows(s, iw, justify && kind.equals("stats")));
        }
        if (!cur.isEmpty()) out.add(new Block(kind, title, cur));
        return out;
    }

    private List<Row> infoRows(CardData d, boolean full, String timer, int iw) {
        List<Row> out = new ArrayList<>();
        CardConfig.BindStyle bs = c.bindings.get(d.binding);
        if (d.binding != Binding.CONSUMABLE) out.add(rowPlain(bs.name(), bs.color(), true));
        if (d.bindOn != null && d.bindTo != null) {
            CardConfig.BindStyle tb = c.bindings.get(d.bindTo);
            out.addAll(wrapRows("Привяжется при " + CardManager.whenText(d.bindOn) + ": " + tb.name(), tb.color(), iw));
        }
        if (timer != null) {
            List<String> tw = wrap(timer, iw - 12);
            for (int i = 0; i < tw.size(); i++) out.add(rowPlain((i == 0 ? "⌛ " : "   ") + tw.get(i), c.bindings.get(Binding.TEMPORARY).color(), false));
        }
        if (full) {
            if (d.binding != Binding.CONSUMABLE && !bs.text().isBlank()) out.addAll(wrapRows(bs.text(), c.hintColor, iw));
            if (c.showOwner && d.owner != null) {
                String nm = org.bukkit.Bukkit.getOfflinePlayer(d.owner).getName();
                out.add(new Row(Component.text("Владелец: ", c.statLabel).append(Component.text(nm == null ? "—" : nm, c.statValue)), widthOf("Владелец: " + (nm == null ? "—" : nm))));
            }
        }
        return out;
    }

    private TextColor tint(String kind) {
        TextColor t = c.tints.get(kind);
        return t != null ? t : c.tints.getOrDefault("info", TextColor.fromHexString("#8A90AE"));
    }

    /** Блоки карточки для плашечных видов: описание, характеристики, зачарования, эффекты, привязка. */
    private List<Block> plateBlocks(CardData d, boolean full, String timer, int iw, boolean justify) {
        List<Block> bl = new ArrayList<>();
        List<Row> desc = descLimited(d, full, iw);
        if (!desc.isEmpty()) bl.add(new Block("desc", "Описание", desc));
        if (full) bl.addAll(statBlocks(d, iw, justify));
        else if (!d.brief.isEmpty()) {
            List<Row> br = new ArrayList<>();
            for (String b : d.brief) br.addAll(statRows(b, iw, justify));
            bl.add(new Block("stats", "Состояние", br));
        }
        List<Row> info = infoRows(d, full, timer, iw);
        if (!info.isEmpty()) bl.add(new Block("info", "Привязка", info));
        return bl;
    }

    private List<PRow> flatten(List<Block> blocks, int startId, TextColor headerLift) {
        List<PRow> out = new ArrayList<>();
        int id = startId;
        for (Block b : blocks) {
            TextColor t = tint(b.kind());
            out.add(new PRow(rowPlain(b.title().toUpperCase(Locale.ROOT), mix(t, WHITE, 0.55f), true), t, id));
            for (Row r : b.rows()) out.add(new PRow(r, t, id));
            id++;
        }
        return out;
    }

    private static int size(List<Block> bl) { int n = 0; for (Block b : bl) n += b.rows().size() + 1; return n; }

    private List<Component> render(CardStyle st, int plateW, int padL, List<PRow> rows) {
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PRow r = rows.get(i);
            boolean first = i == 0 || rows.get(i - 1).block() != r.block(), last = i == rows.size() - 1 || rows.get(i + 1).block() != r.block();
            int kind = first && last ? 3 : first ? 0 : last ? 2 : 1;
            out.add(line(plated(st, plateW, kind, r.tint(), r.row(), padL)));
        }
        return out;
    }

    private static void padTo(List<PRow> col, int n) {
        if (col.isEmpty()) return;
        PRow last = col.get(col.size() - 1);
        while (col.size() < n) col.add(new PRow(new Row(Component.empty(), 0), last.tint(), last.block()));
    }

    // ───────────── главный метод ─────────────
    /** Строки подсказки. timer — «Исчезнет через 59м» (или null). */
    public List<Component> lore(CardData d, boolean full, String timer) { return lore(d, full, timer, CardStyle.CLASSIC); }

    public List<Component> lore(CardData d, boolean full, String timer, CardStyle style) {
        return switch (style) {
            case CLASSIC -> classic(d, full, timer);
            case WIDE -> wide(d, full, timer);
            case TECH -> tech(d, full, timer);
        };
    }

    private record Split(List<Block> left, List<Block> right) { boolean single() { return left.isEmpty() || right.isEmpty(); } }

    /** Раскладка блоков по колонкам: описание — влево, характеристики — вправо, остальное — в ту колонку, что короче. */
    private Split split(List<Block> bl) {
        List<Block> left = new ArrayList<>(), right = new ArrayList<>();
        int hl = 0, hr = 0;
        for (Block b : bl) {
            boolean toLeft;
            if (left.isEmpty() && b.kind().equals("desc")) toLeft = true;
            else if (right.isEmpty()) toLeft = !left.isEmpty() ? false : true;
            else toLeft = hl <= hr;
            if (toLeft) { left.add(b); hl += b.rows().size() + 1; } else { right.add(b); hr += b.rows().size() + 1; }
        }
        return new Split(left, right);
    }

    private List<Component> tech(CardData d, boolean full, String timer) {
        List<Component> out = new ArrayList<>();
        List<Block> bl = plateBlocks(d, full, timer, TECH_W - 7 - 4, true);
        out.addAll(render(CardStyle.TECH, TECH_W, 7, flatten(bl, 0, null)));
        out.add(line(hintRow(full).c()));
        return out;
    }

    private List<Component> wide(CardData d, boolean full, String timer) {
        List<Component> out = new ArrayList<>();
        List<Block> bl = plateBlocks(d, full, timer, COL - 8, true);
        Split spl = split(bl);
        List<PRow> lp = flatten(spl.left(), 0, null), rp = flatten(spl.right(), 100, null);
        if (spl.single()) {
            out.addAll(render(CardStyle.WIDE, COL, 4, spl.left().isEmpty() ? rp : lp));
        } else {
            int n = Math.max(lp.size(), rp.size());
            padTo(lp, n); padTo(rp, n);
            List<Component> a = render(CardStyle.WIDE, COL, 4, lp), b = render(CardStyle.WIDE, COL, 4, rp);
            for (int i = 0; i < n; i++) out.add(line(Component.empty().append(a.get(i)).append(sp(GAP)).append(b.get(i))));
        }
        out.add(line(hintRow(full).c()));
        return out;
    }

    private Component text(String s, TextColor base) { return line(Component.text("", base).append(LEGACY.deserialize(s))); }

    private Component divider(Rarity r) {
        if (c.dividerGlyph) return line(Component.text(DIVIDER, r.accent()));
        return line(Component.text(" ".repeat(Math.max(8, c.width / 4)), NamedTextColor.DARK_GRAY).decorate(TextDecoration.STRIKETHROUGH));
    }

    private List<Component> classic(CardData d, boolean full, String timer) {
        List<Component> out = new ArrayList<>();
        CardConfig.BindStyle bs = c.bindings.get(d.binding);
        out.add(line(Component.text(bs.name(), bs.color()).append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(d.rarity.name(), d.rarity.accent()))));
        if (d.bindOn != null && d.bindTo != null) {
            CardConfig.BindStyle tb = c.bindings.get(d.bindTo);
            for (String w : wrap("Привяжется при " + CardManager.whenText(d.bindOn) + ": " + tb.name(), c.width + 20)) out.add(line(Component.text(w, tb.color())));
        }
        if (timer != null) out.add(line(Component.text("⌛ " + timer, c.bindings.get(Binding.TEMPORARY).color())));
        out.add(divider(d.rarity));
        for (Row r : descLimited(d, full, c.width)) out.add(line(r.c()));
        int sw = c.width + 30;
        if (!full) for (String b : d.brief) for (Row r : statRows(b, sw, false)) out.add(line(r.c()));
        boolean hasDetails = !d.stats.isEmpty() || d.binding != Binding.CONSUMABLE || d.owner != null;
        if (full) {
            if (hasDetails) {
                out.add(divider(d.rarity));
                boolean headed = false;
                for (String s : d.stats) {
                    if (s.startsWith("##")) {
                        out.add(Component.empty());
                        out.add(line(Component.text("◆ " + s.substring(2), d.rarity.accent()).decorate(TextDecoration.BOLD)));
                        headed = true;
                    } else {
                        if (!headed) { out.add(line(Component.text("◆ Характеристики", d.rarity.accent()).decorate(TextDecoration.BOLD))); headed = true; }
                        for (Row r : statRows(s, sw, false)) out.add(line(r.c()));
                    }
                }
                if (d.binding != Binding.CONSUMABLE && !bs.text().isBlank()) {
                    if (!d.stats.isEmpty()) out.add(Component.empty());
                    for (String s : wrap(bs.text(), c.width)) out.add(text(s, c.hintColor));
                }
                if (c.showOwner && d.owner != null) {
                    String nm = org.bukkit.Bukkit.getOfflinePlayer(d.owner).getName();
                    out.add(line(Component.text("Владелец: ", c.statLabel).append(Component.text(nm == null ? "—" : nm, c.statValue))));
                }
            }
            out.add(line(hintRow(true).c()));
        } else if (cutDesc(d, false, c.width) || hasDetails) {
            out.add(line(hintRow(false).c()));
        }
        return out;
    }
}
