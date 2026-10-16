package com.politichell.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * ЕДИНСТВЕННЫЙ ресурспак — всегда актуальный: он лежит ВНУТРИ jar плагина (PoliticHellHUD-pack.zip) и раздаётся игрокам
 * автоматически (встроенный веб-сервер PackServer). Обновил плагин → пак обновился сам. /hudpack — отправить заново всем.
 * Источник пака (pack.source в config.yml): embedded (по умолчанию) | file (свой zip на диске) | url (внешняя ссылка).
 * Не используй вместе с resource-pack= в server.properties (там оставь пусто).
 */
public class PackManager {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("politichell-hud-pack".getBytes());
    private static final String EMBEDDED = "PoliticHellHUD-pack.zip";
    private final PoliticHellHud plugin;
    private final PackServer server = new PackServer();
    private volatile byte[] hash;
    private volatile String hex = "";
    private volatile String externalUrl;      // если источник — внешняя ссылка
    private volatile String status = "ещё не готов";
    private volatile boolean hosting;

    public PackManager(PoliticHellHud plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("pack.enabled", true); }
    public String status() { return status; }
    public void shutdown() { server.stop(); }

    private String source() { return plugin.getConfig().getString("pack.source", "embedded").toLowerCase(); }

    /** Прочитать пак, посчитать sha1, поднять раздачу и отправить всем онлайн-игрокам. */
    public void reloadAndSend(Runnable done) {
        if (!enabled()) { status = "выключен (pack.enabled: false)"; if (done != null) done.run(); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] data;
                boolean merged = false;
                String src = source();
                externalUrl = null;
                if (src.equals("url")) {
                    String u = plugin.getConfig().getString("pack.url", "");
                    try (InputStream in = URI.create(u).toURL().openStream()) { data = in.readAllBytes(); }
                    externalUrl = u + (u.contains("?") ? "&" : "?") + "v=" + hexOf(data).substring(0, 10);
                } else if (src.equals("file")) {
                    data = Files.readAllBytes(Path.of(plugin.getConfig().getString("pack.file", "")));
                } else {
                    try (InputStream in = plugin.getResource(EMBEDDED)) {
                        if (in == null) throw new IllegalStateException("в jar нет " + EMBEDDED + " (собери плагин через tools/release.py)");
                        data = in.readAllBytes();
                    }
                }
                if (!src.equals("url") && plugin.getConfig().getBoolean("pack.merge-nminimap", true)) {
                    java.io.File nmZip = new java.io.File(plugin.getDataFolder().getParentFile(), "NMinimap/built-pack.zip");
                    if (nmZip.isFile()) {
                        try {
                            data = PackMerge.merge(data, Files.readAllBytes(nmZip.toPath()));
                            merged = true;
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Не удалось добавить пак карты NMinimap в общий пак: " + ex + " — игрокам уйдёт пак без карты.");
                        }
                    }
                }
                if (src.equals("embedded")) {   // копия для ручной проверки/отладки: plugins/PoliticHellHUD/PoliticHellHUD-pack.zip (перезаписывается)
                    try { Files.write(plugin.getDataFolder().toPath().resolve(EMBEDDED), data); } catch (Exception ignored) {}
                }
                byte[] h = MessageDigest.getInstance("SHA-1").digest(data);
                String hx = HexFormat.of().formatHex(h);
                boolean host = externalUrl == null;
                if (host) {
                    int port = plugin.getConfig().getInt("pack.port", 8765);
                    try { server.start(port, data); hosting = true; }
                    catch (Exception e) { hosting = false; throw new IllegalStateException("не удалось открыть порт " + port + " (занят?): " + e.getMessage()); }
                }
                final byte[] fdata = data;
                final boolean fmerged = merged;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    hash = h; hex = hx;
                    status = "sha1 " + hx.substring(0, 10) + ", " + fdata.length / 1024 + " КБ, источник: " + src + (fmerged ? " + карта NMinimap" : "")
                            + (host ? ", раздача: порт " + plugin.getConfig().getInt("pack.port", 8765) : ", внешняя ссылка");
                    for (Player p : Bukkit.getOnlinePlayers()) send(p);
                    plugin.getLogger().info("Ресурспак готов: " + status);
                    if (done != null) done.run();
                });
            } catch (Exception ex) {
                status = "ОШИБКА: " + ex.getMessage();
                plugin.getLogger().warning("Не удалось подготовить ресурспак: " + ex);
                if (done != null) Bukkit.getScheduler().runTask(plugin, done);
            }
        });
    }

    private static String hexOf(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)); } catch (Exception e) { return "0000000000"; }
    }

    /** Ссылка для конкретного игрока. address: auto = тот адрес, по которому игрок зашёл на сервер (localhost для тебя, внешний IP/домен для друзей). */
    private String urlFor(Player p) {
        if (externalUrl != null) return externalUrl;
        String addr = plugin.getConfig().getString("pack.address", "auto");
        if (addr.equalsIgnoreCase("auto")) {
            addr = "127.0.0.1";
            try { var vh = p.getVirtualHost(); if (vh != null && vh.getHostString() != null && !vh.getHostString().isBlank()) addr = vh.getHostString(); } catch (Throwable ignored) {}
        }
        return "http://" + addr + ":" + plugin.getConfig().getInt("pack.port", 8765) + "/PoliticHellHUD.zip?v=" + hex.substring(0, Math.min(10, hex.length()));
    }

    public void send(Player p) {
        if (!enabled() || hash == null) return;
        Component prompt = MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("pack.prompt", "<gold>PoliticHell</gold> — ресурспак интерфейса"));
        p.setResourcePack(PACK_ID, urlFor(p), hash, prompt, plugin.getConfig().getBoolean("pack.required", false));
    }
}
