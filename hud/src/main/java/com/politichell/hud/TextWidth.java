package com.politichell.hud;

/** Приблизительная ширина текста обычным шрифтом Minecraft в GUI-пикселях (для выравнивания «карточки» на плашке). Ошибка ±1 px на символ — не страшно. */
public final class TextWidth {
    private TextWidth() {}

    private static final java.util.Map<Character, Integer> GLYPHS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Точные ширины картинок-символов пака — из glyph_widths.yml (его создаёт build_pack.py). */
    public static void load(PoliticHellHud plugin) {
        try (var in = plugin.getResource("glyph_widths.yml")) {
            if (in == null) return;
            var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String k : y.getKeys(false)) GLYPHS.put((char) Integer.parseInt(k, 16), y.getInt(k));
        } catch (Exception e) { plugin.getLogger().warning("glyph_widths.yml: " + e); }
    }

    /** Точные ширины ванильного шрифта (ascii / accented / nonlatin_european из клиента 1.21.11): файл mc_font_widths.txt «КОД:advance». */
    private static final java.util.Map<Character, Integer> MC = new java.util.HashMap<>();
    static {
        try (var in = TextWidth.class.getResourceAsStream("/mc_font_widths.txt")) {
            if (in != null) for (String l : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                int i = l.indexOf(':');
                if (i > 0) MC.put((char) Integer.parseInt(l.substring(0, i), 16), Integer.parseInt(l.substring(i + 1).trim()));
            }
        } catch (Exception ignored) {}
    }

    /** Ширина одного символа вместе с зазором (advance). Символы приватной зоны (значки пака) берутся из glyph_widths.yml. */
    public static int adv(char c) {
        Integer g = GLYPHS.get(c);
        if (g != null) return g;
        if (c >= '\uF001' && c <= '\uF100') return c - 0xF000;          // пробелы со смещением пака
        if (c >= '\uF201' && c <= '\uF300') return -(c - 0xF200);
        if (c >= '\uE000' && c <= '\uF8FF') return c >= '\uE400' ? 14 : c == '\uE300' || c == '\uE301' ? 10 : 8;
        Integer m = MC.get(c);
        if (m != null) return m;
        if (c == ' ') return 4;
        return c >= '\u2E80' ? 9 : 6;                                      // остальное — unifont; ±1–2 px
    }

    public static int of(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }   // цветовые коды ширины не имеют
            w += adv(c);
        }
        return w;
    }

    /** Ширина строки с кодами «&» / «§»: учитывает жирный (&l: +1 px на символ), коды цвета/сброса его снимают. */
    public static int ofLegacy(String s, boolean boldBase) {
        int w = 0; boolean bold = boldBase;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                char k = Character.toLowerCase(s.charAt(i + 1));
                if (k == 'x' && i + 13 < s.length()) { bold = boldBase; i += 13; continue; }
                if (k == 'l') { bold = true; i++; continue; }
                if ("0123456789abcdefr".indexOf(k) >= 0) { bold = boldBase; i++; continue; }
                if ("kmno".indexOf(k) >= 0) { i++; continue; }
            }
            w += adv(c) + (bold && adv(c) > 0 ? 1 : 0);
        }
        return w;
    }
}
