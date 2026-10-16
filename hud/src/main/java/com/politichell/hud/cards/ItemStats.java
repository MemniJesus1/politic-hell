package com.politichell.hud.cards;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.datacomponent.item.Consumable;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.potion.PotionEffect;

import java.util.*;

/**
 * «Живая» часть карточки: то, что зависит от конкретного предмета, а не от материала.
 * Прочность (цвет от зелёного к красному), зачарования (складываются с характеристиками: «Урон: 8 +3»),
 * эффекты еды и зелий, размер стопки. Результат пишется в CardData.stats / CardData.brief.
 * Строки используют цветовые коды &amp;x&amp;r&amp;r&amp;g&amp;g&amp;b&amp;b — CardText их понимает.
 */
final class ItemStats {
    private ItemStats() {}

    private static final String GRAY = "&x&a&8&a&8&b&4", PURPLE = "&x&c&0&a&0&f&f", RED = "&x&f&f&6&b&6&b";

    /** Название, описание-эффект и добавка к характеристике одного зачарования. */
    private record Ench(String name, String note, String label, double delta, boolean bad) {}

    // ───────── вспомогательное ─────────
    static String hx(TextColor c) {
        StringBuilder b = new StringBuilder("&x");
        for (char ch : c.asHexString().substring(1).toCharArray()) b.append('&').append(ch);
        return b.toString();
    }

    static String f(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 1 && n <= 10 ? r[n] : Integer.toString(n);
    }

    private static String label(String line) {
        int i = line.indexOf(':');
        return i > 0 ? line.substring(0, i).replaceAll("&[0-9a-fk-orx]", "").trim() : "";
    }

    static String mmss(long sec) {
        if (sec >= 3600) return (sec / 3600) + ":" + String.format("%02d:%02d", (sec % 3600) / 60, sec % 60);
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }

    /** Цвет прочности: 100% зелёный → 60% салатовый → 35% жёлтый → 15% оранжевый → 0% красный. */
    static TextColor durabilityColor(double t) {
        t = Math.max(0, Math.min(1, t));
        double[] at = {0.0, 0.15, 0.35, 0.60, 1.0};
        String[] col = {"#FF3B3B", "#FF8A3D", "#FFD23F", "#B6E24A", "#5BE06E"};
        for (int i = 0; i < at.length - 1; i++)
            if (t <= at[i + 1]) return TextColor.lerp((float) ((t - at[i]) / (at[i + 1] - at[i])), TextColor.fromHexString(col[i]), TextColor.fromHexString(col[i + 1]));
        return TextColor.fromHexString(col[col.length - 1]);
    }

    // ───────── зачарования ─────────
    private static final Map<String, String> RU = new HashMap<>();
    static {
        String[] a = {"protection", "Защита", "fire_protection", "Огнеупорность", "feather_falling", "Невесомость", "blast_protection", "Взрывоустойчивость",
                "projectile_protection", "Защита от снарядов", "respiration", "Подводное дыхание", "aqua_affinity", "Подводник", "thorns", "Шипы",
                "depth_strider", "Подводная ходьба", "frost_walker", "Ледоход", "binding_curse", "Проклятие несъёмности", "soul_speed", "Скорость души",
                "swift_sneaking", "Проворство", "sharpness", "Острота", "smite", "Небесная кара", "bane_of_arthropods", "Бич членистоногих",
                "knockback", "Отбрасывание", "fire_aspect", "Огненный аспект", "looting", "Добыча", "sweeping_edge", "Разящий клинок",
                "efficiency", "Эффективность", "silk_touch", "Шёлковое касание", "unbreaking", "Неразрушимость", "fortune", "Удача",
                "power", "Сила", "punch", "Отдача", "flame", "Воспламенение", "infinity", "Бесконечность", "luck_of_the_sea", "Везучий рыбак",
                "lure", "Приманка", "loyalty", "Верность", "impaling", "Пронзатель", "riptide", "Тяга", "channeling", "Громовержец",
                "multishot", "Тройной выстрел", "quick_charge", "Быстрая перезарядка", "piercing", "Пронзающая стрела", "mending", "Починка",
                "vanishing_curse", "Проклятие утраты", "density", "Плотность", "breach", "Брешь", "wind_burst", "Порыв ветра", "lunge", "Выпад"};
        for (int i = 0; i < a.length; i += 2) RU.put(a[i], a[i + 1]);
    }

    private static String pretty(String key) {
        String s = key.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Ench ench(String key, int l, boolean armor) {
        String name = RU.getOrDefault(key, pretty(key));
        return switch (key) {
            case "protection" -> new Ench(name, "−" + 4 * l + "% любого урона", null, 0, false);
            case "fire_protection" -> new Ench(name, "−" + 8 * l + "% урона от огня и лавы", null, 0, false);
            case "feather_falling" -> new Ench(name, "−" + 12 * l + "% урона от падения", null, 0, false);
            case "blast_protection" -> new Ench(name, "−" + 8 * l + "% урона от взрывов", null, 0, false);
            case "projectile_protection" -> new Ench(name, "−" + 8 * l + "% урона от стрел и снарядов", null, 0, false);
            case "respiration" -> new Ench(name, "+" + 15 * l + " с воздуха под водой", null, 0, false);
            case "aqua_affinity" -> new Ench(name, "добыча под водой без штрафа", null, 0, false);
            case "thorns" -> new Ench(name, 15 * l + "% шанс отразить урон атакующему", null, 0, false);
            case "depth_strider" -> new Ench(name, "под водой идёшь на " + Math.min(100, 33 * l) + "% от обычной скорости", null, 0, false);
            case "frost_walker" -> new Ench(name, "лёд под ногами, радиус " + Math.min(16, l + 2), null, 0, false);
            case "soul_speed" -> new Ench(name, "быстрее по песку и почве душ", null, 0, false);
            case "swift_sneaking" -> new Ench(name, "быстрее ходьба крадучись", null, 0, false);
            case "binding_curse" -> new Ench(name, "нельзя снять, пока не сломается", null, 0, true);
            case "vanishing_curse" -> new Ench(name, "исчезнет после смерти", null, 0, true);
            case "sharpness" -> new Ench(name, "+" + f(0.5 * l + 0.5) + " урона каждого удара", "Урон", 0.5 * l + 0.5, false);
            case "smite" -> new Ench(name, "+" + f(2.5 * l) + " урона по нежити", null, 0, false);
            case "bane_of_arthropods" -> new Ench(name, "+" + f(2.5 * l) + " урона по паукам и насекомым, замедляет их", null, 0, false);
            case "knockback" -> new Ench(name, "сильнее отбрасывает цель", null, 0, false);
            case "fire_aspect" -> new Ench(name, "поджигает цель на " + 4 * l + " с", null, 0, false);
            case "looting" -> new Ench(name, "до +" + l + " к добыче с мобов", null, 0, false);
            case "sweeping_edge" -> new Ench(name, "размах: " + Math.round(100.0 * l / (l + 1)) + "% урона соседним целям", null, 0, false);
            case "efficiency" -> new Ench(name, "+" + (l * l + 1) + " к скорости добычи", "Скорость добычи", l * l + 1, false);
            case "silk_touch" -> new Ench(name, "блоки выпадают целиком", null, 0, false);
            case "fortune" -> new Ench(name, "больше добычи: руда до ×" + (l + 1), null, 0, false);
            case "unbreaking" -> new Ench(name, "служит в " + f(unbreakMult(l, armor)) + " раза дольше", null, 0, false);
            case "mending" -> new Ench(name, "чинится опытом", null, 0, false);
            case "power" -> new Ench(name, "+" + 25 * (l + 1) + "% урона стрелы", null, 0, false);
            case "punch" -> new Ench(name, "сильнее отбрасывает цель", null, 0, false);
            case "flame" -> new Ench(name, "стрела поджигает цель на 5 с", null, 0, false);
            case "infinity" -> new Ench(name, "стрелы не тратятся", null, 0, false);
            case "quick_charge" -> new Ench(name, "перезарядка быстрее на " + f(0.25 * l) + " с", null, 0, false);
            case "multishot" -> new Ench(name, "3 снаряда за выстрел", null, 0, false);
            case "piercing" -> new Ench(name, "пробивает до " + l + " целей", null, 0, false);
            case "luck_of_the_sea" -> new Ench(name, "+" + l + " к удаче рыбалки", null, 0, false);
            case "lure" -> new Ench(name, "клёв быстрее на " + 5 * l + " с", null, 0, false);
            case "loyalty" -> new Ench(name, "трезубец возвращается после броска", null, 0, false);
            case "impaling" -> new Ench(name, "+" + f(2.5 * l) + " урона по водным мобам", null, 0, false);
            case "riptide" -> new Ench(name, "рывок вперёд в воде или под дождём", null, 0, false);
            case "channeling" -> new Ench(name, "бьёт молнией в грозу", null, 0, false);
            case "density" -> new Ench(name, "+" + f(0.5 * l) + " урона за каждый блок падения", null, 0, false);
            case "breach" -> new Ench(name, "игнорирует " + 15 * l + "% брони цели", null, 0, false);
            case "wind_burst" -> new Ench(name, "подбрасывает вверх после удара с высоты", null, 0, false);
            case "lunge" -> new Ench(name, "рывок вперёд при ударе копьём", null, 0, false);
            default -> new Ench(name, "", null, 0, false);
        };
    }

    /** Во сколько раз Неразрушимость увеличивает срок службы (у брони формула другая). */
    private static double unbreakMult(int l, boolean armor) {
        return armor ? 1.0 / (0.6 + 0.4 / (l + 1)) : l + 1;
    }

    // ───────── эффекты ─────────
    private static final Map<String, String> EFF = new HashMap<>();
    private static final Set<String> BAD = Set.of("slowness", "mining_fatigue", "instant_damage", "nausea", "blindness", "hunger", "weakness", "poison", "wither",
            "levitation", "unluck", "bad_omen", "darkness", "raid_omen", "trial_omen", "infested", "oozing", "weaving");
    static {
        String[] a = {"speed", "Скорость", "slowness", "Замедление", "haste", "Спешка", "mining_fatigue", "Усталость", "strength", "Сила", "instant_health", "Лечение",
                "instant_damage", "Урон", "jump_boost", "Прыгучесть", "nausea", "Тошнота", "regeneration", "Регенерация", "resistance", "Сопротивление",
                "fire_resistance", "Огнестойкость", "water_breathing", "Подводное дыхание", "invisibility", "Невидимость", "blindness", "Слепота",
                "night_vision", "Ночное зрение", "hunger", "Голод", "weakness", "Слабость", "poison", "Отравление", "wither", "Иссушение",
                "health_boost", "Прилив здоровья", "absorption", "Поглощение", "saturation", "Насыщение", "glowing", "Свечение", "levitation", "Левитация",
                "luck", "Удача", "unluck", "Неудача", "slow_falling", "Плавное падение", "conduit_power", "Сила тритона", "dolphins_grace", "Грация дельфина",
                "bad_omen", "Дурное знамение", "hero_of_the_village", "Герой деревни", "darkness", "Тьма", "wind_charged", "Ветровой заряд",
                "weaving", "Плетение", "oozing", "Слизь", "infested", "Заражение", "raid_omen", "Знамение набега", "trial_omen", "Знамение испытаний"};
        for (int i = 0; i < a.length; i += 2) EFF.put(a[i], a[i + 1]);
    }

    private static String effLine(PotionEffect pe, double prob, double timeFactor, CardConfig c) {
        String key = pe.getType().getKey().getKey();
        String name = EFF.getOrDefault(key, pretty(key));
        int amp = pe.getAmplifier();
        boolean bad = BAD.contains(key);
        String col = hx(bad ? c.statMinus : c.statPlus), L = hx(c.statLabel), V = hx(c.statValue);
        boolean instant = key.startsWith("instant_");
        long sec = pe.getDuration() < 0 ? -1 : Math.round(pe.getDuration() * timeFactor / 20.0);
        String time = instant ? "сразу" : sec < 0 ? "без срока" : mmss(sec);
        String extra = switch (key) {
            case "absorption" -> "+" + 4 * (amp + 1) + " здоровья-щита";
            case "regeneration" -> sec > 0 ? "≈" + Math.max(1, Math.round(sec * 20.0 / Math.max(1, 50 >> amp))) + " здоровья" : "";
            case "health_boost" -> "+" + 4 * (amp + 1) + " макс. здоровья";
            case "instant_health" -> "лечит " + 4 * (1 << amp);
            case "instant_damage" -> "урон " + 6 * (1 << amp);
            case "poison" -> sec > 0 ? "до " + Math.max(1, Math.round(sec * 20.0 / Math.max(1, 25 >> amp))) + " урона" : "";
            default -> "";
        };
        StringBuilder b = new StringBuilder(col).append(name);
        if (amp > 0) b.append(' ').append(roman(amp + 1));
        b.append(L).append(" · ").append(V).append(time);
        if (!extra.isEmpty()) b.append(L).append(" · ").append(col).append(extra);
        if (prob < 0.999) b.append(L).append(" · ").append(V).append(Math.round(prob * 100)).append('%');
        return b.toString();
    }

    // ───────── главная функция ─────────
    static void apply(CardData d, ItemStack s, ItemMeta meta, CardConfig c) {
        String L = hx(c.statLabel), V = hx(c.statValue), PLUS = hx(c.statPlus);
        Material m = d.material;
        boolean armor = d.icon.equals("armor") || m.name().endsWith("_HELMET") || m.name().endsWith("_CHESTPLATE") || m.name().endsWith("_LEGGINGS") || m.name().endsWith("_BOOTS");

        // 1. зачарования
        Map<String, Integer> en = new TreeMap<>();
        for (var e : meta.getEnchants().entrySet()) en.put(e.getKey().getKey().getKey(), e.getValue());
        boolean book = meta instanceof EnchantmentStorageMeta;
        if (book) for (var e : ((EnchantmentStorageMeta) meta).getStoredEnchants().entrySet()) en.put(e.getKey().getKey().getKey(), e.getValue());
        List<Ench> es = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Map<String, Double> delta = new HashMap<>();
        int unbr = 0;
        for (var e : en.entrySet()) {
            Ench x = ench(e.getKey(), e.getValue(), armor);
            es.add(x);
            names.add(x.name() + (e.getValue() > 1 || !e.getKey().endsWith("curse") ? " " + roman(e.getValue()) : ""));
            if (x.label() != null && !book) delta.merge(x.label(), x.delta(), Double::sum);
            if (e.getKey().equals("unbreaking")) unbr = e.getValue();
        }

        // 2. характеристики материала + добавки зачарований
        List<String> st = new ArrayList<>();
        for (String l : d.stats) {
            String lab = label(l);
            if (lab.equals("Прочность") || lab.equals("В стопке") || lab.equals("Стопка")) continue;
            Double dl = delta.get(lab);
            if (dl != null && !l.contains("&")) {
                String base = l.substring(l.indexOf(':') + 1).trim().split(" ")[0];
                try {
                    double b = Double.parseDouble(base.replace(",", "."));
                    st.add(L + lab + ": " + PLUS + f(b + dl));          // итог с зачарованием: «Урон: 10», а не «7 +3»
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            st.add(l);
        }

        // 3. прочность
        List<String> brief = new ArrayList<>();
        int max = m.getMaxDurability();
        if (meta instanceof Damageable dm && dm.hasMaxDamage()) max = dm.getMaxDamage();
        if (meta.isUnbreakable()) {
            String line = L + "Прочность: " + PLUS + "неломаемая";
            st.add(line); brief.add(line);
        } else if (max > 0 && meta instanceof Damageable dm) {
            int cur = Math.max(0, max - dm.getDamage());
            double t = (double) cur / max;
            String col = hx(durabilityColor(t));
            String line = L + "Прочность: " + col + cur + L + "/" + max;
            String bline = L + "Прочность: " + col + Math.round(t * 100) + "%";                 // кратко — проценты, подробно — «195/195»
            if (d.binding.bound() && c.unbreakableBound) { line += " " + GRAY + "не ломается"; bline += " " + GRAY + "не ломается"; }
            st.add(line); brief.add(bline);
        }

        // 3б. еда: в кратком виде сразу видно, сколько даёт голода и насыщения (в подробном они же в «Характеристиках»)
        try {
            var food = s.getData(DataComponentTypes.FOOD);
            if (food != null) brief.add(L + "Сытость: " + PLUS + "+" + food.nutrition() + L + "  Насыщение: " + PLUS + "+" + f(food.saturation()));
        } catch (Throwable ignored) {}

        // 4. стопка
        if (m.getMaxStackSize() > 1) st.add(L + "Стопка: " + V + "до " + m.getMaxStackSize());

        // 5. зачарования (раздел)
        if (!es.isEmpty()) {
            st.add("##" + (book ? "Книга даёт" : "Зачарования"));
            int i = 0;
            for (var e : en.entrySet()) {
                Ench x = es.get(i++);
                String col = x.bad() ? RED : PURPLE;
                String nm = x.name() + (e.getValue() > 1 || !e.getKey().endsWith("curse") ? " " + roman(e.getValue()) : "");
                st.add(col + nm + (x.note().isEmpty() ? "" : L + " — " + (x.bad() ? RED : V) + x.note()));
            }
            if (!book) brief.add(PURPLE + String.join(", ", names));
            else brief.add(PURPLE + String.join(", ", names));
        }

        // 6. эффекты (еда, зелья)
        List<String> eff = new ArrayList<>();
        double tf = m == Material.TIPPED_ARROW ? 0.125 : m == Material.LINGERING_POTION ? 0.25 : 1.0;
        try {
            Consumable cons = s.getData(DataComponentTypes.CONSUMABLE);
            if (cons != null) for (ConsumeEffect ce : cons.consumeEffects()) {
                if (ce instanceof ConsumeEffect.ApplyStatusEffects a) for (PotionEffect pe : a.effects()) eff.add(effLine(pe, a.probability(), 1.0, c));
                else if (ce instanceof ConsumeEffect.ClearAllStatusEffects) eff.add(L + "Снимает все действующие эффекты");
                else if (ce instanceof ConsumeEffect.TeleportRandomly tr) eff.add(L + "Телепорт в случайную точку (до " + f(tr.diameter() / 2) + " блоков)");
            }
        } catch (Throwable ignored) {}
        if (meta instanceof PotionMeta pm) {
            try { var bt = pm.getBasePotionType(); if (bt != null) for (PotionEffect pe : bt.getPotionEffects()) eff.add(effLine(pe, 1, tf, c)); } catch (Throwable ignored) {}
            for (PotionEffect pe : pm.getCustomEffects()) eff.add(effLine(pe, 1, tf, c));
        }
        if (meta instanceof SuspiciousStewMeta sm) for (PotionEffect pe : sm.getCustomEffects()) eff.add(effLine(pe, 1, 1.0, c));
        if (!eff.isEmpty()) {
            boolean drink = m.name().contains("POTION") || m == Material.TIPPED_ARROW;
            st.add("##" + (m == Material.TIPPED_ARROW ? "Эффект попадания" : m == Material.SPLASH_POTION ? "Эффекты при броске" : m == Material.LINGERING_POTION ? "Эффекты облака" : drink ? "Эффекты зелья" : "Эффекты при употреблении"));
            st.addAll(eff);
        }
        d.stats = st;
        d.brief = brief;
    }
}
