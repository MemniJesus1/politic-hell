package com.politichell.hud.cards;

import com.politichell.hud.PoliticHellHud;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;

import java.util.UUID;

/**
 * Правила привязки: Персональный / Неотчуждаемый предмет нельзя выбросить, положить в сундук и т. п., он не выпадает и не ломается.
 * Шалкер (мешок) с таким предметом сам считается привязанным. Что именно разрешено — cards.yml → rules.
 */
final class CardGuard implements Listener {
    private final PoliticHellHud plugin;
    private final CardManager mgr;

    CardGuard(PoliticHellHud plugin, CardManager mgr) { this.plugin = plugin; this.mgr = mgr; }

    private boolean bypass(Player p) { return mgr.bypassing(p); }

    private Binding eff(ItemStack s) { return mgr.effective(s); }

    private boolean bound(ItemStack s) { return s != null && !s.getType().isAir() && mgr.config.enabled && eff(s).bound(); }

    private void deny(Player p) { p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(mgr.config.denyMessage)); }

    /** Нельзя ли держать предмет с такой привязкой в этом окне (в ячейке raw)? */
    private boolean forbidden(Binding b, InventoryType type, int raw) {
        if (type == InventoryType.PLAYER) return false;
        if (type == InventoryType.CRAFTING) return raw >= 0 && raw <= 4;               // окно инвентаря: сетка крафта (0–4) запрещена, броня/хотбар — свои
        if (b == Binding.LOCKED) return true;
        return !mgr.config.personalAllowed.contains(type);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack cur = e.getCurrentItem(), cursor = e.getCursor();
        // переключатель «кратко / подробно»
        if (mgr.config.toggleShiftRight && e.getClick() == ClickType.SHIFT_RIGHT && cur != null && !cur.getType().isAir() && mgr.realTop(e.getView().getTopInventory())) {
            e.setCancelled(true);
            mgr.toggleMode(p);
            return;
        }
        if (!mgr.config.enabled || bypass(p)) return;
        Inventory top = e.getView().getTopInventory();
        InventoryType tt = top.getType();
        int raw = e.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();
        InventoryAction act = e.getAction();
        boolean curB = bound(cur), cursorB = bound(cursor);
        if (!curB && !cursorB && e.getClick() != ClickType.NUMBER_KEY && e.getClick() != ClickType.SWAP_OFFHAND) return;

        switch (act) {
            case DROP_ALL_SLOT, DROP_ONE_SLOT -> { if (curB) { e.setCancelled(true); deny(p); } return; }
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR -> { if (cursorB) { e.setCancelled(true); deny(p); } return; }
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> {
                if (cursorB && inTop && forbidden(eff(cursor), tt, raw)) { e.setCancelled(true); deny(p); return; }
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (curB && !inTop && tt != InventoryType.CRAFTING && tt != InventoryType.PLAYER && forbidden(eff(cur), tt, 99)) { e.setCancelled(true); deny(p); return; }
            }
            case HOTBAR_SWAP -> {
                ItemStack other = e.getClick() == ClickType.SWAP_OFFHAND ? p.getInventory().getItemInOffHand()
                        : e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton()) : null;
                if (bound(other) && inTop && forbidden(eff(other), tt, raw)) { e.setCancelled(true); deny(p); return; }
            }
            default -> {}
        }
        // мешки (bundle): привязанное в мешок нельзя и наоборот
        if ((cursorB && cur != null && cur.getItemMeta() instanceof BundleMeta) || (curB && cursor != null && cursor.getItemMeta() instanceof BundleMeta)) {
            e.setCancelled(true); deny(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !mgr.config.enabled || bypass(p)) return;
        ItemStack old = e.getOldCursor();
        if (!bound(old)) return;
        Inventory top = e.getView().getTopInventory();
        for (int raw : e.getRawSlots()) if (raw < top.getSize() && forbidden(eff(old), top.getType(), raw)) { e.setCancelled(true); deny(p); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !mgr.config.enabled || bypass(p)) return;
        if (e.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE && bound(e.getCursor())) { e.setCancelled(true); deny(p); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!mgr.config.enabled || bypass(p)) return;
        if (bound(e.getItemDrop().getItemStack())) { e.setCancelled(true); deny(p); }
    }

    /** Если привязанный предмет всё же оказался на земле — он неуничтожим и не исчезает, а подобрать его может только хозяин. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(ItemSpawnEvent e) {
        ItemStack s = e.getEntity().getItemStack();
        if (!bound(s)) return;
        e.getEntity().setUnlimitedLifetime(true);
        e.getEntity().setInvulnerable(true);
        UUID owner = mgr.ownerOf(s);
        if (owner != null) e.getEntity().setOwner(owner);
    }

    @EventHandler(ignoreCancelled = true) public void onHopper(InventoryMoveItemEvent e) { if (bound(e.getItem())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true) public void onHopperPickup(InventoryPickupItemEvent e) { if (bound(e.getItem().getItemStack())) e.setCancelled(true); }

    /** Сущности, которым можно «отдать» предмет из руки: рамка, стойка, Тихоня, пиглин, лошади/ослы/верблюды/ламы, волк (броня), свинья/лавоход (седло), новые мобы 1.21.x. */
    private static boolean takesItems(org.bukkit.entity.Entity en) {
        if (en instanceof ItemFrame || en instanceof ArmorStand || en instanceof org.bukkit.entity.Allay || en instanceof org.bukkit.entity.Piglin
                || en instanceof org.bukkit.entity.AbstractHorse || en instanceof org.bukkit.entity.Wolf || en instanceof org.bukkit.entity.Pig
                || en instanceof org.bukkit.entity.Strider || en instanceof org.bukkit.entity.Fox) return true;
        String k = en.getType().getKey().getKey();
        return k.contains("nautilus") || k.contains("happy_ghast") || k.contains("copper_golem");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!mgr.config.enabled || bypass(p)) return;
        var target = e.getRightClicked();
        if (!(target instanceof ItemFrame) && !(target instanceof ArmorStand) && !mgr.config.blockEntityGive) return;
        if (!takesItems(target)) return;
        ItemStack it = e.getHand() == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (bound(it)) { e.setCancelled(true); deny(p); }
    }

    /** Блоки, в которые предмет «вкладывается» правым кликом из руки (минуя окно инвентаря): горшок, полки, аналой, костёр, проигрыватель… */
    private static boolean takesFromHand(Material m) {
        String n = m.name();
        return n.equals("DECORATED_POT") || n.equals("CHISELED_BOOKSHELF") || n.equals("LECTERN") || n.equals("JUKEBOX") || n.equals("COMPOSTER")
                || n.endsWith("CAMPFIRE") || n.endsWith("_SHELF") || n.equals("FLOWER_POT") || n.startsWith("POTTED_") || n.equals("END_PORTAL_FRAME") || n.equals("RESPAWN_ANCHOR");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBlockInsert(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!mgr.config.enabled || !mgr.config.blockBlockInsert || bypass(p) || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!takesFromHand(e.getClickedBlock().getType())) return;
        ItemStack it = e.getItem();
        if (bound(it)) { e.setCancelled(true); deny(p); }
    }

    // ───── привязка «при использовании» ─────
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseClick(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL || e.getItem() == null || !mgr.config.enabled) return;
        mgr.bindUse(e.getPlayer(), e.getHand() == null ? EquipmentSlot.HAND : e.getHand());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && mgr.config.enabled) mgr.bindUse(p, EquipmentSlot.HAND);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (mgr.config.enabled) mgr.bindUse(e.getPlayer(), EquipmentSlot.HAND);
    }

    /** Брошенный привязанный трезубец поднимет только владелец. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowPickup(org.bukkit.event.player.PlayerPickupArrowEvent e) {
        if (!mgr.config.enabled || !mgr.config.tridentOwnerOnly || bypass(e.getPlayer())) return;
        ItemStack it = e.getItem().getItemStack();
        if (!bound(it)) return;
        UUID owner = mgr.ownerOf(it);
        if (owner != null && !owner.equals(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (!mgr.config.enabled || bypass(e.getPlayer())) return;
        if (bound(e.getPlayerItem())) { e.setCancelled(true); deny(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!mgr.config.enabled || bypass(p)) return;
        if (bound(e.getItemInHand())) { e.setCancelled(true); deny(p); }
    }

    /** Привязанный предмет не ломается: когда прочность на исходе, урон просто не засчитывается. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(PlayerItemDamageEvent e) {
        if (!mgr.config.enabled || !mgr.config.unbreakableBound) return;
        ItemStack it = e.getItem();
        if (!bound(it) || !(it.getItemMeta() instanceof Damageable dm)) return;
        int left = it.getType().getMaxDurability() - dm.getDamage();
        if (e.getDamage() >= left) e.setCancelled(true);
    }
}
