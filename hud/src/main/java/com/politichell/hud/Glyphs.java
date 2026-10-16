package com.politichell.hud;

/** Символы иконок из ресурспака (шрифт minecraft:default). Должны совпадать с tools/build_pack.py. */
public final class Glyphs {
    private Glyphs() {}
    public static final String VOICE_ON = "\uE300";
    public static final String VOICE_OFF = "\uE301";
    public static final String FLAG_NONE = "\uE302";
    public static final String REL_ALLY = "\uE320";
    public static final String REL_ENEMY = "\uE321";
    public static final String REL_NEUTRAL = "\uE322";
    public static final String REL_FRIEND = "\uE323";
    public static final String ZZZ = "\uE330";
    public static final String CARD = "\uE390";           // плашка под ником (карточка игрока)
    public static final int CARD_W = 112;                 // ширина плашки в GUI-px (см. build_pack.py: CARD_W)
    public static final String LOGO_TAB = "\uE391";       // логотип для шапки таба (картинка source_art/logo.png)
    // плашка-фон под ником: кусочки mid16 / mid4 / mid1 + скошенный край cap (см. build_pack.py: PLATE_GL)
    private static final String P16 = "\uE392", P4 = "\uE393", P1 = "\uE394", PCAP = "\uE395";

    /** Плашка шириной ровно n px (n ≥ 7); после неё курсор стоит на n px правее начала. */
    public static String plate(int n) {
        if (n < 7) n = 7;
        int rest = n - 6;
        String back = CompassManager.spaces(-1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rest / 16; i++) sb.append(P16).append(back);
        rest %= 16;
        for (int i = 0; i < rest / 4; i++) sb.append(P4).append(back);
        for (int i = 0; i < rest % 4; i++) sb.append(P1).append(back);
        sb.append(PCAP).append(back);
        return sb.toString();
    }

    /** Фон под текст шириной contentW px: плашка на 3 px шире с каждой стороны, затем курсор возвращается к началу текста. Суммарный сдвиг = 0. */
    public static String bg(int contentW) {
        int n = contentW + 6;
        return "§f" + CompassManager.spaces(-3) + plate(n) + CompassManager.spaces(-n) + CompassManager.spaces(3);
    }

    public static final String SUN = "\uE310";
    public static final String MOON = "\uE311";
    public static final String RAIN = "\uE312";
    public static final String STORM = "\uE313";
    public static final String SNOW = "\uE314";
    public static final String CLOUD_SUN = "\uE315";
    public static final String FOG = "\uE316";
    public static final String CLOCK = "\uE317";
    public static final String WIND = "\uE318";
}
