package me.politichell.engine;

import org.bukkit.Color;
import net.kyori.adventure.text.format.TextColor;

/**
 * СТИЛЬ 1.1 (лёгкий рефреш палитры Gold-Black-White):
 *  - золото чуть теплее и «имперее» (#F7C24F вместо старого #F5BE46);
 *  - сталь чуть светлее (#A8B1BE) для читаемости второстепенного текста;
 *  - фон панелей получил едва заметный синий оттенок графита (11,13,19);
 *  - hover-карточка потеплела (36,31,19).
 *  ЕДИНСТВЕННОЕ место в коде, где определяются цвета интерфейса.
 */
public final class Style1Theme {
    private Style1Theme() {}

    // Фоны
    public static final Color BG_DARK        = Color.fromARGB(240, 11, 13, 19);   // главный холст
    public static final Color BG_CARD        = Color.fromARGB(215, 22, 25, 34);   // карточка
    public static final Color BG_CARD_HOVER  = Color.fromARGB(235, 36, 31, 19);   // hover/актив
    public static final Color BG_DIM         = Color.fromARGB(180, 18, 20, 28);   // неактивная строка
    public static final Color TRANSPARENT_BG = Color.fromARGB(0, 0, 0, 0);        // без фона

    // Текст и акцент
    public static final TextColor GOLD       = TextColor.color(0xF7C24F); // имперское золото 1.1
    public static final TextColor GOLD_DIM   = TextColor.color(0xB98A34);
    public static final TextColor STEEL      = TextColor.color(0xA8B1BE);

    // Статусы (дипломатия)
    public static final int COLOR_SAME    = 0x2ECC71; // свои
    public static final int COLOR_ALLY    = 0x3498DB; // союзник
    public static final int COLOR_ENEMY   = 0xE74C3C; // враг/война
    public static final int COLOR_NEUTRAL = 0xD4AF37; // нейтральная держава

    public static float hoverScale()  { return 0.55f; }
    public static float normalScale() { return 0.52f; }
}
