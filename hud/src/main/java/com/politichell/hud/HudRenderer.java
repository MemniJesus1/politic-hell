package com.politichell.hud;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Рисует HUD в actionbar:  [плашка1][плашка2], а число кладёт ПОВЕРХ серой области плашки
 * с помощью отрицательных "пробелов" шрифта politichell:hud (см. ресурспак).
 */
public class HudRenderer implements Runnable {
    static volatile Key FONT = Key.key("politichell", "hud_tight");
    static final String GLYPH_DONATE = "\ue000";
    static final String GLYPH_COINS = "\ue001";
    static final String SAT_FULL = "\ue3c0", SAT_HALF = "\ue3c1";      // золотая обводка иконок еды (ресурспак)

    private final PoliticHellHud plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private int tick;

    // config cache
    private List<Currency> order;
    private int plateAdv, gap, idleRefresh, shift;
    /** mini = плашки в 2 раза меньше + крошечный шрифт цифр (3x5); см. build_pack.py --style mini */
    static volatile boolean tiny;
    private int centerDonate, maxDonate, centerCoins, maxCoins;
    private boolean anim;
    private double speed;
    private int hold;
    private TextColor cNormal, cGain, cLoss, cK, cM, cB, cT;
    private boolean suffixColors;
    private boolean satEnabled;

    private TextColor suffixColor(char ch) {
        return switch (ch) { case 'K' -> cK; case 'M' -> cM; case 'B' -> cB; default -> cT; };
    }

    private static class State {
        final EnumMap<Currency, Long> shown = new EnumMap<>(Currency.class);
        final EnumMap<Currency, Integer> holdLeft = new EnumMap<>(Currency.class);
        final EnumMap<Currency, Integer> dir = new EnumMap<>(Currency.class);
        boolean init;
        int satPts;          // насыщение в «половинках» иконок 0..20
    }

    public HudRenderer(PoliticHellHud plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var c = plugin.cfg("hud");
        order = new java.util.ArrayList<>();
        for (String s : c.getStringList("order")) {
            Currency cur = Currency.byId(s);
            if (cur != null && !order.contains(cur)) order.add(cur);
        }
        if (order.isEmpty()) order = List.of(Currency.DONATE, Currency.COINS);
        String h = c.getString("height", "tight").toLowerCase();
        if (!h.equals("tight") && !h.equals("armor") && !h.equals("high")) h = "tight";
        FONT = Key.key("politichell", "hud_" + h);          // высота плашек: шрифты hud_tight / hud_armor / hud_high есть в одном паке
        boolean mini = "mini".equalsIgnoreCase(c.getString("style", "mini"));
        tiny = mini;
        String L = mini ? "layout-mini." : "layout.";
        plateAdv = c.getInt(L + "plate-advance", mini ? 35 : 68);
        gap = c.getInt(L + "gap", mini ? 1 : 2);
        shift = c.getInt(L + "shift-right", mini ? 101 : 0);
        idleRefresh = Math.max(2, c.getInt("idle-refresh-ticks", 10));
        centerDonate = c.getInt(L + "donate.text-center", mini ? 20 : 40);
        maxDonate = c.getInt(L + "donate.text-max-width", mini ? 25 : 49);
        centerCoins = c.getInt(L + "coins.text-center", mini ? 14 : 27);
        maxCoins = c.getInt(L + "coins.text-max-width", mini ? 25 : 49);
        satEnabled = c.getBoolean("saturation.enabled", true);
        anim = c.getBoolean("animation.enabled", true);
        speed = c.getDouble("animation.speed", 0.22);
        hold = c.getInt("animation.hold-ticks", 24);
        cNormal = color(c.getString("colors.normal"), 0xFFFFFF);
        cGain = color(c.getString("colors.gain"), 0x55FF55);
        cLoss = color(c.getString("colors.loss"), 0xFF5555);
        suffixColors = c.getBoolean("colors.suffix-enabled", true);
        cK = color(c.getString("colors.suffix-k"), 0xBFBFBF);
        cM = color(c.getString("colors.suffix-m"), 0xFFC83C);
        cB = color(c.getString("colors.suffix-b"), 0xFF7AFF);
        cT = color(c.getString("colors.suffix-t"), 0x55FFFF);
    }

    private static TextColor color(String hex, int def) {
        TextColor t = hex == null ? null : TextColor.fromHexString(hex);
        return t != null ? t : TextColor.color(def);
    }

    public void forget(UUID id) { states.remove(id); }

    // ───────────────────────── tick ─────────────────────────
    @Override
    public void run() {
        tick += 2;
        CurrencyProvider prov = PoliticHellHud.provider();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.isHidden(p.getUniqueId())) continue;
            if (plugin.map() != null && plugin.map().isOpen(p.getUniqueId())) continue;   // у карты своя нижняя строка
            State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            boolean animating = false;
            for (Currency cur : Currency.values()) {
                long target = prov.get(p.getUniqueId(), cur);
                long shown = st.shown.getOrDefault(cur, target);
                if (!st.init || !anim) {
                    shown = target;
                } else if (shown != target) {
                    long diff = target - shown;
                    long step = Math.max(1, (long) Math.ceil(Math.abs(diff) * speed));
                    st.dir.put(cur, diff > 0 ? 1 : -1);
                    st.holdLeft.put(cur, hold);
                    shown += Math.signum(diff) * Math.min(step, Math.abs(diff));
                    animating = true;
                } else if (st.holdLeft.getOrDefault(cur, 0) > 0) {
                    st.holdLeft.merge(cur, -2, Integer::sum);
                    animating = true;
                }
                st.shown.put(cur, shown);
            }
            st.init = true;
            int sp = satPoints(p);
            if (sp != st.satPts) { st.satPts = sp; animating = true; }
            if (animating || tick % idleRefresh == 0) p.sendActionBar(build(st));
        }
    }

    /** Мгновенно перерисовать HUD игрока (например, после /hud on). */
    public void refresh(Player p) {
        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        CurrencyProvider prov = PoliticHellHud.provider();
        for (Currency cur : Currency.values()) st.shown.put(cur, prov.get(p.getUniqueId(), cur));
        st.init = true;
        st.satPts = satPoints(p);
        p.sendActionBar(build(st));
    }

    /** Насыщение игрока в половинках иконок (0..20). 0 — ничего не рисуем: нет еды на экране (творческий, спектатор, верховая езда) или насыщение пустое. */
    private int satPoints(Player p) {
        if (!satEnabled) return 0;
        var gm = p.getGameMode();
        if (gm == org.bukkit.GameMode.CREATIVE || gm == org.bukkit.GameMode.SPECTATOR) return 0;
        if (p.getVehicle() instanceof org.bukkit.entity.LivingEntity) return 0;     // верхом вместо еды рисуется здоровье скакуна
        float sat = Math.min(p.getSaturation(), p.getFoodLevel());
        if (sat <= 0.05f) return 0;
        return Math.max(1, Math.min(20, (int) Math.ceil(sat - 0.05f)));
    }

    // ───────────────────────── component ─────────────────────────
    private Component build(State st) {
        Component root = Component.empty();
        if (shift > 0) root = root.append(space(shift));   // сдвиг вправо (actionbar центрируется клиентом: центр уезжает на shift/2)
        for (int i = 0; i < order.size(); i++) {
            Currency cur = order.get(i);
            boolean donate = cur == Currency.DONATE;
            String text = compact(st.shown.getOrDefault(cur, 0L), donate ? maxDonate : maxCoins);
            int w = width(text);
            int center = donate ? centerDonate : centerCoins;
            int off = Math.max(0, center - w / 2);
            int after = plateAdv - off - w;

            if (i > 0) root = root.append(space(gap));
            // плашка (без тени)
            root = root.append(Component.text(donate ? GLYPH_DONATE : GLYPH_COINS)
                    .font(FONT).color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none()));
            // вернуться к началу плашки + смещение до центра серой области
            root = root.append(space(off - plateAdv));
            // число
            boolean flash = st.holdLeft.getOrDefault(cur, 0) > 0;
            TextColor col = flash ? (st.dir.getOrDefault(cur, 1) > 0 ? cGain : cLoss) : cNormal;
            // цифры — основным цветом, буквы K/M/B/T — своим (чтобы порядок читался с одного взгляда)
            StringBuilder run = new StringBuilder();
            TextColor runCol = null;
            for (char ch : text.toCharArray()) {
                TextColor cc = (!flash && suffixColors && "KMBT".indexOf(ch) >= 0) ? suffixColor(ch) : col;
                if (runCol != null && !cc.equals(runCol)) {
                    root = root.append(Component.text(run.toString()).font(FONT).color(runCol));
                    run.setLength(0);
                }
                runCol = cc; run.append(ch);
            }
            if (run.length() > 0) root = root.append(Component.text(run.toString()).font(FONT).color(runCol));
            // дойти до конца плашки, чтобы общий размер был ровно по плашкам (центровка клиентом)
            root = root.append(space(after));
        }
        return root.append(satOverlay(st.satPts));
    }

    /**
     * Золотая обводка насыщения поверх иконок голода. Курсор сначала уходит назад к левому краю нужной иконки
     * (полоса еды: x от +10 до +91 от центра экрана, иконка 8 px шагом справа налево), рисуются иконки, затем курсор
     * возвращается точно в конец строки — общая ширина actionbar не меняется, плашки денег не «ездят».
     */
    private Component satOverlay(int pts) {
        if (pts <= 0) return Component.empty();
        int wt = shift + order.size() * plateAdv + gap * (order.size() - 1);      // общая ширина строки выше
        int cursorEnd = -(wt / 2) + wt;                                           // где стоит курсор (px от центра экрана)
        int n = (pts + 1) / 2;
        int x0 = 82 - 8 * (n - 1);                                                // левый край самой левой иконки
        Component c = space(x0 - cursorEnd);
        for (int k = n - 1; k >= 0; k--) {
            boolean half = (pts % 2 == 1 && k == n - 1);
            c = c.append(Component.text(half ? SAT_HALF : SAT_FULL).font(FONT).color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none()));
            c = c.append(space(-2));                                              // глиф занимает 10 px, шаг иконок 8
        }
        return c.append(space(cursorEnd - (x0 + 8 * n)));
    }

    /** Сдвиг курсора на px пикселей (может быть отрицательным). */
    static Component space(int px) {
        if (px == 0) return Component.empty();
        StringBuilder sb = new StringBuilder();
        int left = Math.abs(px);
        int base = px > 0 ? 0xF000 : 0xF200;
        while (left > 0) {
            int n = Math.min(256, left);
            sb.append((char) (base + n));
            left -= n;
        }
        return Component.text(sb.toString()).font(FONT);
    }

    // ───────────────────────── text helpers ─────────────────────────
    /** Ширина строки в стандартном шрифте Minecraft (с учётом 1px между символами). */
    static int width(String s) { return width(s, tiny); }

    static int width(String s, boolean tinyFont) {
        int w = 0;
        for (char ch : s.toCharArray()) {
            w += tinyFont ? switch (ch) {
                case ' ' -> 3;
                case ',', '.' -> 2;
                default -> 4;
            } : switch (ch) {
                case ' ' -> 4;
                case ',', '.' -> 2;
                default -> 6;
            };
        }
        return Math.max(0, w - 1);
    }

    private static final long[] UNIT = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
    private static final char[] SYM = {'T', 'B', 'M', 'K'};

    /**
     * Формат для HUD (без пробелов, понятный с одного взгляда):
     *   999 → 999 · 100000 → 100000 · 1 000 000 → 1M · 1 010 000 → 1M10K · 3 142 342 → 3M142K · 2 500 000 000 → 2B500M
     * Если не влезает в плашку: 12 345 678 → 12.3M, а если и так не влезает → 12M. Всегда округляется ВНИЗ.
     */
    static String compact(long v, int maxWidth) { return compact(v, maxWidth, tiny); }

    static String compact(long v, int maxWidth, boolean tinyFont) {
        String sign = v < 0 ? "-" : "";
        long a = Math.abs(v);
        if (a < 1_000_000L) {
            String plain = sign + a;
            return width(plain, tinyFont) <= maxWidth ? plain : sign + a / 1000 + "K";
        }
        int i = 0;
        while (i < UNIT.length - 1 && a < UNIT[i]) i++;
        long major = a / UNIT[i], rem = a % UNIT[i];
        String full = sign + major + SYM[i];
        if (i + 1 < UNIT.length) {
            long minor = rem / UNIT[i + 1];
            if (minor > 0) full += minor + "" + SYM[i + 1];
        }
        if (width(full, tinyFont) <= maxWidth) return full;
        long tenth = rem * 10 / UNIT[i];
        String dec = sign + major + (tenth > 0 ? "." + tenth : "") + SYM[i];
        if (width(dec, tinyFont) <= maxWidth) return dec;
        return sign + major + SYM[i];
    }

    /** 1 234 567 → если не влезает, то 1.2M. (для плейсхолдеров PlaceholderAPI) */
    static String format(long v, int maxWidth) {
        String full = group(v);
        if (width(full) <= maxWidth) return full;
        double a = Math.abs((double) v);
        String sign = v < 0 ? "-" : "";
        String[] suf = {"K", "M", "B", "T"};
        double x = a;
        int idx = -1;
        while (x >= 1000 && idx < suf.length - 1) { x /= 1000; idx++; }
        String num = x >= 100 ? String.valueOf((long) x) : String.format(java.util.Locale.ROOT, "%.1f", x);
        if (num.endsWith(".0")) num = num.substring(0, num.length() - 2);
        return sign + num + (idx >= 0 ? suf[idx] : "");
    }

    private static String group(long v) {
        String s = Long.toString(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return (v < 0 ? "-" : "") + sb;
    }
}
