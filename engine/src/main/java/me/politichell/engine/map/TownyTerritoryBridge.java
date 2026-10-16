package me.politichell.engine.map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Мост к Towny (и позже SiegeWar) для политической карты HOI4-типа.
 *
 * Работает через рефлексию: если Towny не установлен — все методы возвращают null/0
 * и движок продолжает работать как «дикая земля» (wilderness).
 *
 * APIs:
 *   getClaimAt(world, chunkX, chunkZ) → ChunkClaim(town, nation, key, capital, outpost, siege)
 *   isBorder(world, chunkX, chunkZ)   → есть ли государственная граница рядом
 *   mapColorFor(viewerId, world, cx, cz, layerMode) → 0xRRGGBB в Стиль 1.1
 *   nationNameOf(uuid)                → название державы игрока (или null)
 *   providePois(viewer)               → markers для компаса/карты: столицы/фронты
 *
 * Правило производительности: кеш чанков чистится каждые 5 секунд,
 * НЕ дёргать Towny API каждый тик.
 */
public final class TownyTerritoryBridge {

    public record ChunkClaim(String townName, String nationName, String nationKey,
                             boolean isCapital, boolean isOutpost, boolean underSiege) {}

    public enum MapMode { ALL, OWN, RELATIONS, OFF }

    private static final int COLOR_SAME    = 0x2ECC71;
    private static final int COLOR_ALLY    = 0x3498DB;
    private static final int COLOR_ENEMY   = 0xE74C3C;
    private static final int COLOR_NEUTRAL = 0xD4AF37;

    private final me.politichell.engine.PoliticHellEngine plugin;
    private final Map<Long, ChunkClaim> chunkCache = new ConcurrentHashMap<>();
    private final Map<Long, Long> cacheStamp  = new ConcurrentHashMap<>();

    private Object townyApi;
    private Method mGetTownBlock, mHasTown, mGetTownOrNull, mHasNation, mGetNationOrNull,
                   mName, mIsHomeBlock, mIsOutpost;
    private Method mResidentByUuid, mHasTownResident, mTownResident, mHasNationResident, mNationResident;
    private boolean hooked;
    private long lastPurge = System.currentTimeMillis();

    public TownyTerritoryBridge(me.politichell.engine.PoliticHellEngine plugin) {
        this.plugin = plugin;
        hook();
    }

    private void hook() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Towny") == null) {
                plugin.getLogger().info("[PoliticHellEngine] Towny not found — running in WILDERNESS mode.");
                return;
            }
            Class<?> api = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            townyApi = api.getMethod("getInstance").invoke(null);
            mGetTownBlock = api.getMethod("getTownBlock", Location.class);

            Class<?> tbClass = Class.forName("com.palmergames.bukkit.towny.object.TownBlock");
            mHasTown = tbClass.getMethod("hasTown");
            mGetTownOrNull = tbClass.getMethod("getTownOrNull");
            mIsHomeBlock = tbClass.getMethod("isHomeBlock");
            mIsOutpost  = tbClass.getMethod("isOutpost");

            mResidentByUuid = api.getMethod("getResident", UUID.class);
            mHasTownResident = Class.forName("com.palmergames.bukkit.towny.object.Resident").getMethod("hasTown");
            mTownResident = Class.forName("com.palmergames.bukkit.towny.object.Resident").getMethod("getTownOrNull");
            mHasNationResident = null; // find via town
            hooked = true;
            plugin.getLogger().info("[PoliticHellEngine] TownyTerritoryBridge hooked successfully.");
        } catch (Throwable t) {
            hooked = false;
            plugin.getLogger().warning("[PoliticHellEngine] Towny hook failed: " + t.getMessage());
        }
    }

    public boolean isHooked() { return hooked; }

    private static long key(World w, int cx, int cz) {
        return ((long) w.getName().hashCode() << 40) ^ ((long) cx << 20) ^ (cz & 0xFFFFFL);
    }

    /** Владелец чанка (кеш 5 сек). */
    public ChunkClaim getClaimAt(World world, int chunkX, int chunkZ) {
        if (!hooked || world == null) return null;
        long k = key(world, chunkX, chunkZ);
        Long stamp = cacheStamp.get(k);
        long now = System.currentTimeMillis();
        if (stamp != null && now - stamp <= 5000L) return chunkCache.get(k);

        purgeIfOld(now);
        ChunkClaim claim = resolve(world, chunkX, chunkZ);
        chunkCache.put(k, claim);
        cacheStamp.put(k, now);
        return claim;
    }

    private void purgeIfOld(long now) {
        if (now - lastPurge < 5000L) return;
        lastPurge = now;
        cacheStamp.entrySet().removeIf(e -> now - e.getValue() > 30000L);
        chunkCache.keySet().retainAll(cacheStamp.keySet());
    }

    private ChunkClaim resolve(World world, int chunkX, int chunkZ) {
        try {
            Location loc = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
            Object tb = mGetTownBlock.invoke(townyApi, loc);
            if (tb == null) return null;
            if (!(Boolean) mHasTown.invoke(tb)) return null;

            Object town = mGetTownOrNull.invoke(tb);
            if (town == null) return null;

            Method tName = town.getClass().getMethod("getName");
            String townName = (String) tName.invoke(town);

            boolean capital = (Boolean) mIsHomeBlock.invoke(tb);
            boolean outpost = (Boolean) mIsOutpost.invoke(tb);

            String nationName = townName;
            boolean nationCap = false;
            Method mHasNation = town.getClass().getMethod("hasNation");
            if ((Boolean) mHasNation.invoke(town)) {
                Object nation = town.getClass().getMethod("getNationOrNull").invoke(town);
                if (nation != null) {
                    nationName = (String) nation.getClass().getMethod("getName").invoke(nation);
                    Object cap = nation.getClass().getMethod("getCapital").invoke(nation);
                    nationCap = town.equals(cap);
                }
            }
            String key = nationName.toLowerCase(java.util.Locale.ROOT).replace(" ", "_");
            return new ChunkClaim(townName, nationName, key,
                    (capital && !nationCap) || nationCap && capital, outpost, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Есть ли граница с другим владельцем у соседнего чанка (для обводки на карте). */
    public boolean isBorder(World world, int chunkX, int chunkZ) {
        ChunkClaim center = getClaimAt(world, chunkX, chunkZ);
        if (center == null) return false;
        String myKey = center.nationKey();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            ChunkClaim n = getClaimAt(world, chunkX + d[0], chunkZ + d[1]);
            if (n == null || !myKey.equals(n.nationKey())) return true;
        }
        return false;
    }

    /** Держава игрока (по Towny Resident), null = вольный стрелок. */
    public String nationNameOf(UUID playerId) {
        try {
            if (!hooked) return null;
            Object res = mResidentByUuid.invoke(townyApi, playerId);
            if (res == null) return null;
            if (!(Boolean) mHasTownResident.invoke(res)) return null;
            Object town = mTownResident.invoke(res);
            if (town == null) return null;
            Method mHasNation = town.getClass().getMethod("hasNation");
            if ((Boolean) mHasNation.invoke(town)) {
                Object nation = town.getClass().getMethod("getNationOrNull").invoke(town);
                if (nation != null) return (String) nation.getClass().getMethod("getName").invoke(nation);
            }
            return (String) town.getClass().getMethod("getName").invoke(town);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String nationKeyOf(UUID playerId) {
        String n = nationNameOf(playerId);
        return n == null ? null : n.toLowerCase(java.util.Locale.ROOT).replace(" ", "_");
    }

    /** Цвет по дипломатии для миникарты / полноэкранной карты. 0 = не красить. */
    public int mapColorFor(UUID viewerId, World world, int chunkX, int chunkZ, MapMode mode) {
        if (mode == MapMode.OFF) return 0;
        ChunkClaim claim = getClaimAt(world, chunkX, chunkZ);
        if (claim == null) return 0;

        String viewerKey = nationKeyOf(viewerId);
        if (mode == MapMode.OWN) {
            return viewerKey != null && viewerKey.equals(claim.nationKey()) ? COLOR_SAME : 0;
        }
        if (mode == MapMode.ALL) {
            return nationHashColor(claim.nationKey());
        }
        // RELATIONS (по умолчанию)
        if (viewerKey == null) return COLOR_NEUTRAL;
        if (viewerKey.equals(claim.nationKey())) return COLOR_SAME;
        if (isAllyOf(viewerId, claim.nationName())) return COLOR_ALLY;
        return COLOR_NEUTRAL; // SiegeWar-подключение позже: enemy → COLOR_ENEMY
    }

    private boolean isAllyOf(UUID viewerId, String targetNation) {
        try {
            Object res = mResidentByUuid.invoke(townyApi, viewerId);
            if (res == null) return false;
            if (!(Boolean) mHasTownResident.invoke(res)) return false;
            Object town = mTownResident.invoke(res);
            if (town == null) return false;
            Method hN = town.getClass().getMethod("hasNation");
            if (!(Boolean) hN.invoke(town)) return false;
            Object myNation = town.getClass().getMethod("getNationOrNull").invoke(town);
            if (myNation == null) return false;
            Object target = townyApi.getClass().getMethod("getNation", String.class)
                    .invoke(townyApi, targetNation);
            if (target == null) return false;
            Method allies = myNation.getClass().getMethod("getEnemies"); // Towny хранит врагов отдельно
            if (allies.invoke(myNation) != null) {
                // упрощённо: если в списке enemies — красный цвет обрабатывается SiegeWar-фазой; для demo пропускаем.
            }
            Method mAllyNations = myNation.getClass().getMethod("getAllies");
            Object alliesSet = mAllyNations.invoke(myNation);
            if (alliesSet instanceof java.util.Collection<?> col) {
                for (Object a : col) {
                    String aName = (String) a.getClass().getMethod("getName").invoke(a);
                    if (targetNation.equalsIgnoreCase(aName)) return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** POIs для компаса/карты (позже подключим к твоему CompassManager). */
    public java.util.List<PoiData> providePois(Player viewer) {
        java.util.List<PoiData> out = new java.util.ArrayList<>();
        if (!hooked) return out;
        try {
            Class<?> api = townyApi.getClass();
            Object townsCol = api.getMethod("getTowns").invoke(townyApi);
            if (townsCol instanceof java.util.Collection<?> col) {
                for (Object t : col) {
                    try {
                        String tName = (String) t.getClass().getMethod("getName").invoke(t);
                        Object spawn = t.getClass().getMethod("getSpawn").invoke(t);
                        if (spawn == null) continue;
                        Location sp = (Location) spawn;
                        String nationName = tName;
                        boolean istCap = false;
                        if ((Boolean) t.getClass().getMethod("hasNation").invoke(t)) {
                            Object n = t.getClass().getMethod("getNationOrNull").invoke(t);
                            if (n != null) {
                                nationName = (String) n.getClass().getMethod("getName").invoke(n);
                                istCap = true;
                            }
                        }
                        out.add(new PoiData("town_" + tName.toLowerCase(java.util.Locale.ROOT).replace(" ", "_"),
                                tName, nationName, istCap,
                                sp.getWorld() == null ? "world" : sp.getWorld().getName(),
                                sp.getX(), sp.getZ()));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public record PoiData(String id, String townName, String nation, boolean isCapital,
                          String world, double x, double z) {}

    public static int nationHashColor(String nationKey) {
        int hash = nationKey.hashCode();
        float hue = ((hash & 0xFFFFFF) % 360) / 360f;
        return java.awt.Color.HSBtoRGB(hue, 0.62f, 0.85f) & 0xFFFFFF;
    }
}
