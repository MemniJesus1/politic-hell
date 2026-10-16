package com.politichell.hud;

/** Две валюты сервера. */
public enum Currency {
    /** Обычные монеты (жёлтая плашка). */
    COINS("coins"),
    /** Донатные монеты (фиолетовая плашка). */
    DONATE("donate");

    public final String id;

    Currency(String id) { this.id = id; }

    public static Currency byId(String s) {
        for (Currency c : values()) if (c.id.equalsIgnoreCase(s)) return c;
        return null;
    }
}
