package com.politichell.hud;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Настоящая экономика:
 *  • МОНЕТЫ (coins) — через Vault: читаем/меняем баланс у ЛЮБОГО плагина-экономики, который зарегистрирован в Vault
 *    (Vault 2.0, EssentialsX, XConomy, CoinsEngine…). Все остальные плагины (магазины, Towny, налоги) видят ТЕ ЖЕ деньги.
 *  • ДОНАТ (donate) — отдельная валюта: PlayerPoints (если установлен и выбран в economy.yml) или свой файл data.yml.
 * Vault умеет только одну валюту как основную — поэтому донат вынесен отдельно.
 *
 * Потоки: TAB/PlaceholderAPI зовут get() не из главного потока. Из чужого потока отдаём кэш (он обновляется
 * на главном потоке каждые refresh-ticks), чтобы не дёргать Vault-плагин из асинхронного потока.
 */
public class EconomyBridge implements CurrencyProvider, Listener {
    private final PoliticHellHud plugin;
    private final FileCurrencyProvider file;

    private String mode;                 // auto | vault | file
    private String donateSource;         // file | playerpoints
    private volatile Economy eco;        // null = Vault-экономики нет
    private volatile String ecoDescr = "нет";
    private Object ppApi; private Method ppLook, ppGive, ppTake, ppSet;

    private record Cached(long value, long at) {}
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private long ttlMs = 400;

    public EconomyBridge(PoliticHellHud plugin, FileCurrencyProvider file) {
        this.plugin = plugin;
        this.file = file;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Читает economy.yml и заново ищет Vault-экономику / PlayerPoints. Вызывается при старте и в /hudreload. */
    public void reload() {
        var c = plugin.cfg("economy");
        mode = c.getString("mode", "auto").toLowerCase();
        donateSource = c.getString("donate.source", "auto").toLowerCase();
        if (donateSource.equals("auto")) donateSource = Bukkit.getPluginManager().getPlugin("PlayerPoints") != null ? "playerpoints" : "file";
        ttlMs = Math.max(50, c.getLong("refresh-ticks", 10) * 50);
        cache.clear();
        resolve();
        resolvePlayerPoints();
    }

    // ── поиск провайдеров ────────────────────────────────────────────────────
    private void resolve() {
        try { resolve0(); } catch (Throwable t) { eco = null; ecoDescr = "ошибка: " + t; }
    }

    private void resolve0() {
        if (mode.equals("file")) { eco = null; ecoDescr = "режим file (Vault не используется)"; return; }
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) { eco = null; ecoDescr = "плагин Vault не найден"; return; }
        var all = Bukkit.getServicesManager().getRegistrations(Economy.class);
        if (all.isEmpty()) { eco = null; ecoDescr = "Vault есть, но плагина-экономики нет"; return; }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);   // победитель по приоритету
        String want = plugin.cfg("economy").getString("vault-provider", "").trim();
        if (!want.isEmpty()) {                                    // админ выбрал экономику по имени плагина
            for (var r : all) if (r.getPlugin().getName().equalsIgnoreCase(want) || r.getProvider().getName().equalsIgnoreCase(want)) { rsp = r; break; }
        }
        eco = rsp.getProvider();
        StringBuilder sb = new StringBuilder(rsp.getPlugin().getName() + " / " + eco.getName());
        if (all.size() > 1) {
            sb.append("  [!] экономик в Vault несколько:");
            for (var r : all) sb.append(" ").append(r.getPlugin().getName()).append("(").append(r.getPriority()).append(")");
            sb.append(" — выбери одну: economy.yml → vault-provider");
        }
        ecoDescr = sb.toString();
    }

    private void resolvePlayerPoints() {
        ppApi = null;
        if (!donateSource.equals("playerpoints")) return;
        try {
            var pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pp == null) throw new IllegalStateException("плагин PlayerPoints не найден");
            ppApi = pp.getClass().getMethod("getAPI").invoke(pp);
            Class<?> k = ppApi.getClass();
            ppLook = k.getMethod("look", UUID.class);
            ppGive = k.getMethod("give", UUID.class, int.class);
            ppTake = k.getMethod("take", UUID.class, int.class);
            ppSet = k.getMethod("set", UUID.class, int.class);
        } catch (Throwable t) {
            ppApi = null;
            plugin.getLogger().warning("Донат: PlayerPoints недоступен (" + t.getMessage() + ") — используется файл data.yml");
        }
    }

    @EventHandler public void onReg(ServiceRegisterEvent e) { if (isEco(e.getProvider().getService())) { resolve(); report(); } }
    @EventHandler public void onUnreg(ServiceUnregisterEvent e) { if (isEco(e.getProvider().getService())) resolve(); }
    private static boolean isEco(Class<?> k) { return k.getName().equals("net.milkbowl.vault.economy.Economy"); }   // без загрузки класса Vault

    public boolean usesVault() { return eco != null; }

    /** Строка для /hudeco info и лога. */
    public String describe() {
        return "монеты: " + (eco != null ? "Vault → " + ecoDescr : "файл data.yml (" + ecoDescr + ")")
                + " | донат: " + (ppApi != null ? "PlayerPoints" : "файл data.yml");
    }

    /** Пишет в лог итог (вызывается после полной загрузки сервера). */
    public void report() {
        plugin.getLogger().info("Экономика: " + describe());
        if (eco == null && !mode.equals("file"))
            plugin.getLogger().warning("Vault-экономика не найдена — монеты временно хранятся в data.yml плагина и НЕ видны другим плагинам. Поставь плагин-экономику (см. economy.yml).");
    }

    // ── CurrencyProvider ─────────────────────────────────────────────────────
    @Override
    public long get(UUID id, Currency c) {
        if (c == Currency.COINS && eco != null) return coins(id);
        if (c == Currency.DONATE && ppApi != null) return donate(id);
        return file.get(id, c);
    }

    @Override
    public void set(UUID id, Currency c, long amount) {
        if (c == Currency.COINS && eco != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            double delta = amount - eco.getBalance(op);
            move(op, delta);
            cache.remove(id);
        } else if (c == Currency.DONATE && ppApi != null) {
            try { ppSet.invoke(ppApi, id, (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount))); }
            catch (Throwable t) { plugin.getLogger().warning("PlayerPoints.set: " + t); }
            cache.remove(id);
        } else file.set(id, c, amount);
    }

    @Override
    public void add(UUID id, Currency c, long delta) {
        if (c == Currency.COINS && eco != null) { move(Bukkit.getOfflinePlayer(id), delta); cache.remove(id); }
        else if (c == Currency.DONATE && ppApi != null) {
            try {
                if (delta >= 0) ppGive.invoke(ppApi, id, (int) Math.min(Integer.MAX_VALUE, delta));
                else ppTake.invoke(ppApi, id, (int) Math.min(Integer.MAX_VALUE, -delta));
            } catch (Throwable t) { plugin.getLogger().warning("PlayerPoints.give/take: " + t); }
            cache.remove(id);
        } else file.add(id, c, delta);
    }

    @Override public void save() { file.save(); }

    // ── монеты через Vault ───────────────────────────────────────────────────
    private void move(OfflinePlayer op, double delta) {
        Economy e = eco;
        if (e == null || delta == 0) return;
        if (!e.hasAccount(op)) e.createPlayerAccount(op);
        EconomyResponse r;
        if (delta > 0) r = e.depositPlayer(op, delta);
        else r = e.withdrawPlayer(op, Math.min(-delta, Math.max(0, e.getBalance(op))));   // не уходим ниже нуля
        if (!r.transactionSuccess())
            plugin.getLogger().warning("Vault: операция с " + op.getName() + " не прошла: " + r.errorMessage);
    }

    private long coins(UUID id) {
        long now = System.currentTimeMillis();
        Cached cd = cache.get(id);
        boolean main = Bukkit.isPrimaryThread();
        if (cd != null && (now - cd.at < ttlMs || !main)) return cd.value;
        if (!main) return 0;                                   // из чужого потока и кэша ещё нет — подождём обновления
        long v = read(id);
        cache.put(id, new Cached(v, now));
        return v;
    }

    private long read(UUID id) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            Economy e = eco;
            if (!e.hasAccount(op)) e.createPlayerAccount(op);
            return (long) Math.floor(e.getBalance(op));
        } catch (Throwable t) { return 0; }
    }

    private long donate(UUID id) {
        long now = System.currentTimeMillis();
        UUID key = new UUID(id.getMostSignificantBits() ^ 0x5DL, id.getLeastSignificantBits());   // отдельный ключ кэша для доната
        Cached cd = cache.get(key);
        boolean main = Bukkit.isPrimaryThread();
        if (cd != null && (now - cd.at < ttlMs || !main)) return cd.value;
        if (!main) return 0;
        long v = 0;
        try { v = ((Number) ppLook.invoke(ppApi, id)).longValue(); } catch (Throwable ignored) {}
        cache.put(key, new Cached(v, now));
        return v;
    }

    /** Раз в refresh-ticks обновляет кэш для онлайн-игроков (главный поток) — чтобы таб и скорборд видели свежие цифры. */
    public void refreshOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (eco != null) { cache.remove(p.getUniqueId()); coins(p.getUniqueId()); }
            if (ppApi != null) { UUID id = p.getUniqueId(); donate(id); }
        }
    }
}
