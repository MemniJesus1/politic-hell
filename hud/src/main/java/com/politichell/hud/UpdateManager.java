package com.politichell.hud;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Обновление ВСЕГО одной командой.  /hudupdate [всё|пак|таб|конфиги]
 *   пак      — переотправить игрокам актуальный ресурспак (он внутри jar, всегда нужной версии)
 *   таб      — записать в plugins/TAB актуальные config.yml, groups.yml и animations.yml (старые → plugins/PoliticHellHUD/backup/<дата>/) и сделать /tab reload
 *   конфиги  — вернуть конфиги плагина (hud, compass, gameplay, economy, ranks, config) к новым — СТАРЫЕ СОХРАНЯЮТСЯ в backup (твои настройки пропадут из рабочих файлов!)
 *   меню     — русские меню DeluxeMenus (/menu /gorod /strana /uchastok) → plugins/DeluxeMenus/gui_menus (старые → backup) + dm reload
 *   всё      — пак + таб + меню   (по умолчанию)
 * Сам собой выполняется при первом запуске новой версии плагина (update.auto-apply в config.yml): пак + таб.
 */
public class UpdateManager {
    private final PoliticHellHud plugin;
    private final File marker;

    public UpdateManager(PoliticHellHud plugin) {
        this.plugin = plugin;
        this.marker = new File(plugin.getDataFolder(), "installed-version.txt");
    }

    private String version() { return plugin.getDescription().getVersion(); }

    /** Вызывается при старте: новая версия плагина → автоматически обновить таб (пак отправляется отдельно при старте). */
    public void onStartup() {
        migrateOldConfig();
        String old = "";
        try { if (marker.exists()) old = Files.readString(marker.toPath()).trim(); } catch (Exception ignored) {}
        if (old.equals(version())) return;
        try { Files.writeString(marker.toPath(), version()); } catch (Exception ignored) {}
        if (!plugin.getConfig().getBoolean("update.auto-apply", true)) {
            plugin.getLogger().info("Версия плагина изменилась (" + (old.isEmpty() ? "первый запуск" : old) + " → " + version() + "). Обнови таб командой /hudupdate");
            return;
        }
        plugin.getLogger().info("Новая версия " + version() + " (было: " + (old.isEmpty() ? "первый запуск" : old) + ") — обновляю TAB-конфиги автоматически");
        Bukkit.getScheduler().runTaskLater(plugin, () -> { installTab(null); installMenus(null); }, 100L);
    }

    /** Старый config.yml (до 1.8.2: без pack.source) выключал пак и требовал ручной раздачи — заменяем новым, старый → backup. */
    private void migrateOldConfig() {
        try {
            File f = new File(plugin.getDataFolder(), "config.yml");
            if (!f.exists()) return;
            if (org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f).isSet("pack.source")) return;
            File bk = backupDir();
            Files.move(f.toPath(), new File(bk, "config.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.saveDefaultConfig();
            plugin.reloadConfig();
            plugin.getLogger().info("Старый config.yml заменён новым (раздача пака включена). Старый: " + bk.getPath());
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось обновить config.yml: " + e);
        }
    }

    public void command(CommandSender s, String[] a) {
        String what = a.length > 0 ? a[0].toLowerCase() : "всё";
        switch (what) {
            case "всё", "все", "all" -> { s.sendMessage("§e[1/2] Ресурспак..."); plugin.packs().reloadAndSend(() -> { s.sendMessage("§a  пак: " + plugin.packs().status()); installTab(s); installMenus(s); }); }
            case "пак", "pack" -> plugin.packs().reloadAndSend(() -> s.sendMessage("§aПак: " + plugin.packs().status()));
            case "таб", "tab" -> installTab(s);
            case "меню", "menu", "menus" -> installMenus(s);
            case "конфиги", "configs" -> resetConfigs(s);
            default -> s.sendMessage("§c/hudupdate [всё|пак|таб|меню|конфиги]");
        }
    }

    public static List<String> options() { return List.of("всё", "пак", "таб", "меню", "конфиги"); }

    private File backupDir() {
        File d = new File(plugin.getDataFolder(), "backup/" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
        d.mkdirs();
        return d;
    }

    private void say(CommandSender s, String m) { plugin.getLogger().info(m.replaceAll("§.", "")); if (s != null) s.sendMessage(m); }

    /** Записать в plugins/TAB наши config.yml, groups.yml и animations.yml (анимированная рамка шапки). */
    public void installTab(CommandSender s) {
        var tab = Bukkit.getPluginManager().getPlugin("TAB");
        if (tab == null) { say(s, "§c[2/2] Плагин TAB не найден — пропускаю (поставь TAB и повтори /hudupdate таб)"); return; }
        try {
            File dir = tab.getDataFolder(), bk = backupDir();
            for (String n : new String[]{"config.yml", "groups.yml", "animations.yml"}) {
                File f = new File(dir, n);
                if (f.exists()) Files.copy(f.toPath(), new File(bk, "TAB_" + n).toPath(), StandardCopyOption.REPLACE_EXISTING);
                try (InputStream in = plugin.getResource("tab/" + n)) {
                    if (in == null) throw new IllegalStateException("в jar нет tab/" + n);
                    Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            say(s, "§a[2/2] TAB: config.yml, groups.yml и animations.yml обновлены (старые — в " + bk.getPath().replace('\\', '/') + ")");
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab reload"), 20L);
            say(s, "§aВыполняю /tab reload. Готово.");
        } catch (Exception e) {
            say(s, "§cTAB не обновлён: " + e);
        }
    }

    /** Меню DeluxeMenus: /menu, /gorod, /strana, /uchastok. Файлы лежат в jar (menus/). */
    public void installMenus(CommandSender s) {
        // PHASE0: старое сундучное меню отключено (движок Style1ScreenHub / PoliticHellEngine).
        if (s != null) say(s, "§6[HUD] Меню старого типа (DeluxeMenus) отключены в Фазе 0. Новый движок: /menu, /arsenal, /crates, /strana (PoliticHellEngine).");
        if (false) { // ЛЕГАСИ: прошлый код установки DeluxeMenus-меню (до Фазы 1)
            var dm = Bukkit.getPluginManager().getPlugin("DeluxeMenus");
            if (dm == null) { say(s, "§eDeluxeMenus не найден — меню пропущены (поставь DeluxeMenus и повтори /hudupdate меню)"); return; }
            try {
                File dir = new File(dm.getDataFolder(), "gui_menus"), bk = backupDir();
                dir.mkdirs();
                String index;
                try (InputStream in = plugin.getResource("menus/index.txt")) {
                    if (in == null) throw new IllegalStateException("в jar нет menus/index.txt");
                    index = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                boolean fresh = false;
                for (String n : index.split("\\s+")) {
                    if (n.isBlank()) continue;
                    File f = new File(dir, n + ".yml");
                    if (f.exists()) Files.copy(f.toPath(), new File(bk, "DM_" + n + ".yml").toPath(), StandardCopyOption.REPLACE_EXISTING); else fresh = true;
                    try (InputStream in = plugin.getResource("menus/" + n + ".yml")) {
                        if (in == null) throw new IllegalStateException("в jar нет menus/" + n + ".yml");
                        Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                for (String n : index.split("\\s+")) if (!n.isBlank() && registerInDm(new File(dm.getDataFolder(), "config.yml"), n, bk)) fresh = true;
                say(s, "§aМеню DeluxeMenus обновлены: /menu  /gorod  /strana  /uchastok (старые — в " + bk.getPath().replace('\\', '/') + ")");
                Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dm reload"), 20L);
                if (fresh) say(s, "§eКоманды меню регистрируются при старте сервера — если /menu не работает, перезапусти сервер один раз.");
            } catch (Exception e) {
                say(s, "§cМеню не обновлены: " + e);
            }
        }
    }

    /** DeluxeMenus грузит только меню, перечисленные в его config.yml → gui_menus. Дописываем строки текстом (комментарии файла сохраняются). */
    private boolean registerInDm(File cfg, String name, File bk) throws Exception {
        if (!cfg.exists()) return false;
        java.util.List<String> lines = new java.util.ArrayList<>(Files.readAllLines(cfg.toPath(), java.nio.charset.StandardCharsets.UTF_8));
        for (String l : lines) if (l.trim().equals("file: " + name + ".yml")) return false;        // уже есть
        File copy = new File(bk, "DM_config.yml");
        if (!copy.exists()) Files.copy(cfg.toPath(), copy.toPath());
        int at = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).matches("^gui_menus:\\s*(#.*)?$")) { at = i; break; }
        java.util.List<String> add = java.util.List.of("  " + name + ":", "    file: " + name + ".yml");
        if (at >= 0) lines.addAll(at + 1, add);
        else { lines.add(""); lines.add("gui_menus:"); lines.addAll(add); }
        Files.write(cfg.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
        return true;
    }

    private void resetConfigs(CommandSender s) {
        try {
            File bk = backupDir();
            for (String n : new String[]{"config", "hud", "compass", "gameplay", "economy", "ranks"}) {
                File f = new File(plugin.getDataFolder(), n + ".yml");
                if (f.exists()) { Files.move(f.toPath(), new File(bk, n + ".yml").toPath(), StandardCopyOption.REPLACE_EXISTING); }
            }
            plugin.saveDefaultConfig();
            plugin.reloadConfig();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hudreload");
            say(s, "§aКонфиги возвращены к актуальным. Твои старые — в " + bk.getPath().replace('\\', '/'));
        } catch (Exception e) {
            say(s, "§cНе удалось: " + e);
        }
    }
}
