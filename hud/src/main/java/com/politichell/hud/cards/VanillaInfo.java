package com.politichell.hud.cards;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.Material;
import org.bukkit.attribute.AttributeModifier;
import io.papermc.paper.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Авто-оформление ЛЮБОГО предмета Minecraft: название, категория (иконка), редкость, описание и характеристики. */
public final class VanillaInfo {
    private VanillaInfo() {}

    public static String name(CardConfig c, Material m) {
        String ru = c.ruNames.get(m.name());
        if (ru != null) return ru;
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** id иконки: weapon tool armor food block resource potion magic misc */
    public static String category(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD") || n.equals("MACE") || n.equals("TRIDENT") || n.equals("BOW") || n.equals("CROSSBOW") || n.endsWith("_SPEAR")) return "weapon";
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS") || n.equals("FISHING_ROD")
                || n.equals("FLINT_AND_STEEL") || n.equals("BRUSH") || n.endsWith("_BUCKET") || n.equals("BUCKET")) return "tool";
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.equals("SHIELD") || n.equals("ELYTRA")
                || n.endsWith("_HORSE_ARMOR") || n.equals("WOLF_ARMOR") || n.equals("TURTLE_HELMET")) return "armor";
        if (n.equals("POTION") || n.equals("SPLASH_POTION") || n.equals("LINGERING_POTION") || n.equals("TIPPED_ARROW") || n.equals("EXPERIENCE_BOTTLE")
                || n.equals("DRAGON_BREATH") || n.equals("OMINOUS_BOTTLE")) return "potion";
        if (n.equals("ENCHANTED_BOOK") || n.equals("ENCHANTING_TABLE") || n.startsWith("ENDER_") || n.equals("NETHER_STAR") || n.equals("TOTEM_OF_UNDYING")
                || n.equals("BEACON") || n.equals("CONDUIT") || n.equals("HEART_OF_THE_SEA") || n.equals("ECHO_SHARD") || n.equals("RECOVERY_COMPASS")
                || n.equals("END_CRYSTAL") || n.equals("DRAGON_EGG") || n.equals("WIND_CHARGE") || n.equals("FIRE_CHARGE")) return "magic";
        if (m.isEdible()) return "food";
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.startsWith("RAW_") || n.endsWith("_ORE") || n.equals("DIAMOND") || n.equals("EMERALD")
                || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("REDSTONE") || n.equals("LAPIS_LAZULI") || n.equals("QUARTZ") || n.equals("AMETHYST_SHARD")
                || n.equals("NETHERITE_SCRAP") || n.equals("ANCIENT_DEBRIS") || n.equals("FLINT") || n.equals("CLAY_BALL") || n.equals("BRICK")) return "resource";
        if (m.isBlock()) return "block";
        return "misc";
    }

    public static String rarityId(CardConfig c, Material m) {
        CardConfig.Note nt = note(c, m);
        if (nt != null && nt.rarity() != null) return nt.rarity();
        String n = m.name();
        if (n.startsWith("NETHERITE_")) return "legendary";
        if (n.startsWith("DIAMOND_")) return "rare";
        if (n.startsWith("GOLDEN_") || n.startsWith("IRON_") || n.startsWith("CHAINMAIL_")) return "uncommon";
        if (n.endsWith("_SPAWN_EGG")) return "rare";
        try {
            ItemRarity r = new ItemStack(m).getRarity();
            return switch (r) { case UNCOMMON -> "uncommon"; case RARE -> "rare"; case EPIC -> "epic"; default -> "common"; };
        } catch (Throwable t) { return "common"; }
    }

    private static CardConfig.Note note(CardConfig c, Material m) { return c.notes.get(m.name()); }

    private record Rule(String kind, String key, String text) {}
    private static final List<Rule> RULES = List.of(
            new Rule("end", "_SWORD", "Основное оружие ближнего боя: быстрые удары и хороший урон."),
            new Rule("end", "_PICKAXE", "Добывает камень и руды. Чем лучше материал — тем глубже и быстрее копает."),
            new Rule("end", "_AXE", "Рубит дерево и ломает щиты. Бьёт сильнее меча, но медленнее."),
            new Rule("end", "_SHOVEL", "Быстро копает землю, песок, гравий и снег."),
            new Rule("end", "_HOE", "Вспахивает землю и быстро срезает листву и стога."),
            new Rule("is", "BOW", "Дальнобойное оружие. Зажми ПКМ, чтобы натянуть тетиву; нужны стрелы."),
            new Rule("is", "CROSSBOW", "Заряжается стрелой или фейерверком. Бьёт мощно и метко, но перезаряжается медленно."),
            new Rule("is", "SHIELD", "Принимает удары спереди: зажми ПКМ, чтобы поднять."),
            new Rule("end", "_HELMET", "Защищает голову. Чем лучше материал — тем выше броня."),
            new Rule("end", "_CHESTPLATE", "Защищает торс. Даёт больше всего брони из всех частей."),
            new Rule("end", "_LEGGINGS", "Защищает ноги. Вместе с остальным снаряжением складывается в общую броню."),
            new Rule("end", "_BOOTS", "Защищает ступни. Вместе с остальным снаряжением складывается в общую броню; чары «Невесомость» смягчают падение."),
            new Rule("end", "_SPAWN_EGG", "Яйцо призыва: вызывает существо. Обычно доступно только админам."),
            new Rule("end", "_ORE", "Руда. Добудь подходящей киркой и переплавь в печи."),
            new Rule("start", "RAW_", "Необработанная руда. Переплавь в печи — получишь слиток."),
            new Rule("end", "_INGOT", "Слиток металла. Материал для инструментов, брони и блоков."),
            new Rule("end", "_NUGGET", "Крупица металла: девять штук складываются в слиток."),
            new Rule("end", "_PLANKS", "Доски. Основа строительства и верстака."),
            new Rule("end", "_LOG", "Бревно дерева. Из него получаются доски и уголь."),
            new Rule("end", "_STAIRS", "Ступени: удобно подниматься и украшать постройки."),
            new Rule("end", "_SLAB", "Плита — половина блока. Два таких склеиваются в целый блок."),
            new Rule("end", "_WALL", "Каменная стена: выше блока, не пускает мобов."),
            new Rule("end", "_FENCE", "Забор: не пускает мобов и игроков, высота полтора блока."),
            new Rule("end", "_FENCE_GATE", "Калитка для забора."),
            new Rule("end", "_DOOR", "Дверь. Открывается по ПКМ или сигналом."),
            new Rule("end", "_TRAPDOOR", "Люк. Закрывает проём в полу и в стене."),
            new Rule("end", "_BUTTON", "Кнопка: на короткое время даёт сигнал."),
            new Rule("end", "_PRESSURE_PLATE", "Нажимная плита: подаёт сигнал, пока на неё кто-то стоит."),
            new Rule("end", "_SIGN", "Табличка: на ней можно написать текст."),
            new Rule("end", "_CHEST_BOAT", "Лодка с сундуком для перевозки вещей."),
            new Rule("end", "_BOAT", "Лодка. Быстро плавает по воде и безопасно перевозит."),
            new Rule("end", "_MINECART", "Вагонетка для рельсов."),
            new Rule("end", "_BED", "Кровать: пропускает ночь и сохраняет точку возрождения."),
            new Rule("end", "_WOOL", "Шерсть. Для кроватей, ковров и украшений."),
            new Rule("end", "_CARPET", "Ковёр. Украшает пол и гасит вибрации."),
            new Rule("end", "_DYE", "Краситель. Красит шерсть, стекло, глину и многое другое."),
            new Rule("end", "_BANNER", "Знамя: можно нарисовать узор и поставить как флаг."),
            new Rule("end", "_SHULKER_BOX", "Переносной сундук: содержимое остаётся внутри, даже когда ломаешь блок."),
            new Rule("end", "_SAPLING", "Саженец: посади на землю, и вырастет дерево."),
            new Rule("end", "_SEEDS", "Семена: посади на вспаханной земле."),
            new Rule("end", "_LEAVES", "Листва. Из неё иногда выпадают саженцы и яблоки."),
            new Rule("end", "_GLASS", "Прозрачный блок. Пропускает свет."),
            new Rule("end", "_GLASS_PANE", "Тонкая стеклянная панель."),
            new Rule("end", "_CONCRETE", "Бетон: гладкий цветной блок для строительства."),
            new Rule("end", "_TERRACOTTA", "Цветная глина: ровный матовый блок."),
            new Rule("start", "MUSIC_DISC", "Музыкальная пластинка. Вставь в проигрыватель."),
            new Rule("end", "_POTTERY_SHERD", "Черепок. Из четырёх таких делают узорный горшок."),
            new Rule("end", "_ARMOR_TRIM_SMITHING_TEMPLATE", "Шаблон отделки: украшает броню узором на кузнечном столе.")
    );

    public static List<String> desc(CardConfig c, Material m) {
        CardConfig.Note nt = note(c, m);
        if (nt != null && nt.desc() != null && !nt.desc().isBlank()) return List.of(nt.desc());
        String n = m.name();
        for (Rule r : RULES) {
            boolean hit = switch (r.kind) { case "end" -> n.endsWith(r.key); case "start" -> n.startsWith(r.key); default -> n.equals(r.key); };
            if (hit) return List.of(r.text);
        }
        return switch (category(m)) {
            case "food" -> List.of("Еда. Съешь, зажав ПКМ: утоляет голод и насыщает.");
            case "block" -> List.of("Блок. Ставится в мир для строительства и украшения.");
            case "resource" -> List.of("Ресурс. Используется в крафте и обмене.");
            case "potion" -> List.of("Зелье или эликсир. Действие зависит от рецепта.");
            case "magic" -> List.of("Редкий магический предмет.");
            case "tool" -> List.of("Инструмент. Облегчает работу и добычу.");
            default -> List.of("Предмет. Применение зависит от ситуации.");
        };
    }

    private static String f(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static final Map<String, String> ATTR = new LinkedHashMap<>();
    static {
        ATTR.put("attack_damage", "Урон"); ATTR.put("attack_speed", "Скорость атаки"); ATTR.put("armor", "Броня"); ATTR.put("armor_toughness", "Твёрдость брони");
        ATTR.put("knockback_resistance", "Стойкость к отбросу"); ATTR.put("max_health", "Здоровье"); ATTR.put("movement_speed", "Скорость");
        ATTR.put("attack_knockback", "Отбрасывание"); ATTR.put("mining_efficiency", "Эффективность добычи"); ATTR.put("block_interaction_range", "Дальность блоков");
        ATTR.put("entity_interaction_range", "Дальность атаки"); ATTR.put("jump_strength", "Прыжок"); ATTR.put("step_height", "Высота шага");
    }

    /** Одна строка характеристики из атрибута: «Урон: 7». */
    public static String attrLine(String key, double amount, AttributeModifier.Operation op) {
        String label = ATTR.get(key);
        if (label == null) return null;
        if (op != AttributeModifier.Operation.ADD_NUMBER) return label + ": " + (amount >= 0 ? "+" : "") + f(amount * 100) + "%";
        return switch (key) {
            case "attack_damage" -> label + ": " + f(1.0 + amount);
            case "attack_speed" -> label + ": " + f(4.0 + amount);
            case "knockback_resistance" -> label + ": +" + f(amount * 100) + "%";
            case "armor", "armor_toughness" -> label + ": " + f(amount);
            default -> label + ": " + (amount >= 0 ? "+" : "") + f(amount);
        };
    }

    /** Это строка про атрибут (урон/броня/скорость…)? Нужна, когда у своего предмета заданы собственные attributes. */
    public static boolean isAttrLine(String line) {
        int i = line.indexOf(':');
        return i > 0 && ATTR.containsValue(line.substring(0, i).trim());
    }

    /** Базовая скорость добычи инструмента (на подходящих блоках). Для «Скорость добычи» + Эффективность. */
    public static int miningSpeed(Material m) {
        String n = m.name();
        if (!(n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE"))) return 0;
        if (n.startsWith("WOODEN_")) return 2;
        if (n.startsWith("STONE_")) return 4;
        if (n.startsWith("COPPER_")) return 5;
        if (n.startsWith("IRON_")) return 6;
        if (n.startsWith("DIAMOND_")) return 8;
        if (n.startsWith("NETHERITE_")) return 9;
        if (n.startsWith("GOLDEN_")) return 12;
        return 0;
    }

    public static List<String> stats(Material m) {
        List<String> out = new ArrayList<>();
        try {
            ItemStack s = new ItemStack(m);
            ItemAttributeModifiers mods = s.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (mods != null) {
                Map<String, String> lines = new LinkedHashMap<>();
                Map<String, Double> sum = new LinkedHashMap<>();
                Map<String, AttributeModifier.Operation> ops = new HashMap<>();
                for (var e : mods.modifiers()) {
                    String key = e.attribute().getKey().getKey();
                    sum.merge(key, e.modifier().getAmount(), Double::sum);
                    ops.put(key, e.modifier().getOperation());
                }
                for (String key : ATTR.keySet()) if (sum.containsKey(key)) {
                    String l = attrLine(key, sum.get(key), ops.get(key));
                    if (l != null) lines.put(key, l);
                }
                out.addAll(lines.values());
            }
            FoodProperties food = s.getData(DataComponentTypes.FOOD);
            if (food != null) {
                out.add("Сытость: " + food.nutrition());
                out.add("Насыщение: " + f(food.saturation()));
            }
        } catch (Throwable ignored) {}
        int ms = miningSpeed(m);
        if (ms > 0) out.add("Скорость добычи: " + ms);
        try {
            if (m.isBlock() && m != Material.AIR) {
                float h = m.getHardness();
                if (h < 0) out.add("Неразрушимый блок");
                else if (h > 0) { out.add("Прочность блока: " + f(h)); out.add("Взрывоустойчивость: " + f(m.getBlastResistance())); }
            }
        } catch (Throwable ignored) {}
        try { if (m.isFuel()) out.add("Топливо для печи"); } catch (Throwable ignored) {}
        return out;
    }

}
