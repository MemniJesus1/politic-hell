package com.politichell.hud.cards;

import net.kyori.adventure.text.format.TextColor;

import java.util.List;

/** Редкость: название и цвета градиента (cards.yml → rarities). */
public record Rarity(String id, String name, List<TextColor> stops) {
    /** Цвет для перегородки и акцентов — последний цвет градиента. */
    public TextColor accent() { return stops.get(stops.size() - 1); }
}
