package me.politichell.engine;

import me.politichell.engine.map.TownyTerritoryBridge;
import me.politichell.engine.ui.Style1ScreenHub;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * POLITICHELL ENGINE 1.16.0 (Фаза 0).
 *
 * Модули:
 *   Style1ScreenHub      — движок экранных 3D-меню (Обложка, Арсенал, Экипировка, Ящики, Штаб).
 *   TownyTerritoryBridge — чанк → держава → цвета дипломатии (данные для HOI4-карты).
 *
 * Хранение: plugins/PoliticHellEngine/engine-data.yml
 *   - pity счётчики кейсов (анти-дюп / гарантии);
 *   -╗ researched узлы древа;
 *   - тест-экономика secoins живёт в PDC игрока (5200 ₴ по умолчанию) — позже сольём с EconomyBridge.
 */
public final class PoliticHellEngine extends JavaPlugin implements Listener {

    private static PoliticHellEngine instance;

    private Style1ScreenHub screenHub;
    private TownyTerritoryBridge territoryBridge;

    private NamespacedKey secoinsKey;
    private File dataFile;
    private YamlConfiguration data;

    private final Map<String, Integer> pity = new HashMap<>();
    private final Set<String> researched = new HashSet<>();

    public static PoliticHellEngine get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        secoinsKey = new NamespacedKey(this, "ph_secoins");

        dataFile = new File(getDataFolder(), "engine-data.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        distinctLoad();

        territoryBridge = new TownyTerritoryBridge(this);
        screenHub = new Style1ScreenHub(this);
        screenHub.purgeOrphans();

        for (String cmd : new String[]{"menu", "arsenal", "equip", "crates", "strana"}) {
            org.bukkit.command.PluginCommand c = getCommand(cmd);
            if (c != null) c.setExecutor(screenHub);
        }
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("[PoliticHellEngine] 1.16.0 on: Стиль 1.1 · Towny="
                + (territoryBridge.isHooked() ? "hooked" : "not found (wilderness)"));
    }

    @Override
    public void onDisable() {
        if (screenHub != null) screenHub.closeAll();
        persist();
        instance = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String lc = label.toLowerCase(Locale.ROOT);
        if (lc.equals("phengine")) {
            if (args.length == 0) {
                sender.sendMessage("[PoliticHellEngine] /phengine reload|reset|help");
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> {
                    purgeData();
                    loadFromDisk();
                    sender.sendMessage("[PoliticHellEngine] data reloaded.");
                }
                case "reset" -> {
                    purgeData();
                    persist();
                    sender.sendMessage("[PoliticHellEngine] tree + pity сброшены.");
                }
                default -> sender.sendMessage(
                        "[PoliticHellEngine] /phengine reload|reset|help");
            }
            return true;
        }
        if (lc.equals("phcoins")) {
            if (args.length != 3) {
                sender.sendMessage("[PoliticHellEngine] /phcoins <add|set> <player> <amount>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("[PoliticHellEngine] игрок не в сети: " + args[1]);
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("[PoliticHellEngine] не число: " + args[2]);
                return true;
            }
            if (args[0].equalsIgnoreCase("set")) {
                setCoins(target, amount);
            } else {
                addCoins(target, amount);
            }
            sender.sendMessage("[PoliticHellEngine] "
                    + target.getName() + " → " + String.format(Locale.US, "%,.0f", getCoins(target)) + " ₴");
            return true;
        }
        return false;
    }

    // ────────────────────────── события ──────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        var pdc = p.getPersistentDataContainer();
        if (!pdc.has(secoinsKey, PersistentDataType.DOUBLE)) {
            pdc.set(secoinsKey, PersistentDataType.DOUBLE, 5000.0);
        }
    }

    // TownyTerritoryBridge / ScreenHub события регистрируются внутри самих классов.

    // ────────────────────────── экономика (тест) ──────────────────────────

    public double getCoins(Player p) {
        Double v = p.getPersistentDataContainer().get(secoinsKey, PersistentDataType.DOUBLE);
        return v == null ? 0.0 : v;
    }

    public void addCoins(Player p, double delta) {
        setCoins(p, getCoins(p) + delta);
    }

    public void setCoins(Player p, double value) {
        p.getPersistentDataContainer().set(secoinsKey, PersistentDataType.DOUBLE, Math.max(0.0, value));
    }

    // ────────────────────────── pity кейсов ──────────────────────────

    public int getPity(UUID pid, String crateId) {
        return pity.getOrDefault(pid + ":" + crateId, 0);
    }

    public void setPity(UUID pid, String crateId, int value) {
        pity.put(pid + ":" + crateId, Math.max(0, value));
        persist();
    }

    public boolean isResearched(String nodeId) {
        return researched.contains(nodeId);
    }

    public void setResearched(String nodeId) {
        researched.add(nodeId);
        persist();
    }

    public Style1ScreenHub screenHub() {
        return screenHub;
    }

    public TownyTerritoryBridge territoryBridge() {
        return territoryBridge;
    }

    // ────────────────────────── PDC/data I/O ──────────────────────────

    private void loadFromDisk() {
        for (String k : data.getKeys(false)) {
            if (k.equals("researched")) {
                for (String id : data.getStringList(k)) researched.add(id);
            } else if (k.equals("pity")) {
                var sec = data.getConfigurationSection("pity");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        pity.put(key, sec.getInt(key, 0));
                    }
                }
            }
        }
    }

    private void persist() {
        if (data == null) data = YamlConfiguration.loadConfiguration(dataFile);
        data.set("researched", researched.stream().toList());
        pity.forEach((k, v) -> data.set("pity." + k, v));
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("[PoliticHellEngine] data save failed: " + ex.getMessage());
        }
    }

    private void purgeData() {
        pity.clear();
        researched.clear();
    }

    private void distinctLoad() {
        loadFromDisk();
    }
}
