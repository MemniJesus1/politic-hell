package com.politichell.hud.cards;

/** Привязка предмета: что с ним можно делать. Названия и объяснения — в cards.yml → bindings. */
public enum Binding {
    CONSUMABLE("consumable", 0),   // Свободный: обычный предмет
    PERSONAL("personal", 1),       // Персональный: не выпадает, нельзя выбросить/положить в сундук; можно в эндер-сундук и шалкер
    LOCKED("locked", 2),           // Неотчуждаемый: только в инвентаре игрока
    TEMPORARY("temporary", 0);     // Временный: исчезает по таймеру/событию

    public final String id;
    public final int level;        // 0 — свободный, 1 — персональный, 2 — неотчуждаемый

    Binding(String id, int level) { this.id = id; this.level = level; }

    public boolean bound() { return level > 0; }

    public static Binding parse(String s, Binding def) {
        if (s == null) return def;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        for (Binding b : values()) if (b.id.equals(t)) return b;
        return switch (t) {
            case "free", "свободный", "обычный", "none" -> CONSUMABLE;
            case "персональный", "personal", "привязанный", "bound" -> PERSONAL;
            case "неотчуждаемый", "lock", "locked" -> LOCKED;
            case "временный", "temp", "timed" -> TEMPORARY;
            default -> def;
        };
    }
}
