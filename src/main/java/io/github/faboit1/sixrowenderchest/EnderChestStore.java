package io.github.faboit1.sixrowenderchest;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
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
 *
 * <h2>Migrating from Purpur</h2>
 * Purpur stores its six rows in the ordinary vanilla {@code EnderItems} list, so a world copied off a
 * Purpur server needs no conversion — the extra rows are already sitting in the playerdata, and the
 * restore path above picks them up on the player's first join. Two details make that work in practice
 * rather than only in theory:
 * <ul>
 *   <li>{@link #upgrade} runs the game's data fixer when the file was written by an older Minecraft
 *       version, the same as the server does for rows 1-3. Without it, a migration that also crosses a
 *       version boundary would hand old-format item NBT to the current codec and lose rows 4-6.</li>
 *   <li>{@link #locate} looks in the same fallback places the server does, so a {@code .dat} that is
 *       missing or was written under a different online-mode setting still resolves.</li>
 * </ul>
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
    void prefetch(UUID uuid, String name) {
        this.snapshots.values().removeIf(snapshot -> System.currentTimeMillis() - snapshot.readAt() > SNAPSHOT_TTL_MILLIS);
        Object root = read(uuid, name);
        if (root != null) {
            this.snapshots.put(uuid, new Snapshot(root, System.currentTimeMillis()));
        }
    }

    void forget(UUID uuid) {
        this.snapshots.remove(uuid);
    }

    /**
     * Finds the file the server would have loaded this player from.
     *
     * <p>The order mirrors {@code PlayerDataStorage#load}: the real file, then the {@code .dat_old}
     * copy the server keeps from the previous save, and — on an online-mode server whose real file is
     * missing — the offline-UUID file Spigot falls back to. That last one matters when a world is moved
     * between servers that disagreed about online mode, which is exactly the kind of migration this
     * plugin has to survive. ({@code .offline-read} is the name the server renames it to once it has
     * read it, so a join-time retry can still find it.)
     *
     * @return the first candidate that exists, or {@code null} if the player has no data yet
     */
    private Path locate(UUID uuid, String name) {
        Path real = this.playerDataDirectory.resolve(uuid + ".dat");
        if (Files.isRegularFile(real)) {
            return real;
        }
        Path old = this.playerDataDirectory.resolve(uuid + ".dat_old");
        if (Files.isRegularFile(old)) {
            return old;
        }
        if (name != null && Bukkit.getOnlineMode()) {
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            for (String suffix : new String[] { ".dat", ".dat_old", ".dat.offline-read" }) {
                Path candidate = this.playerDataDirectory.resolve(offline + suffix);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Object read(UUID uuid, String name) {
        Path file = locate(uuid, name);
        if (file == null) {
            return null; // First join: nothing to restore, and nothing to lose.
        }
        Object root;
        try {
            root = this.nms.readPlayerData(file);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Could not read playerdata for " + uuid, e);
            return null;
        }
        return upgrade(uuid, root);
    }

    /**
     * Brings a playerdata tag written by an older Minecraft version up to the running version.
     *
     * <p>The server does this to the same file when it loads it, which is why rows 1-3 survive a
     * version jump untouched. Reading the file directly bypasses that, so without this step rows 4-6
     * of anyone migrating from an older Purpur would be old-format item NBT that the current
     * {@code ItemStack.CODEC} cannot decode — they would be reported as undecodable and lost.
     */
    @SuppressWarnings("deprecation") // Bukkit#getUnsafe is the only way to ask for the data version.
    private Object upgrade(UUID uuid, Object root) {
        int fileVersion;
        int serverVersion;
        try {
            Object stored = this.nms.tag(root, "DataVersion");
            // Absent means the file predates the field entirely; -1 is what the server passes then.
            fileVersion = stored == null ? -1 : Nms.numeric(stored);
            serverVersion = Bukkit.getUnsafe().getDataVersion();
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Could not read the data version of playerdata for " + uuid, e);
            return root;
        }

        if (fileVersion == serverVersion) {
            return root;
        }
        if (fileVersion > serverVersion) {
            this.logger.warning("Playerdata for " + uuid + " is data version " + fileVersion
                + " but this server is " + serverVersion + ". Downgrading is not supported, so rows 4-6"
                + " are read as-is; check that player's ender chest.");
            return root;
        }
        if (!this.nms.canUpgradePlayerData()) {
            this.logger.warning("Playerdata for " + uuid + " is data version " + fileVersion
                + " but this server is " + serverVersion + ", and this server build exposes no data"
                + " fixer. Rows 4-6 are read without upgrading; check that player's ender chest.");
            return root;
        }

        try {
            Object upgraded = this.nms.upgradePlayerData(root, fileVersion);
            if (upgraded == null) {
                return root;
            }
            this.logger.info("Upgraded playerdata for " + uuid + " from data version " + fileVersion
                + " to " + serverVersion + " to read ender chest rows 4-6.");
            return upgraded;
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Could not run the data fixer over playerdata for " + uuid
                + "; rows 4-6 are read as-is.", e);
            return root;
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
        Object root = snapshot != null ? snapshot.root() : read(uuid, player.getName());

        // Grow first, then fill: the restore writes directly into the widened backing list.
        try {
            this.nms.resizeToSixRows(container);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Could not widen the ender chest of " + player.getName(), e);
            return false;
        }

        if (root != null) {
            restoreUpperRows(player, container, root);
        } else if (locate(uuid, player.getName()) != null) {
            // The file exists but would not parse. The container is now 54 slots wide, so the next
            // save rewrites EnderItems and anything that was in rows 4-6 goes with it. Keep a copy.
            backup(player, "unreadable");
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
            backup(player, "error");
            return;
        }

        if (failed > 0) {
            this.logger.severe(failed + " item(s) in rows 4-6 of " + player.getName()
                + "'s ender chest could not be decoded and will be lost on the next save.");
            backup(player, "undecodable");
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

    /** Copies the raw playerdata file aside so nothing is unrecoverable when a restore goes wrong. */
    private void backup(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        Path source = locate(uuid, player.getName());
        if (source == null) {
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
