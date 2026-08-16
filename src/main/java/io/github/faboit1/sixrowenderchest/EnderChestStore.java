package io.github.faboit1.sixrowenderchest;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Restores ender chest rows 4-6 on join.
 *
 * <h2>Why this class has to exist</h2>
 * Purpur constructs {@code PlayerEnderChestContainer} with {@code super(54)}, so the container is
 * already 54 slots wide by the time the server reads {@code EnderItems} out of the player's NBT, and
 * vanilla persistence handles all six rows unaided. A plugin cannot get in that early: the container
 * is built inside the {@code Player} constructor and the NBT is read before any Bukkit event that
 * exposes the player. By the time {@code PlayerJoinEvent} fires, the loader has already discarded
 * every {@code EnderItems} entry with a slot index of 27 or higher.
 *
 * <p>So the plugin grows the container at join and then re-reads the slots the loader dropped from
 * the very same file the loader read — {@code playerdata/<uuid>.dat}. The important consequence is
 * that there is still exactly <em>one</em> store. The plugin never writes ender chest contents
 * anywhere; the server keeps saving all 54 slots to {@code EnderItems} on its own once the container
 * is wide enough. Rows 1-3 and rows 4-6 therefore always come from the same file, written in the same
 * atomic save, and a crash rolls both halves back together. There is no second copy of an item that
 * could survive independently, which is the only way an ender chest plugin can actually duplicate.
 */
final class EnderChestStore {

    /** How long an unclaimed pre-login snapshot is kept before it is treated as abandoned. */
    private static final long SNAPSHOT_TTL_MILLIS = 5 * 60 * 1000L;

    private final Nms nms;
    private final Logger logger;
    private final Path playerDataDirectory;
    private final Path recoveryDirectory;

    /** Parsed playerdata, read off-thread during pre-login and claimed at join. */
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    private record Snapshot(Object root, long readAt) {}

    EnderChestStore(Nms nms, Logger logger, Path pluginDirectory) {
        this.nms = nms;
        this.logger = logger;
        this.playerDataDirectory = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("playerdata");
        this.recoveryDirectory = pluginDirectory.resolve("recovery");
    }

    /**
     * Reads and parses the joining player's data file ahead of time.
     *
     * <p>Called from {@code AsyncPlayerPreLoginEvent}, which is both off the main thread (so the disk
     * read costs nothing) and before the player is registered as online (so nothing can be writing the
     * file at the same time). Failures here are not fatal — {@link #applySixRows} retries inline.
     */
    void prefetch(UUID uuid) {
        this.snapshots.values().removeIf(snapshot -> System.currentTimeMillis() - snapshot.readAt() > SNAPSHOT_TTL_MILLIS);
        Object root = read(uuid);
        if (root != null) {
            this.snapshots.put(uuid, new Snapshot(root, System.currentTimeMillis()));
        }
    }

    void forget(UUID uuid) {
        this.snapshots.remove(uuid);
    }

    private Path fileFor(UUID uuid) {
        return this.playerDataDirectory.resolve(uuid + ".dat");
    }

    private Object read(UUID uuid) {
        Path file = fileFor(uuid);
        if (!Files.isRegularFile(file)) {
            return null; // First join: nothing to restore, and nothing to lose.
        }
        try {
            return this.nms.readPlayerData(file);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Could not read playerdata for " + uuid, e);
            return null;
        }
    }

    /**
     * Grows the player's ender chest to 54 slots and puts rows 4-6 back.
     *
     * @return true if the player's ender chest is six rows wide once this returns
     */
    boolean applySixRows(Player player) {
        UUID uuid = player.getUniqueId();
        Object container;
        try {
            container = this.nms.enderChestContainer(player);
            if (this.nms.containerSize(container) == Nms.SIX_ROWS) {
                // Already grown — the plugin was reloaded under a player who never left.
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Could not reach the ender chest container of " + player.getName(), e);
            return false;
        }

        Snapshot snapshot = this.snapshots.remove(uuid);
        Object root = snapshot != null ? snapshot.root() : read(uuid);

        // Grow first, then fill: the restore writes directly into the widened backing list.
        try {
            this.nms.resizeToSixRows(container);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Could not widen the ender chest of " + player.getName(), e);
            return false;
        }

        if (root != null) {
            restoreUpperRows(player, container, root);
        } else if (Files.isRegularFile(fileFor(uuid))) {
            // The file exists but would not parse. The container is now 54 slots wide, so the next
            // save rewrites EnderItems and anything that was in rows 4-6 goes with it. Keep a copy.
            backup(uuid, "unreadable");
        }
        return true;
    }

    /**
     * Re-reads the {@code EnderItems} entries the vanilla loader threw away.
     *
     * <p>Only slots 27 and up are touched. Rows 1-3 are left exactly as the server loaded them, so if
     * another plugin has already changed them during login, that change wins rather than being
     * silently reverted to the on-disk copy.
     */
    private void restoreUpperRows(Player player, Object container, Object root) {
        int restored = 0;
        int failed = 0;
        try {
            Object enderItems = this.nms.tag(root, "EnderItems");
            if (!(enderItems instanceof Iterable<?> entries)) {
                return; // No ender chest data at all.
            }
            warnOnDataVersionMismatch(player, root);

            for (Object entry : entries) {
                int slot = slotOf(entry);
                if (slot < Nms.THREE_ROWS || slot >= Nms.SIX_ROWS) {
                    continue;
                }
                Optional<Object> stack = this.nms.decodeItem(entry);
                if (stack.isPresent()) {
                    this.nms.setSlot(container, slot, stack.get());
                    restored++;
                } else {
                    failed++;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Failed restoring ender chest rows 4-6 for " + player.getName(), e);
            backup(player.getUniqueId(), "error");
            return;
        }

        if (failed > 0) {
            this.logger.severe(failed + " item(s) in rows 4-6 of " + player.getName()
                + "'s ender chest could not be decoded and will be lost on the next save.");
            backup(player.getUniqueId(), "undecodable");
        }
        if (restored > 0) {
            int count = restored;
            this.logger.fine(() -> "Restored " + count + " item(s) into rows 4-6 for " + player.getName());
        }
    }

    private int slotOf(Object entry) throws ReflectiveOperationException {
        // "Slot" (byte) up to 1.21.4, "slot" (int) from 1.21.5 onwards.
        Object slot = this.nms.tag(entry, "Slot");
        if (slot == null) {
            slot = this.nms.tag(entry, "slot");
        }
        return slot == null ? -1 : Nms.numeric(slot);
    }

    @SuppressWarnings("deprecation") // Bukkit#getUnsafe is the only way to ask for the data version.
    private void warnOnDataVersionMismatch(Player player, Object root) throws ReflectiveOperationException {
        Object stored = this.nms.tag(root, "DataVersion");
        if (stored == null) {
            return;
        }
        int fileVersion = Nms.numeric(stored);
        int serverVersion = Bukkit.getUnsafe().getDataVersion();
        if (fileVersion != serverVersion) {
            this.logger.warning("Playerdata for " + player.getName() + " is data version " + fileVersion
                + " but this server is " + serverVersion + ". Rows 4-6 are read without running the data"
                + " fixer; check that player's ender chest after the upgrade.");
        }
    }

    /** Copies the raw playerdata file aside so nothing is unrecoverable when a restore goes wrong. */
    private void backup(UUID uuid, String reason) {
        Path source = fileFor(uuid);
        if (!Files.isRegularFile(source)) {
            return;
        }
        try {
            Files.createDirectories(this.recoveryDirectory);
            Path target = this.recoveryDirectory.resolve(
                uuid + "-" + reason + "-" + Instant.now().toEpochMilli() + ".dat");
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            this.logger.severe("Saved a copy of " + uuid + "'s playerdata to " + target
                + " before it is overwritten. Rows 4-6 can be recovered from it.");
        } catch (IOException e) {
            this.logger.log(Level.SEVERE, "Could not back up playerdata for " + uuid, e);
        }
    }
}
