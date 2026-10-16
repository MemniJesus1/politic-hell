package com.politichell.hud.cards;

import java.util.Locale;

/** Вид карточки предмета; игрок выбирает свой: /tip style  или меню /phh. */
public enum CardStyle {
    CLASSIC("classic", "Классика", "Одна колонка: название, перегородки, описание, характеристики. Строгая и компактная."),
    WIDE("wide", "Широкая", "Две колонки на цветных плашках: слева описание, справа характеристики и зачарования."),
    TECH("tech", "Терминал", "Блоки-плашки с яркой полосой слева, как экраны интерфейса: по плашке на каждый раздел.");

    public final String id, title, about;

    CardStyle(String id, String title, String about) { this.id = id; this.title = title; this.about = about; }

    public CardStyle next() { return values()[(ordinal() + 1) % values().length]; }

    public static CardStyle parse(String s, CardStyle def) {
        if (s == null) return def;
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (CardStyle v : values()) if (v.id.equals(k) || v.title.toLowerCase(Locale.ROOT).equals(k)) return v;
        return switch (k) { case "классика", "обычная", "1" -> CLASSIC; case "широкая", "две", "2" -> WIDE; case "терминал", "плашки", "3" -> TECH; default -> def; };
    }
}
