package io.github.faboit1.sixrowenderchest;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Makes the vanilla ender chest six rows wide, the way Purpur's
 * {@code settings.blocks.ender_chest.six-rows} does, on Folia-family servers that cannot run Purpur.
 *
 * <p>This is deliberately not a "backpack" plugin. There is no second inventory, no GUI of its own,
 * no database. It widens the player's real {@code PlayerEnderChestContainer} to 54 slots and opens a
 * real six-row {@code ChestMenu} over it, so {@code player.getEnderChest()}, other plugins, and the
 * server's own save code all see one ordinary ender chest that simply has more slots.
 */
public final class SixRowEnderChestPlugin extends JavaPlugin implements Listener {

    private Nms nms;
    private EnderChestStore store;
    private boolean openFailureLogged;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("six-rows", true)) {
            getLogger().info("six-rows is disabled in config.yml; ender chests stay at three rows.");
            return;
        }

        try {
            this.nms = Nms.bootstrap();
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "This server build is not supported — no ender chest was modified.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        boolean debug = getConfig().getBoolean("debug", false);
        if (debug) {
            getLogger().info("debug is on: every join will report what the playerdata contained.");
        } else if (!getConfig().contains("debug")) {
            // saveDefaultConfig() leaves an existing file alone, so upgrading the jar does not add
            // new keys. Say so, rather than let someone set an option that is not there.
            getLogger().info("Your config.yml predates the 'debug' option. To enable diagnostics, add"
                + " a line reading 'debug: true' to it, or delete the file to regenerate it.");
        }
        this.store = new EnderChestStore(this.nms, getLogger(), getDataFolder().toPath(), debug);
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ------------------------------------------------------------------ widening

    /**
     * Reads the joining player's data file off-thread, before the player counts as online and before
     * anything can be writing that file.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            this.store.prefetch(event.getUniqueId(), event.getName());
        }
    }

    /**
     * Widens the chest as early as possible, so every other plugin that looks at
     * {@code player.getEnderChest()} already sees 54 slots.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        this.store.applySixRows(event.getPlayer());
    }

    // ------------------------------------------------------------------ opening

    /**
     * Replaces the vanilla three-row open with a six-row one.
     *
     * <p>Vanilla's {@code EnderChestBlock} hard-codes {@code ChestMenu.threeRows}, and a wider
     * container does not change that — it would just hide rows 4-6 behind a three-row screen. Purpur
     * patches the block; a plugin has to intercept the interaction instead and open the menu itself.
     * The guards below mirror the conditions under which vanilla would have opened the chest, so
     * anything that would not have opened one still does not.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        // Vanilla: sneaking with something in either hand places that item instead of opening.
        if (player.isSneaking() && !(isEmpty(player.getInventory().getItemInMainHand())
            && isEmpty(player.getInventory().getItemInOffHand()))) {
            return;
        }
        // Vanilla: a solid block directly above seals the chest shut.
        if (block.getRelative(BlockFace.UP).getType().isOccluding()) {
            return;
        }

        boolean opened;
        try {
            // Covers the rare case of a join that was missed (a plugin reload under online players).
            if (this.nms.containerSize(this.nms.enderChestContainer(player)) != Nms.SIX_ROWS
                && !this.store.applySixRows(player)) {
                return;
            }
            opened = this.nms.openSixRows(player, block);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!this.openFailureLogged) {
                this.openFailureLogged = true;
                getLogger().log(Level.SEVERE, "Could not open a six-row ender chest; falling back to vanilla.", e);
            }
            return; // Leave the event alone: the player gets the normal three-row chest.
        }

        // Cancel even when the open was refused, so a plugin that vetoed InventoryOpenEvent does not
        // get a vanilla three-row chest opened behind its back.
        event.setCancelled(true);

        if (opened) {
            awardStatistic(player);
            this.nms.angerNearbyPiglins(player, block);
        }
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    private void awardStatistic(Player player) {
        try {
            player.incrementStatistic(Statistic.ENDERCHEST_OPENED);
        } catch (LinkageError | RuntimeException ignored) {
            // Statistic bookkeeping is not worth breaking an open over.
        }
    }
}
