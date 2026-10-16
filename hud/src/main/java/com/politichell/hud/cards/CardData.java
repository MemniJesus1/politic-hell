package com.politichell.hud.cards;

import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/** Итоговые данные карточки предмета (после склейки: свой экземпляр → описание из items.yml → vanilla.yml → авто). */
public final class CardData {
    public Material material;
    public String id;            // id своего предмета (items.yml) или null
    public String title;         // название без цвета
    public Rarity rarity;
    public Binding binding = Binding.CONSUMABLE;
    public String icon = "misc";
    public List<String> desc = List.of();
    public List<String> stats = List.of();
    public List<String> brief = List.of();   // строки, которые видны и в кратком виде (прочность, зачарования)
    public Long expires;         // момент исчезновения (мс) или null
    public String event;         // событие-срок или null
    public UUID owner;           // владелец персонального предмета или null
    public String bindOn;             // pickup | equip | use — привяжется при событии (null — не ждёт)
    public Binding bindTo;            // во что превратится при привязке
    public boolean keepVanillaName;   // у зелий, голов, книг название оставляем клиентское

    public String signature(boolean full, String timerText, int gen, CardStyle style) {
        return Integer.toHexString((material + "|" + id + "|" + title + "|" + rarity.id() + "|" + binding.id + "|" + icon + "|" + desc + "|" + stats + "|" + brief
                + "|" + owner + "|" + event + "|" + timerText + "|" + full + "|" + keepVanillaName + "|" + style.id + "|" + bindOn + "|" + bindTo).hashCode()) + "." + gen;
    }
}
