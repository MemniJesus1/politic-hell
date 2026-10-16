package com.politichell.hud.cards;

import com.politichell.hud.PoliticHellHud;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

/**
 * Карточки предметов: единое описание для ЛЮБОГО предмета (ванильного и своего), привязки, сроки жизни.
 * Предмет оформляется «на месте» (название, описание, рамка подсказки) — см. docs/Карточки_предметов.md.
 */
public final class CardManager implements Listener, CommandExecutor, TabCompleter, Runnable {
    private final PoliticHellHud plugin;
    public final CardConfig config;
    public final CardText text;
    private final NamespacedKey ID, RARITY, BINDING, OWNER, EXPIRES, EVENT, BOOT, DESC, STATS, TITLE, ICON, SIG;
    private final long bootId = System.currentTimeMillis();
    private final Map<UUID, Boolean> modes = new HashMap<>();
    private final Map<UUID, CardStyle> styles = new HashMap<>();
    private final Set<UUID> bypassOn = new HashSet<>();
    private final Set<String> expiredEvents = new HashSet<>();
    private final Map<UUID, Map<Integer, ItemStack>> pending = new HashMap<>();
    private final Map<Material, List<String>> statsCache = new HashMap<>();
    private final File modesFile, eventsFile, pendingFile;
    private static final Set<String> ICON_IDS = Set.of("weapon", "tool", "armor", "food", "block", "resource", "potion", "magic", "misc", "custom");
    /** Предметы, у которых название определяет клиент (тип зелья, ник головы, книга) — свой заголовок им не ставим. */
    private static final Set<Material> KEEP_NAME = EnumSet.of(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION, Material.TIPPED_ARROW,
            Material.PLAYER_HEAD, Material.WRITTEN_BOOK, Material.OMINOUS_BOTTLE, Material.SUSPICIOUS_STEW, Material.FIREWORK_STAR, Material.GOAT_HORN);

    /** Ванильные строки зачарований и «Неразрушимый» прячем: вместо них — наши, в нужном месте карточки. */
    private static final ItemFlag[] HIDE_FLAGS = {ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE};
    private static final Set<Material> POTIONISH = EnumSet.of(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION, Material.TIPPED_ARROW,
            Material.ENCHANTED_BOOK, Material.SUSPICIOUS_STEW);
    private final Map<UUID, Long> lastDamage = new HashMap<>();

    public CardManager(PoliticHellHud plugin) {
        this.plugin = plugin;
        config = new CardConfig(plugin);
        text = new CardText(config);
        ID = new NamespacedKey(plugin, "card_id"); RARITY = new NamespacedKey(plugin, "card_rarity"); BINDING = new NamespacedKey(plugin, "card_binding");
        OWNER = new NamespacedKey(plugin, "card_owner"); EXPIRES = new NamespacedKey(plugin, "card_expires"); EVENT = new NamespacedKey(plugin, "card_event");
        BOOT = new NamespacedKey(plugin, "card_boot"); DESC = new NamespacedKey(plugin, "card_desc"); STATS = new NamespacedKey(plugin, "card_stats");
        TITLE = new NamespacedKey(plugin, "card_title"); ICON = new NamespacedKey(plugin, "card_icon"); SIG = new NamespacedKey(plugin, "card_sig");
        modesFile = new File(config.dir, "players.yml"); eventsFile = new File(config.dir, "events.yml"); pendingFile = new File(config.dir, "pending.yml");
    }

    // ═════════════ запуск / перезагрузка ═════════════
    public void enable() {
        config.load();
        loadState();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new CardGuard(plugin, this), plugin);
        var c1 = plugin.getCommand("card"); if (c1 != null) { c1.setExecutor(this); c1.setTabCompleter(this); }
        var c2 = plugin.getCommand("tip"); if (c2 != null) c2.setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(plugin, this, 40L, config.scanTicks);
        plugin.getLogger().info("Карточки предметов: " + config.custom.size() + " своих, " + config.vanilla.size() + " правок ванильных, редкостей " + config.rarities.size());
    }

    public void reload() { config.load(); statsCache.clear(); }

    public void disable() { saveState(); }

    private void loadState() {
        if (modesFile.exists()) {
            var y = YamlConfiguration.loadConfiguration(modesFile);
            for (String k : y.getKeys(false)) try {
                UUID u = UUID.fromString(k);
                if (y.isConfigurationSection(k)) {                                  // новый формат: <uuid>: { full: …, style: … }
                    if (y.contains(k + ".full")) modes.put(u, y.getBoolean(k + ".full"));
                    if (y.contains(k + ".style")) styles.put(u, CardStyle.parse(y.getString(k + ".style"), config.defaultStyle));
                } else modes.put(u, y.getBoolean(k));                               // старый формат: только «кратко/подробно»
            } catch (Exception ignored) {}
        }
        if (eventsFile.exists()) expiredEvents.addAll(YamlConfiguration.loadConfiguration(eventsFile).getStringList("expired"));
        if (pendingFile.exists()) {
            var y = YamlConfiguration.loadConfiguration(pendingFile);
            for (String u : y.getKeys(false)) try {
                Map<Integer, ItemStack> m = new LinkedHashMap<>();
                var sec = y.getConfigurationSection(u);
                if (sec != null) for (String slot : sec.getKeys(false))
                    m.put(Integer.parseInt(slot), ItemStack.deserializeBytes(Base64.getDecoder().decode(sec.getString(slot))));
                if (!m.isEmpty()) pending.put(UUID.fromString(u), m);
            } catch (Exception e) { plugin.getLogger().warning("cards/pending.yml: " + e); }
        }
    }

    private void saveState() {
        try {
            var y = new YamlConfiguration();
            for (UUID k : new java.util.TreeSet<>(java.util.stream.Stream.concat(modes.keySet().stream(), styles.keySet().stream()).toList())) {
                if (modes.containsKey(k)) y.set(k + ".full", modes.get(k));
                if (styles.containsKey(k)) y.set(k + ".style", styles.get(k).id);
            }
            y.save(modesFile);
            var e = new YamlConfiguration(); e.set("expired", new ArrayList<>(expiredEvents)); e.save(eventsFile);
            savePending();
        } catch (Exception ex) { plugin.getLogger().warning("cards: не удалось сохранить состояние: " + ex); }
    }

    void savePending() {
        try {
            var y = new YamlConfiguration();
            pending.forEach((u, m) -> m.forEach((slot, it) -> y.set(u + "." + slot, Base64.getEncoder().encodeToString(it.serializeAsBytes()))));
            y.save(pendingFile);
        } catch (Exception ex) { plugin.getLogger().warning("cards/pending.yml: " + ex); }
    }

    // ═════════════ режим «кратко / подробно» ═════════════
    public boolean full(Player p) { return modes.getOrDefault(p.getUniqueId(), config.defaultFull); }

    public void toggleMode(Player p) {
        boolean now = !full(p);
        modes.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text(now ? "§aОписания предметов: подробно" : "§7Описания предметов: кратко"));
        refresh(p);
        saveState();
    }

    public CardStyle style(Player p) { return styles.getOrDefault(p.getUniqueId(), config.defaultStyle); }

    public void setStyle(Player p, CardStyle st) {
        styles.put(p.getUniqueId(), st);
        p.sendActionBar(Component.text("§bВид карточки: §f" + st.title));
        refresh(p);
        saveState();
    }

    public void refresh(Player p) {
        boolean full = full(p);
        decorateAll(p.getInventory(), p, full);
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.PLAYER && realTop(top)) decorateAll(top, null, full, p);
        p.updateInventory();
    }

    // ═════════════ чтение предмета ═════════════
    private static String str(PersistentDataContainer p, NamespacedKey k) { return p.get(k, PersistentDataType.STRING); }

    public Binding bindingOf(ItemStack s) {
        if (s == null || s.getType().isAir()) return Binding.CONSUMABLE;
        ItemMeta m = s.getItemMeta();
        if (m == null) return Binding.CONSUMABLE;
        return bindingOf(s.getType(), m.getPersistentDataContainer());
    }

    /** Предмет ещё «ждёт» привязки: при каком событии (on) и во что превратится (to). */
    record Pend(Binding to, String on) {}

    Pend pendingRule(Material mat, PersistentDataContainer p) {
        if (str(p, BINDING) != null) return null;
        String id = str(p, ID);
        var cu = id == null ? null : config.custom.get(id);
        if (cu != null) return cu.bindOn() != null && !cu.bindOn().equals("none") && cu.binding().bound() ? new Pend(cu.binding(), cu.bindOn()) : null;
        var ov = config.ov(mat);
        return ov != null && ov.bindOn() != null && !ov.bindOn().equals("none") && ov.binding() != null && ov.binding().bound() ? new Pend(ov.binding(), ov.bindOn()) : null;
    }

    private Binding bindingOf(Material mat, PersistentDataContainer p) {
        String b = str(p, BINDING);
        if (b != null) return Binding.parse(b, Binding.CONSUMABLE);
        if (pendingRule(mat, p) != null) return Binding.CONSUMABLE;       // пока не привязан — свободный
        String id = str(p, ID);
        var cu = id == null ? null : config.custom.get(id);
        if (cu != null) return cu.binding();
        var ov = config.ov(mat);
        return ov != null && ov.binding() != null ? ov.binding() : Binding.CONSUMABLE;
    }

    /** Привязка с учётом содержимого: шалкер/мешок с привязанным предметом сам становится привязанным. */
    public Binding effective(ItemStack s) {
        if (s == null || s.getType().isAir()) return Binding.CONSUMABLE;
        ItemMeta m = s.getItemMeta();
        if (m == null) return Binding.CONSUMABLE;
        int lvl = bindingOf(s.getType(), m.getPersistentDataContainer()).level;
        try {
            if (m instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof org.bukkit.block.Container box) {
                for (ItemStack in : box.getInventory().getContents()) if (in != null && !in.getType().isAir()) lvl = Math.max(lvl, bindingOf(in).level);
            } else if (m instanceof BundleMeta bm) {
                for (ItemStack in : bm.getItems()) lvl = Math.max(lvl, bindingOf(in).level);
            }
        } catch (Exception ignored) {}
        return lvl >= 2 ? Binding.LOCKED : lvl == 1 ? Binding.PERSONAL : Binding.CONSUMABLE;
    }

    public UUID ownerOf(ItemStack s) {
        if (s == null || s.getType().isAir()) return null;
        ItemMeta m = s.getItemMeta();
        if (m == null) return null;
        String o = str(m.getPersistentDataContainer(), OWNER);
        try { return o == null ? null : UUID.fromString(o); } catch (Exception e) { return null; }
    }

    public String eventOf(ItemStack s) {
        if (s == null || s.getType().isAir()) return null;
        ItemMeta m = s.getItemMeta();
        return m == null ? null : str(m.getPersistentDataContainer(), EVENT);
    }

    private List<String> autoStats(Material m) { return statsCache.computeIfAbsent(m, VanillaInfo::stats); }

    /** Склейка данных: свой экземпляр (/card set) → items.yml → vanilla.yml → авто. */
    public CardData resolve(Material mat, PersistentDataContainer p) {
        CardData d = new CardData();
        d.material = mat;
        d.id = str(p, ID);
        var cu = d.id == null ? null : config.custom.get(d.id);
        var ov = config.ov(mat);
        String t = str(p, TITLE);
        d.title = t != null ? t : cu != null && cu.name() != null ? cu.name() : ov != null && ov.name() != null ? ov.name() : VanillaInfo.name(config, mat);
        String r = str(p, RARITY);
        d.rarity = config.rarity(r != null ? r : cu != null && cu.rarity() != null ? cu.rarity() : ov != null && ov.rarity() != null ? ov.rarity() : VanillaInfo.rarityId(config, mat));
        d.binding = bindingOf(mat, p);
        String ic = str(p, ICON);
        d.icon = ic != null ? ic : cu != null && cu.icon() != null ? cu.icon() : ov != null && ov.icon() != null ? ov.icon() : VanillaInfo.category(mat);
        String ds = str(p, DESC);
        d.desc = ds != null ? List.of(ds.split("\n")) : cu != null && !cu.desc().isEmpty() ? cu.desc() : ov != null && ov.desc() != null ? ov.desc() : VanillaInfo.desc(config, mat);
        List<String> st = new ArrayList<>();
        List<String> base = ov != null && ov.stats() != null ? ov.stats() : (cu == null || cu.autoStats()) ? autoStats(mat) : List.of();
        for (String l : base) if (cu == null || cu.attributes().isEmpty() || !VanillaInfo.isAttrLine(l)) st.add(l);
        if (cu != null) {
            for (String a : cu.attributes()) { String l = attrStat(a); if (l != null) st.add(0, l); }
            st.addAll(cu.stats());
        }
        String xs = str(p, STATS);
        if (xs != null) st.addAll(List.of(xs.split("\n")));
        d.stats = st;
        Long ex = p.get(EXPIRES, PersistentDataType.LONG);
        d.expires = ex;
        d.event = str(p, EVENT);
        String ow = str(p, OWNER);
        try { d.owner = ow == null ? null : UUID.fromString(ow); } catch (Exception e) { d.owner = null; }
        d.keepVanillaName = KEEP_NAME.contains(mat);
        Pend pr = pendingRule(mat, p);
        if (pr != null) { d.bindOn = pr.on(); d.bindTo = pr.to(); }
        return d;
    }

    private String attrStat(String def) {
        try {
            String[] a = def.split("=");
            String key = a[0].trim().toLowerCase(Locale.ROOT);
            double v = Double.parseDouble(a[1].split("@")[0].trim());
            return VanillaInfo.attrLine(key, v, AttributeModifier.Operation.ADD_NUMBER);
        } catch (Exception e) { return null; }
    }

    // ═════════════ обход и привязка «при событии» ═════════════
    /** Обходит ли игрок правила привязки: только если админ САМ включил /card bypass (или включён bypass-creative). Права op сами по себе обход не дают. */
    public boolean bypassing(Player p) {
        return bypassOn.contains(p.getUniqueId()) || (config.bypassCreative && p.getGameMode() == org.bukkit.GameMode.CREATIVE);
    }

    private static String when(String on) {
        return switch (on) { case "pickup" -> "подборе"; case "equip" -> "надевании или в руке"; default -> "первом использовании"; };
    }

    static String whenText(String on) { return when(on); }

    /** Привязывает предмет к игроку (владелец + привязка). true — привязан сейчас. */
    private boolean bindTo(Player p, ItemStack s, Pend pr) {
        ItemMeta m = s.getItemMeta();
        if (m == null) return false;
        var pd = m.getPersistentDataContainer();
        pd.set(BINDING, PersistentDataType.STRING, pr.to().id);
        pd.set(OWNER, PersistentDataType.STRING, p.getUniqueId().toString());
        pd.remove(SIG);
        s.setItemMeta(m);
        p.sendActionBar(Component.text("§b«" + VanillaInfo.name(config, s.getType()) + "» теперь привязан к тебе: §f" + config.bindings.get(pr.to()).name()));
        return true;
    }

    /** Подбор / надевание: вызывается при досмотре инвентаря. slot — ячейка в PlayerInventory. */
    private boolean bindScan(Player p, ItemStack s, int slot) {
        ItemMeta m = s.getItemMeta();
        if (m == null) return false;
        Pend pr = pendingRule(s.getType(), m.getPersistentDataContainer());
        if (pr == null) return false;
        boolean worn = slot >= 36 && slot <= 40 || slot == p.getInventory().getHeldItemSlot();
        if (pr.on().equals("pickup") || (pr.on().equals("equip") && worn)) return bindTo(p, s, pr);
        return false;
    }

    /** Использование: удар, добыча блока, клик предметом. */
    void bindUse(Player p, EquipmentSlot hand) {
        PlayerInventory inv = p.getInventory();
        ItemStack s = inv.getItem(hand);
        if (s == null || s.getType().isAir() || !config.enabled) return;
        ItemMeta m = s.getItemMeta();
        if (m == null) return;
        Pend pr = pendingRule(s.getType(), m.getPersistentDataContainer());
        if (pr == null || pr.on().equals("pickup")) return;      // «при подборе» уже привязано при досмотре
        if (bindTo(p, s, pr)) { decorate(s, p, full(p)); inv.setItem(hand, s); }
    }

    // ═════════════ срок жизни ═════════════
    public String timerText(CardData d, long now) {
        if (d.expires == null && (d.event == null || d.event.isBlank())) return null;
        List<String> parts = new ArrayList<>();
        if (d.expires != null) {
            long s = Math.max(0, (d.expires - now) / 1000);
            if (s >= 3600) parts.add("через " + (s / 3600) + "ч " + ((s % 3600) / 60) + "м");
            else if (s >= 60) parts.add("через " + (s / 60) + "м");
            else parts.add("меньше чем через минуту");
        }
        if (d.event != null && !d.event.isBlank()) parts.add(switch (d.event) {
            case "death" -> "при смерти"; case "logout" -> "при выходе"; case "restart" -> "при перезапуске"; default -> "по событию «" + d.event + "»";
        });
        return "Исчезнет " + String.join(" или ", parts);
    }

    private boolean expired(ItemMeta m) {
        var p = m.getPersistentDataContainer();
        Long ex = p.get(EXPIRES, PersistentDataType.LONG);
        if (ex != null && System.currentTimeMillis() >= ex) return true;
        String ev = str(p, EVENT);
        if (ev == null) return false;
        if (expiredEvents.contains(ev)) return true;
        if (ev.equals("restart")) { Long b = p.get(BOOT, PersistentDataType.LONG); return b != null && b != bootId; }
        return false;
    }

    // ═════════════ оформление предмета ═════════════
    /** Оформляет предмет. holder — кто держит (для привязки к владельцу), может быть null. true — предмет изменён. */
    public boolean decorate(ItemStack s, Player holder, boolean full) { return decorate(s, holder, full, false); }

    /** quiet = предмет уже оформлен, не перерисовывать (чтобы предмет в руке не «дёргался» во время работы). */
    public boolean decorate(ItemStack s, Player holder, boolean full, boolean quiet) { return decorate(s, holder, full, quiet, holder != null ? style(holder) : config.defaultStyle); }

    public boolean decorate(ItemStack s, Player holder, boolean full, boolean quiet, CardStyle style) {
        if (!config.enabled || s == null || s.getType().isAir() || !s.getType().isItem()) return false;
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return false;
        var p = meta.getPersistentDataContainer();
        String oldSig = str(p, SIG);
        if (quiet && oldSig != null) return false;
        if (oldSig == null && !config.foreign && (meta.hasLore() || meta.hasItemName())) return false;   // предмет с чужим описанием не трогаем
        long now = System.currentTimeMillis();
        CardData d = resolve(s.getType(), p);
        var cu = d.id == null ? null : config.custom.get(d.id);
        if (cu != null && cu.ttl() > 0 && d.expires == null) { d.expires = now + cu.ttl() * 1000; p.set(EXPIRES, PersistentDataType.LONG, d.expires); }
        if (cu != null && cu.event() != null && !cu.event().isBlank() && d.event == null) { d.event = cu.event(); p.set(EVENT, PersistentDataType.STRING, d.event); }
        if ("restart".equals(d.event) && p.get(BOOT, PersistentDataType.LONG) == null) p.set(BOOT, PersistentDataType.LONG, bootId);
        if (d.binding.bound() && d.owner == null && holder != null) { d.owner = holder.getUniqueId(); p.set(OWNER, PersistentDataType.STRING, d.owner.toString()); }
        ItemStats.apply(d, s, meta, config);
        String timer = timerText(d, now);
        String sig = d.signature(full, timer, config.gen, style);
        if (sig.equals(oldSig)) return false;
        if (!d.keepVanillaName) meta.itemName(text.title(d, style, full, timer));
        meta.lore(text.lore(d, full, timer, style));
        try { meta.setTooltipStyle(NamespacedKey.fromString(config.stylePrefix + d.rarity.id())); } catch (Throwable ignored) {}
        if (config.hideAttributes) meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addItemFlags(HIDE_FLAGS);
        if (POTIONISH.contains(s.getType())) meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        p.set(SIG, PersistentDataType.STRING, sig);
        s.setItemMeta(meta);
        return true;
    }

    public boolean strip(ItemStack s) {
        if (s == null || s.getType().isAir()) return false;
        ItemMeta meta = s.getItemMeta();
        if (meta == null || str(meta.getPersistentDataContainer(), SIG) == null) return false;
        meta.itemName(null); meta.lore(null);
        try { meta.setTooltipStyle(null); } catch (Throwable ignored) {}
        meta.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.removeItemFlags(HIDE_FLAGS);
        meta.removeItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().remove(SIG);
        s.setItemMeta(meta);
        return true;
    }

    public void decorateAll(Inventory inv, Player holder, boolean full) { decorateAll(inv, holder, full, holder); }

    public void decorateAll(Inventory inv, Player holder, boolean full, Player viewer) {
        if (inv == null) return;
        CardStyle st = viewer != null ? style(viewer) : config.defaultStyle;
        int n = inv instanceof PlayerInventory ? 41 : inv.getSize();
        for (int i = 0; i < n; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) continue;
            if (decorate(s, holder, full, false, st)) inv.setItem(i, s);
        }
    }

    // ═════════════ создание / выдача ═════════════
    public ItemStack create(String key, int amount, Player holder) {
        var cu = config.custom.get(key.toLowerCase(Locale.ROOT));
        Material m = cu != null ? cu.base() : Material.matchMaterial(key);
        if (m == null || !m.isItem() || m.isAir()) return null;
        ItemStack s = new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        if (cu != null) {
            ItemMeta meta = s.getItemMeta();
            meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, cu.id());
            if (cu.glow()) meta.setEnchantmentGlintOverride(true);
            if (cu.unbreakable()) meta.setUnbreakable(true);
            if (cu.cmd() > 0) { try { meta.setCustomModelData(cu.cmd()); } catch (Throwable ignored) {} }
            if (cu.itemModel() != null && !cu.itemModel().isBlank()) { var k = NamespacedKey.fromString(cu.itemModel().trim()); if (k != null) meta.setItemModel(k); }
            int idx = 0;
            for (String a : cu.attributes()) {
                try {
                    String[] kv = a.split("=");
                    String[] vs = kv[1].split("@");
                    Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(kv[0].trim().toLowerCase(Locale.ROOT)));
                    EquipmentSlotGroup g = vs.length > 1 ? EquipmentSlotGroup.getByName(vs[1].trim().toLowerCase(Locale.ROOT)) : EquipmentSlotGroup.ANY;
                    if (attr == null) { plugin.getLogger().warning("items.yml: " + cu.id() + ": неизвестный атрибут «" + kv[0] + "»"); continue; }
                    meta.addAttributeModifier(attr, new AttributeModifier(new NamespacedKey(plugin, "card_" + cu.id() + "_" + (idx++)),
                            Double.parseDouble(vs[0].trim()), AttributeModifier.Operation.ADD_NUMBER, g == null ? EquipmentSlotGroup.ANY : g));
                } catch (Exception e) { plugin.getLogger().warning("items.yml: " + cu.id() + ": плохой атрибут «" + a + "» (нужно attack_damage=9@mainhand)"); }
            }
            s.setItemMeta(meta);
        }
        decorate(s, holder, holder != null ? full(holder) : config.defaultFull);
        return s;
    }

    // ═════════════ события ═════════════
    @Override
    public void run() {
        if (!config.enabled) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean full = full(p);
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < 41; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.getType().isAir()) continue;
                ItemMeta m = s.getItemMeta();
                if (m != null && expired(m)) {
                    inv.setItem(i, null);
                    p.sendActionBar(Component.text("§7Предмет «" + (m.hasItemName() || m.hasDisplayName() ? "особый" : VanillaInfo.name(config, s.getType())) + "» исчез — срок вышел"));
                    continue;
                }
                if (bindScan(p, s, i)) inv.setItem(i, s);
                boolean inUse = (i == inv.getHeldItemSlot() || i == 40 || (i >= 36 && i <= 39)) && now - lastDamage.getOrDefault(p.getUniqueId(), 0L) < 3000;
                if (decorate(s, p, full, inUse)) inv.setItem(i, s);
            }
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.PLAYER && realTop(top)) decorateAll(top, null, full, p);
        }
    }

    /** Настоящее хранилище (сундук, бочка, шалкер, воронка, эндер-сундук, вагонетка, торговец…), а не меню другого плагина (DeluxeMenus, наша панель): меню не оформляем и Shift+ПКМ в них не перехватываем. */
    public boolean realTop(Inventory top) {
        InventoryType t = top.getType();
        if (t == InventoryType.CRAFTING || t == InventoryType.PLAYER) return true;
        if (t != InventoryType.CHEST && t != InventoryType.ENDER_CHEST && t != InventoryType.SHULKER_BOX && t != InventoryType.BARREL
                && t != InventoryType.HOPPER && t != InventoryType.DISPENSER && t != InventoryType.DROPPER) return true;   // верстак, печь, наковальня… — всегда настоящие
        Object h;
        try { h = top.getHolder(false); } catch (Throwable e) { h = top.getHolder(); }
        return h instanceof org.bukkit.block.BlockState || h instanceof org.bukkit.block.DoubleChest || h instanceof org.bukkit.entity.Entity;
    }

    /** Запоминаем, когда предмет последний раз портился: пока игрок работает, строку прочности не трогаем (иначе предмет в руке дёргается). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(org.bukkit.event.player.PlayerItemDamageEvent e) { lastDamage.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    @EventHandler public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        InventoryType t = e.getInventory().getType();
        if (t != InventoryType.CRAFTING && t != InventoryType.PLAYER && realTop(e.getInventory())) decorateAll(e.getInventory(), null, full(p), p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Item it = e.getItem();
        ItemStack s = it.getItemStack();
        UUID owner = ownerOf(s);
        if (owner != null && !owner.equals(p.getUniqueId()) && !bypassing(p)) { e.setCancelled(true); return; }
        if (decorate(s, p, full(p))) it.setItemStack(s);
    }

    @EventHandler public void onCraftPrepare(PrepareItemCraftEvent e) {
        ItemStack r = e.getInventory().getResult();
        if (r == null || r.getType().isAir()) return;
        Player p = e.getView().getPlayer() instanceof Player pl ? pl : null;
        if (decorate(r, null, p != null ? full(p) : config.defaultFull, false, p != null ? style(p) : config.defaultStyle)) e.getInventory().setResult(r);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) { restorePending(p); refresh(p); } }, 20L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        bypassOn.remove(e.getPlayer().getUniqueId());
        PlayerInventory inv = e.getPlayer().getInventory();
        for (int i = 0; i < 41; i++) if ("logout".equals(eventOf(inv.getItem(i)))) inv.setItem(i, null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        PlayerInventory inv = p.getInventory();
        // предметы со сроком «при смерти» исчезают
        for (int i = 0; i < 41; i++) if ("death".equals(eventOf(inv.getItem(i)))) inv.setItem(i, null);
        e.getDrops().removeIf(it -> "death".equals(eventOf(it)));
        if (e.getKeepInventory()) return;
        // привязанные предметы (и шалкеры с ними) не выпадают — вернём в те же ячейки после возрождения
        Map<Integer, ItemStack> keep = new LinkedHashMap<>();
        for (int i = 0; i < 41; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.getType().isAir() && effective(s).bound()) keep.put(i, s.clone());
        }
        e.getDrops().removeIf(it -> effective(it).bound());
        if (!keep.isEmpty()) { pending.put(p.getUniqueId(), keep); savePending(); }
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> restorePending(p), 2L);
    }

    private void restorePending(Player p) {
        Map<Integer, ItemStack> keep = pending.remove(p.getUniqueId());
        if (keep == null) return;
        PlayerInventory inv = p.getInventory();
        for (var en : keep.entrySet()) {
            ItemStack cur = inv.getItem(en.getKey());
            if (cur == null || cur.getType().isAir()) inv.setItem(en.getKey(), en.getValue());
            else for (ItemStack left : inv.addItem(en.getValue()).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
        savePending();
        p.sendActionBar(Component.text("§bПерсональные предметы остались с тобой"));
    }

    // ═════════════ команды ═════════════
    private static final String HELP = """
            §6══ Карточки предметов ══
            §e/tip §7— переключить «кратко / подробно» (то же: Shift+ПКМ по предмету в инвентаре)
            §e/card give <ник> <id|МАТЕРИАЛ> [кол-во] [binding=… rarity=… name=… ttl=…] §7— выдать, сразу с привязкой/названием
            §e/card list [свои|все] §7· §e/card info §7— что за предмет в руке
            §e/card set <name|rarity|binding|icon|desc|ttl|event|owner> <значение> §7— изменить предмет в руке
            §e/card add <desc|stat> <текст> §7· §e/card clear <поле|all>
            §e/card bind <привязка> §7— привязка предмета в руке (свободный / персональный / неотчуждаемый / временный)
            §e/card group <шаблон|category:тип> <привязка> [редкость] [on=pickup|equip|use] §7— массово (NETHERITE_* …); on= — привяжется сам при подборе/надевании/использовании
            §e/card bypass [on|off] §7— админ: обойти привязку (выбросить/спрятать привязанное). Само по себе право op обход НЕ даёт
            §e/card unbind §7— снять привязку и владельца с предмета в руке
            §e/card preview <id|МАТЕРИАЛ> [full|brief] [sharpness=5 damage=500] §7— показать карточку текстом (и из консоли)
            §e/card save <id> §7— сохранить предмет из руки как свой в items.yml
            §e/card export §7— выгрузить ВСЕ предметы игры в vanilla.yml для правки
            §e/card expire <событие> §7— убрать все предметы с таким сроком у всех
            §e/card strip [all] §7· §e/card refresh §7· §e/card reload""";

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("tip")) {
            if (!(s instanceof Player p)) { s.sendMessage("Только для игрока"); return true; }
            if (a.length == 0) { toggleMode(p); return true; }
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("style") || sub.equals("вид") || sub.equals("стиль")) {
                if (a.length < 2) { setStyle(p, style(p).next()); return true; }
                CardStyle st = CardStyle.parse(a[1], null);
                if (st == null) { p.sendMessage("§cВиды: " + String.join(", ", java.util.Arrays.stream(CardStyle.values()).map(x -> x.id + " (" + x.title + ")").toList())); return true; }
                setStyle(p, st);
            } else if (sub.equals("full") || sub.equals("подробно")) { if (!full(p)) toggleMode(p); }
            else if (sub.equals("brief") || sub.equals("кратко")) { if (full(p)) toggleMode(p); }
            else {
                CardStyle st = CardStyle.parse(sub, null);
                if (st != null) setStyle(p, st);
                else {
                    p.sendMessage("§6/tip §7— переключить «кратко / подробно»");
                    p.sendMessage("§6/tip style §7[classic|wide|tech] — вид карточки предмета (без слова — следующий по кругу)");
                    for (CardStyle x : CardStyle.values()) p.sendMessage("  §e" + x.id + " §7(" + x.title + ") — " + x.about);
                }
            }
            return true;
        }
        if (!s.hasPermission("politichellhud.admin")) { s.sendMessage("§cНет прав"); return true; }
        if (a.length == 0) { s.sendMessage(HELP); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(s, a);
            case "preview" -> preview(s, a);
            case "bind" -> {
                if (a.length < 2) { s.sendMessage("§c/card bind <свободный|персональный|неотчуждаемый|временный>  — привязка предмета в руке"); break; }
                set(s, new String[]{"set", "binding", a[1]});
            }
            case "group" -> group(s, a);
            case "bypass" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cТолько для игрока"); break; }
                boolean on = a.length > 1 ? a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("вкл") : !bypassOn.contains(p.getUniqueId());
                if (on) { bypassOn.add(p.getUniqueId()); p.sendMessage("§cОбход привязки ВКЛЮЧЁН: ты можешь выбрасывать и прятать любые привязанные предметы. Выключить: /card bypass off (сбросится и при выходе)."); }
                else { bypassOn.remove(p.getUniqueId()); p.sendMessage("§aОбход выключен — правила привязки действуют и для тебя."); }
            }
            case "unbind" -> {
                ItemStack it = held(s); if (it == null) break;
                ItemMeta m = it.getItemMeta(); var pd = m.getPersistentDataContainer();
                pd.set(BINDING, PersistentDataType.STRING, Binding.CONSUMABLE.id); pd.remove(OWNER); pd.remove(SIG);
                it.setItemMeta(m); reapply(s, it);
            }
            case "list" -> list(s, a.length > 1 ? a[1] : "свои");
            case "info" -> info(s);
            case "set" -> set(s, a);
            case "add" -> add(s, a);
            case "clear" -> clear(s, a);
            case "save" -> save(s, a);
            case "export" -> export(s, a.length > 1 && a[1].equalsIgnoreCase("full"));
            case "expire" -> {
                if (a.length < 2) { s.sendMessage("§c/card expire <событие>"); break; }
                expiredEvents.add(a[1]); saveState();
                s.sendMessage("§aСобытие «" + a[1] + "» наступило: предметы с таким сроком исчезнут у всех онлайн за секунду, у остальных — при входе.");
            }
            case "unexpire" -> { if (a.length > 1) { expiredEvents.remove(a[1]); saveState(); s.sendMessage("§aСобытие «" + a[1] + "» снова не наступило."); } }
            case "strip" -> strip(s, a.length > 1 && a[1].equalsIgnoreCase("all"));
            case "refresh" -> { for (Player p : Bukkit.getOnlinePlayers()) refresh(p); s.sendMessage("§aПерерисовано."); }
            case "reload" -> { reload(); for (Player p : Bukkit.getOnlinePlayers()) refresh(p); s.sendMessage("§aКарточки перечитаны: свои предметов " + config.custom.size() + ", правок ванильных " + config.vanilla.size()); }
            default -> s.sendMessage(HELP);
        }
        return true;
    }

    /** /card preview <id|МАТЕРИАЛ> [full|brief] [style=wide] [sharpness=5 damage=500 …] — показать карточку текстом (работает и из консоли) и сохранить JSON в preview/ для отрисовки. */
    private void preview(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("§c/card preview <id|МАТЕРИАЛ> [full|brief] [style=classic|wide|tech] [sharpness=5 unbreaking=3 damage=500 …]"); return; }
        boolean full = config.defaultFull;
        CardStyle style = config.defaultStyle;
        ItemStack it = create(a[1], 1, null);
        if (it == null) { s.sendMessage("§cНет такого предмета: " + a[1]); return; }
        // необязательно: зачарования и износ для примера — /card preview DIAMOND_SWORD full sharpness=5 unbreaking=3 damage=500
        ItemMeta pm0 = it.getItemMeta();
        for (int i = 2; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("full")) { full = true; continue; }
            if (a[i].equalsIgnoreCase("brief")) { full = false; continue; }
            String[] kv = a[i].split("=");
            if (kv.length != 2) continue;
            try {
                if (kv[0].equalsIgnoreCase("style")) style = CardStyle.parse(kv[1], style);
                else if (kv[0].equalsIgnoreCase("damage")) { if (pm0 instanceof org.bukkit.inventory.meta.Damageable dm) dm.setDamage(Integer.parseInt(kv[1])); }
                else {
                    var en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].toLowerCase(Locale.ROOT)));
                    if (en != null) { if (pm0 instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta es) es.addStoredEnchant(en, Integer.parseInt(kv[1]), true); else pm0.addEnchant(en, Integer.parseInt(kv[1]), true); }
                }
            } catch (Exception ignored) {}
        }
        it.setItemMeta(pm0);
        strip(it); decorate(it, null, full, false, style);
        var ser = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        var gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
        var meta = it.getItemMeta();
        s.sendMessage("§8── " + style.title + ", " + (full ? "подробно" : "кратко") + " ──");
        StringBuilder js = new StringBuilder("{\"rarity\":\"" + resolve(it.getType(), meta.getPersistentDataContainer()).rarity.id() + "\",\"style\":\"" + style.id + "\",\"name\":");
        js.append(meta.hasItemName() ? gson.serialize(meta.itemName()) : "null").append(",\"lore\":[");
        if (meta.hasItemName()) s.sendMessage("ИМЯ: " + ser.serialize(meta.itemName()));
        if (meta.hasLore()) {
            boolean first = true;
            for (var l : meta.lore()) {
                String t = plain.serialize(l);
                int w = 0; try { w = com.politichell.hud.TextWidth.of(t); } catch (Throwable ignored) {}
                s.sendMessage(ser.serialize(l) + " §8|" + w);
                js.append(first ? "" : ",").append(gson.serialize(l)); first = false;
            }
        }
        js.append("]}");
        try {
            File pd = new File(plugin.getDataFolder(), "preview"); pd.mkdirs();
            java.nio.file.Files.writeString(new File(pd, a[1].toLowerCase(Locale.ROOT) + "_" + style.id + "_" + (full ? "full" : "brief") + ".json").toPath(), js.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) { s.sendMessage("§7(JSON не записан: " + ex.getMessage() + ")"); }
    }

    private ItemStack held(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage("§cТолько для игрока"); return null; }
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) { s.sendMessage("§cВозьми предмет в руку"); return null; }
        return it;
    }

    /** Выдача: /card give <ник> <id|МАТЕРИАЛ> [кол-во] [binding=…] [rarity=…] [name=…] [desc=…] [ttl=сек] [owner=ник]  (в name/desc «_» = пробел, «||» = новая строка) */
    private void give(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage("§c/card give <ник> <id|МАТЕРИАЛ> [кол-во] [binding=персональный] [rarity=epic] [name=Новое_имя] [ttl=3600]"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage("§cИгрок не в сети: " + a[1]); return; }
        int n = 1;
        List<String> flags = new ArrayList<>();
        for (int i = 3; i < a.length; i++) {
            if (a[i].contains("=")) flags.add(a[i]);
            else try { n = Integer.parseInt(a[i]); } catch (NumberFormatException ignored) {}
        }
        ItemStack it = create(a[2], n, t);
        if (it == null) { s.sendMessage("§cНет такого предмета: " + a[2] + "  (список: /card list)"); return; }
        if (!flags.isEmpty() && !applyFlags(s, it, t, flags)) return;
        for (ItemStack left : t.getInventory().addItem(it).values()) t.getWorld().dropItemNaturally(t.getLocation(), left);
        s.sendMessage("§aВыдано: " + a[2] + " ×" + it.getAmount() + " → " + t.getName() + (flags.isEmpty() ? "" : " §7(" + String.join(", ", flags) + ")"));
    }

    private boolean applyFlags(CommandSender s, ItemStack it, Player holder, List<String> flags) {
        ItemMeta m = it.getItemMeta();
        var p = m.getPersistentDataContainer();
        for (String f : flags) {
            int i = f.indexOf('=');
            String k = f.substring(0, i).toLowerCase(Locale.ROOT), v = f.substring(i + 1).replace('_', ' ');
            switch (k) {
                case "binding", "bind" -> { Binding b = Binding.parse(v, null); if (b == null) { s.sendMessage("§cПривязка: свободный, персональный, неотчуждаемый, временный"); return false; } p.set(BINDING, PersistentDataType.STRING, b.id); }
                case "rarity" -> { if (!config.rarities.containsKey(v.toLowerCase())) { s.sendMessage("§cРедкости: " + String.join(", ", config.rarities.keySet())); return false; } p.set(RARITY, PersistentDataType.STRING, v.toLowerCase()); }
                case "name" -> p.set(TITLE, PersistentDataType.STRING, v);
                case "desc" -> p.set(DESC, PersistentDataType.STRING, v.replace("||", "\n"));
                case "icon" -> { if (!ICON_IDS.contains(v.toLowerCase())) { s.sendMessage("§cИконки: " + String.join(", ", new TreeSet<>(ICON_IDS))); return false; } p.set(ICON, PersistentDataType.STRING, v.toLowerCase()); }
                case "ttl" -> { try { p.set(EXPIRES, PersistentDataType.LONG, System.currentTimeMillis() + Long.parseLong(v.trim()) * 1000); } catch (NumberFormatException e) { s.sendMessage("§cttl — число секунд"); return false; } }
                case "event" -> p.set(EVENT, PersistentDataType.STRING, v);
                case "owner" -> p.set(OWNER, PersistentDataType.STRING, Bukkit.getOfflinePlayer(v.trim()).getUniqueId().toString());
                default -> { s.sendMessage("§cНеизвестный параметр «" + k + "». Есть: binding rarity name desc icon ttl event owner"); return false; }
            }
        }
        p.remove(SIG);
        it.setItemMeta(m);
        decorate(it, holder, holder != null ? full(holder) : config.defaultFull);
        return true;
    }

    /** /card group <шаблон|category:тип> <привязка|-> [редкость]  |  /card group list — массовое правило (groups.yml). */
    private void group(CommandSender s, String[] a) {
        File f = new File(config.dir, "groups.yml");
        if (a.length < 2 || a[1].equalsIgnoreCase("list")) {
            if (config.groups.isEmpty()) s.sendMessage("§7Массовых правил нет. Добавить: §e/card group NETHERITE_* персональный §7или §e/card group category:weapon - epic §7(файл cards/groups.yml)");
            for (var g : config.groups)
                s.sendMessage(" §e" + (g.pat() != null ? g.pat().pattern().replace("\\Q", "").replace("\\E", "") : "category:" + g.category()) + " §8→ §f" + (g.binding() == null ? "" : config.bindings.get(g.binding()).name() + " ") + (g.rarity() == null ? "" : g.rarity()));
            return;
        }
        if (a.length < 3) { s.sendMessage("§c/card group <шаблон|category:тип> <свободный|персональный|неотчуждаемый|временный|-> [редкость] [on=pickup|equip|use]"); return; }
        Binding b = a[2].equals("-") ? null : Binding.parse(a[2], null);
        if (b == null && !a[2].equals("-")) { s.sendMessage("§cПривязка: свободный, персональный, неотчуждаемый, временный (или «-», чтобы не менять)"); return; }
        String rar = null, bon = null;
        for (int i = 3; i < a.length; i++) {
            String t = a[i].toLowerCase(Locale.ROOT);
            if (t.startsWith("on=") || t.startsWith("при=")) { bon = CardConfig.bindOn(t.substring(t.indexOf('=') + 1)); if (bon == null) { s.sendMessage("§cКогда привязывать: on=pickup (при подборе) | on=equip (при надевании) | on=use (при использовании) | on=none"); return; } }
            else rar = t;
        }
        if (rar != null && !config.rarities.containsKey(rar)) { s.sendMessage("§cРедкости: " + String.join(", ", config.rarities.keySet())); return; }
        if (b == null && rar == null && bon == null) { s.sendMessage("§cУкажи привязку и/или редкость"); return; }
        StringBuilder sb = new StringBuilder("  - ");
        if (a[1].toLowerCase(Locale.ROOT).startsWith("category:")) sb.append("category: ").append(a[1].substring(9).toLowerCase(Locale.ROOT));
        else sb.append("match: \"").append(a[1].toUpperCase(Locale.ROOT)).append('"');
        if (b != null) sb.append("\n    binding: ").append(b.id.equals("consumable") ? "free" : b.id);
        if (rar != null) sb.append("\n    rarity: ").append(rar);
        if (bon != null) sb.append("\n    bind-on: ").append(bon);
        try {
            if (!f.exists()) plugin.saveResource("cards/groups.yml", false);
            String cur = java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            if (!cur.endsWith("\n")) cur += "\n";
            java.nio.file.Files.writeString(f.toPath(), cur + sb + "\n", java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { s.sendMessage("§cНе удалось записать groups.yml: " + e); return; }
        reload();
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        s.sendMessage("§aПравило добавлено и применено: §f" + a[1] + " §7→ " + (b == null ? "" : config.bindings.get(b).name() + " ") + (rar == null ? "" : rar));
    }

    private void list(CommandSender s, String what) {
        if (what.equals("все") || what.equalsIgnoreCase("all")) { s.sendMessage("§7Любой предмет игры — по названию материала, напр. §fDIAMOND_SWORD§7. Полный список: §e/card export §7(файл vanilla.yml)."); return; }
        if (config.custom.isEmpty()) { s.sendMessage("§7Своих предметов нет: добавь в cards/items.yml или /card save <id>"); return; }
        s.sendMessage("§6Свои предметы (§e" + config.custom.size() + "§6):");
        for (var cu : config.custom.values())
            s.sendMessage(" §e" + cu.id() + " §8— §f" + (cu.name() != null ? cu.name() : cu.base().name()) + " §8[" + config.rarity(cu.rarity()).name() + ", " + config.bindings.get(cu.binding()).name() + "]");
    }

    private void info(CommandSender s) {
        ItemStack it = held(s); if (it == null) return;
        ItemMeta m = it.getItemMeta();
        CardData d = resolve(it.getType(), m.getPersistentDataContainer());
        s.sendMessage("§6══ " + it.getType() + " ══");
        s.sendMessage("§7id: §f" + (d.id != null ? d.id : "— (обычный предмет)") + "   §7название: §f" + d.title);
        s.sendMessage("§7редкость: §f" + d.rarity.name() + "   §7привязка: §f" + config.bindings.get(d.binding).name() + " §7(с учётом содержимого: §f" + config.bindings.get(effective(it)).name() + "§7)");
        s.sendMessage("§7иконка: §f" + d.icon + "   §7владелец: §f" + (d.owner != null ? Bukkit.getOfflinePlayer(d.owner).getName() : "—"));
        String tm = timerText(d, System.currentTimeMillis());
        if (tm != null) s.sendMessage("§7срок: §f" + tm);
    }

    private void set(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage("§c/card set <name|rarity|binding|icon|desc|ttl|event|owner> <значение>"); return; }
        ItemStack it = held(s); if (it == null) return;
        ItemMeta m = it.getItemMeta();
        var p = m.getPersistentDataContainer();
        String v = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "name" -> p.set(TITLE, PersistentDataType.STRING, v);
            case "rarity" -> { if (!config.rarities.containsKey(v.toLowerCase())) { s.sendMessage("§cРедкости: " + String.join(", ", config.rarities.keySet())); return; } p.set(RARITY, PersistentDataType.STRING, v.toLowerCase()); }
            case "binding" -> { Binding b = Binding.parse(v, null); if (b == null) { s.sendMessage("§cПривязки: consumable, personal, locked, temporary"); return; } p.set(BINDING, PersistentDataType.STRING, b.id); }
            case "icon" -> { if (!ICON_IDS.contains(v.toLowerCase())) { s.sendMessage("§cИконки: " + String.join(", ", new TreeSet<>(ICON_IDS))); return; } p.set(ICON, PersistentDataType.STRING, v.toLowerCase()); }
            case "desc" -> p.set(DESC, PersistentDataType.STRING, v.replace("||", "\n"));
            case "ttl" -> {
                if (v.equalsIgnoreCase("off")) p.remove(EXPIRES);
                else try { p.set(EXPIRES, PersistentDataType.LONG, System.currentTimeMillis() + Long.parseLong(v) * 1000); } catch (NumberFormatException e) { s.sendMessage("§cСрок — число секунд или off"); return; }
            }
            case "event" -> { if (v.equalsIgnoreCase("off")) { p.remove(EVENT); p.remove(BOOT); } else p.set(EVENT, PersistentDataType.STRING, v); }
            case "owner" -> {
                if (v.equalsIgnoreCase("none")) p.remove(OWNER);
                else { OfflinePlayer op = Bukkit.getOfflinePlayer(v); p.set(OWNER, PersistentDataType.STRING, op.getUniqueId().toString()); }
            }
            default -> { s.sendMessage("§cПоле: name rarity binding icon desc ttl event owner"); return; }
        }
        it.setItemMeta(m);
        reapply(s, it);
    }

    private void reapply(CommandSender s, ItemStack it) {
        Player pl = (Player) s;
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().remove(SIG);       // принудительная перерисовка
        it.setItemMeta(m);
        decorate(it, pl, full(pl));
        pl.getInventory().setItemInMainHand(it);
        s.sendMessage("§aГотово.");
    }

    private void add(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage("§c/card add <desc|stat> <текст>"); return; }
        ItemStack it = held(s); if (it == null) return;
        ItemMeta m = it.getItemMeta();
        var p = m.getPersistentDataContainer();
        String v = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
        if (a[1].equalsIgnoreCase("desc")) {
            String cur = str(p, DESC);
            if (cur == null) cur = String.join("\n", resolve(it.getType(), p).desc);
            p.set(DESC, PersistentDataType.STRING, cur.isEmpty() ? v : cur + "\n" + v);
        } else if (a[1].equalsIgnoreCase("stat")) {
            String cur = str(p, STATS);
            p.set(STATS, PersistentDataType.STRING, cur == null ? v : cur + "\n" + v);
        } else { s.sendMessage("§c/card add <desc|stat> <текст>   (характеристика: «Подпись: значение»)"); return; }
        it.setItemMeta(m);
        reapply(s, it);
    }

    private void clear(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("§c/card clear <name|rarity|binding|icon|desc|stats|ttl|event|owner|all>"); return; }
        ItemStack it = held(s); if (it == null) return;
        ItemMeta m = it.getItemMeta();
        var p = m.getPersistentDataContainer();
        Map<String, NamespacedKey[]> f = Map.of("name", new NamespacedKey[]{TITLE}, "rarity", new NamespacedKey[]{RARITY}, "binding", new NamespacedKey[]{BINDING},
                "icon", new NamespacedKey[]{ICON}, "desc", new NamespacedKey[]{DESC}, "stats", new NamespacedKey[]{STATS}, "ttl", new NamespacedKey[]{EXPIRES},
                "event", new NamespacedKey[]{EVENT, BOOT}, "owner", new NamespacedKey[]{OWNER});
        String k = a[1].toLowerCase(Locale.ROOT);
        if (k.equals("all")) f.values().forEach(ks -> { for (NamespacedKey x : ks) p.remove(x); });
        else if (f.containsKey(k)) for (NamespacedKey x : f.get(k)) p.remove(x);
        else { s.sendMessage("§cНеизвестное поле"); return; }
        it.setItemMeta(m);
        reapply(s, it);
    }

    private void save(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("§c/card save <id>"); return; }
        ItemStack it = held(s); if (it == null) return;
        String id = a[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        CardData d = resolve(it.getType(), it.getItemMeta().getPersistentDataContainer());
        File f = new File(config.dir, "items.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String b = "items." + id + ".";
        y.set(b + "base", it.getType().name());
        y.set(b + "name", d.title);
        y.set(b + "rarity", d.rarity.id());
        y.set(b + "binding", d.binding.id);
        y.set(b + "icon", d.icon);
        y.set(b + "description", d.desc);
        ItemMeta m = it.getItemMeta();
        String xs = str(m.getPersistentDataContainer(), STATS);
        if (xs != null) y.set(b + "stats", List.of(xs.split("\n")));
        if (d.event != null) y.set(b + "event", d.event);
        try { y.save(f); } catch (Exception e) { s.sendMessage("§cНе удалось записать items.yml: " + e); return; }
        reload();
        s.sendMessage("§aСохранено как «" + id + "». Выдавать: §e/card give <ник> " + id + "§a. Правка — в cards/items.yml.");
    }

    private void export(CommandSender s, boolean full) {
        File f = new File(config.dir, "vanilla.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        int added = 0;
        List<Material> all = new ArrayList<>();
        for (Material m : Material.values()) if (m.isItem() && !m.isAir() && !m.isLegacy()) all.add(m);
        all.sort(Comparator.comparing(Enum::name));
        for (Material m : all) {
            String b = m.name() + ".";
            boolean any = false;
            if (!y.contains(b + "name")) { y.set(b + "name", VanillaInfo.name(config, m)); any = true; }
            if (full) {
                if (!y.contains(b + "rarity")) { y.set(b + "rarity", VanillaInfo.rarityId(config, m)); any = true; }
                if (!y.contains(b + "binding")) { y.set(b + "binding", "free"); any = true; }
                if (!y.contains(b + "icon")) { y.set(b + "icon", VanillaInfo.category(m)); any = true; }
            }
            if (!y.contains(b + "desc")) { y.set(b + "desc", VanillaInfo.desc(config, m)); any = true; }
            if (!y.contains(b + "stats")) { y.set(b + "stats", VanillaInfo.stats(m)); any = true; }
            if (any) added++;
        }
        try { y.save(f); } catch (Exception e) { s.sendMessage("§cОшибка записи: " + e); return; }
        reload();
        s.sendMessage("§aВыгружено предметов: §e" + added + " §a(уже готовые правки не тронуты). Файл: §fplugins/PoliticHellHUD/cards/vanilla.yml" + (full ? "" : " §7(название/описание/характеристики; привязку и редкость — через cards/groups.yml или §e/card export full§7)"));
    }

    private void strip(CommandSender s, boolean all) {
        if (!(s instanceof Player p)) return;
        int n = 0;
        if (all) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < 41; i++) { ItemStack it = inv.getItem(i); if (strip(it)) { inv.setItem(i, it); n++; } }
        } else { ItemStack it = p.getInventory().getItemInMainHand(); if (strip(it)) { p.getInventory().setItemInMainHand(it); n++; } }
        s.sendMessage("§aОформление снято: " + n + " ячеек. (Оно вернётся само, если система включена — выключи enabled: false в cards.yml.)");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("tip")) {
            if (a.length == 1) return List.of("style", "full", "brief");
            if (a.length == 2 && a[0].equalsIgnoreCase("style")) return List.of("classic", "wide", "tech");
            return List.of();
        }
        if (!s.hasPermission("politichellhud.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("give", "bind", "group", "preview", "list", "info", "set", "add", "clear", "save", "export", "expire", "unexpire", "strip", "refresh", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) { out.addAll(config.custom.keySet()); for (Material m : Material.values()) if (m.isItem() && !m.isLegacy() && !m.isAir()) out.add(m.name()); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("bind")) out.addAll(List.of("свободный", "персональный", "неотчуждаемый", "временный"));
        else if (a.length >= 4 && a[0].equalsIgnoreCase("give")) out.addAll(List.of("binding=персональный", "binding=неотчуждаемый", "binding=временный", "rarity=epic", "rarity=legendary", "name=", "ttl=3600", "owner="));
        else if (a.length == 3 && a[0].equalsIgnoreCase("group")) out.addAll(List.of("-", "свободный", "персональный", "неотчуждаемый", "временный"));
        else if (a.length == 4 && a[0].equalsIgnoreCase("group")) out.addAll(config.rarities.keySet());
        else if (a.length == 2 && a[0].equalsIgnoreCase("group")) out.addAll(List.of("list", "NETHERITE_*", "category:weapon", "category:armor", "category:tool"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("set")) out.addAll(List.of("name", "rarity", "binding", "icon", "desc", "ttl", "event", "owner"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("add")) out.addAll(List.of("desc", "stat"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("clear")) out.addAll(List.of("name", "rarity", "binding", "icon", "desc", "stats", "ttl", "event", "owner", "all"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "rarity" -> out.addAll(config.rarities.keySet());
                case "binding" -> out.addAll(List.of("consumable", "personal", "locked", "temporary"));
                case "icon" -> out.addAll(ICON_IDS);
                case "ttl", "event", "owner" -> out.add(a[1].equalsIgnoreCase("owner") ? "none" : "off");
                default -> {}
            }
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
