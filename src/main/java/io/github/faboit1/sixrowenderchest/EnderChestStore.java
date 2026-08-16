package io.github.faboit1.sixrowenderchest;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    /** When set, every join reports what the playerdata actually contained. Off by default. */
    private final boolean debug;

    /** Parsed playerdata, read off-thread during pre-login and claimed at join. */
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** The size warning is about the server build, not the player — say it once, not per join. */
    private volatile boolean sizeMismatchLogged;

    private record Snapshot(Object root, long readAt) {}

    EnderChestStore(Nms nms, Logger logger, Path pluginDirectory, boolean debug) {
        this.nms = nms;
        this.logger = logger;
        this.debug = debug;
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
     * @return every candidate that exists on disk, best first; empty if the player has no data yet
     */
    private List<Path> locate(UUID uuid, String name) {
        List<Path> names = new ArrayList<>(5);
        names.add(this.playerDataDirectory.resolve(uuid + ".dat"));
        names.add(this.playerDataDirectory.resolve(uuid + ".dat_old"));
        if (name != null && Bukkit.getOnlineMode()) {
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            for (String suffix : new String[] { ".dat", ".dat_old", ".dat.offline-read" }) {
                names.add(this.playerDataDirectory.resolve(offline + suffix));
            }
        }
        names.removeIf(path -> !Files.isRegularFile(path));
        return names;
    }

    /**
     * Reads the first candidate that actually parses.
     *
     * <p>Falling through on a parse failure rather than only on a missing file is the whole point of
     * {@code .dat_old} — the server keeps that copy so a half-written {@code .dat} is survivable, and
     * it tries the copy in exactly this situation. Stopping at the first existing-but-corrupt file
     * would give up on data the server itself would have recovered.
     */
    private Object read(UUID uuid, String name) {
        for (Path file : locate(uuid, name)) {
            try {
                return upgrade(uuid, this.nms.readPlayerData(file));
            } catch (ReflectiveOperationException | RuntimeException e) {
                this.logger.log(Level.WARNING, "Could not read " + file.getFileName() + " for " + uuid
                    + "; trying the next copy.", e);
            }
        }
        return null; // First join, or nothing on disk parsed.
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
            verifyPersistable(container);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Could not widen the ender chest of " + player.getName(), e);
            return false;
        }

        if (root != null) {
            restore(player, container, root);
        } else if (!locate(uuid, player.getName()).isEmpty()) {
            // The file exists but would not parse. The container is now 54 slots wide, so the next
            // save rewrites EnderItems and anything that was in rows 4-6 goes with it. Keep a copy.
            backup(player, "unreadable");
        }
        return true;
    }

    /**
     * Re-reads the {@code EnderItems} entries the vanilla loader threw away.
     *
     * <p>Rows 4-6 are always restored — the loader ran against a 27-slot container and dropped them.
     * Rows 1-3 are normally left exactly as the server loaded them, so if another plugin changed them
     * during login, that change wins rather than being silently reverted to the on-disk copy. They are
     * only filled in when the container's first three rows are <em>entirely</em> empty while the file
     * says they should not be, which is what a failed load looks like and is not something an ordinary
     * mid-login edit produces. That case is rare but real: it is how a normal three-row ender chest
     * would otherwise come across empty when the server read a different copy of the playerdata than
     * this plugin did, or gave up on the file altogether.
     */
    private void restore(Player player, Object container, Object root) {
        int restored = 0;
        int recovered = 0;
        int failed = 0;
        int upperEntries = 0;
        int unreadableSlots = 0;
        try {
            Object enderItems = this.nms.tag(root, "EnderItems");
            if (enderItems == null) {
                return; // No ender chest data at all — an untouched chest, which is normal.
            }
            List<Object> entries;
            try {
                entries = this.nms.listEntries(enderItems);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Silence here would look exactly like an empty ender chest.
                this.logger.log(Level.SEVERE, "EnderItems in the playerdata of " + player.getName()
                    + " is a " + enderItems.getClass().getName() + ", which this plugin cannot walk."
                    + " Rows 4-6 cannot be restored on this server build.", e);
                backup(player, "unwalkable");
                return;
            }
            if (this.debug) {
                this.logger.info("[debug] " + player.getName() + ": EnderItems holds " + entries.size()
                    + " entr(ies); slots " + describeSlots(entries));
            }
            // Decided up front: filling row 1 must not change how row 2 is judged.
            boolean lowerRowsLost = lowerRowsEmpty(container);

            for (Object entry : entries) {
                int slot = slotOf(entry);
                if (slot < 0) {
                    unreadableSlots++;
                    continue;
                }
                if (slot >= Nms.SIX_ROWS) {
                    continue;
                }
                boolean lower = slot < Nms.THREE_ROWS;
                if (!lower) {
                    upperEntries++;
                }
                if (lower && !lowerRowsLost) {
                    continue; // The server loaded rows 1-3 itself; leave them alone.
                }
                Optional<Object> stack = this.nms.decodeItem(entry);
                if (stack.isPresent()) {
                    this.nms.setSlot(container, slot, stack.get());
                    if (lower) {
                        recovered++;
                        continue;
                    }
                    restored++;
                } else {
                    failed++;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Failed restoring the ender chest of " + player.getName(), e);
            backup(player, "error");
            return;
        }

        if (failed > 0) {
            this.logger.severe(failed + " item(s) in the ender chest of " + player.getName()
                + " could not be decoded and will be lost on the next save.");
            backup(player, "undecodable");
        }
        if (unreadableSlots > 0) {
            this.logger.severe(unreadableSlots + " EnderItems entr(ies) for " + player.getName()
                + " carry no slot index this plugin recognises, so they cannot be placed. This is a"
                + " playerdata format this build does not handle.");
            backup(player, "unknown-slot-key");
        }
        if (upperEntries > 0 && restored == 0) {
            this.logger.severe("The playerdata of " + player.getName() + " has " + upperEntries
                + " item(s) in rows 4-6 but none of them could be restored; they will be lost at the"
                + " next save.");
            backup(player, "unrestorable");
        }
        if (recovered > 0) {
            // Loud on purpose: the server should have loaded these and did not.
            this.logger.warning("The server loaded no rows 1-3 for " + player.getName()
                + " but their playerdata has " + recovered + " item(s) there; restored from the file.");
        }
        if (restored > 0) {
            int count = restored;
            this.logger.fine(() -> "Restored " + count + " item(s) into rows 4-6 for " + player.getName());
        }
    }

    /**
     * Checks that the widened container will actually be <em>saved</em> at its new size.
     *
     * <p>{@code storeAsSlots} bounds its write loop by {@code getContainerSize()}, so that call — not
     * the field the widening sets — decides how many slots reach the disk. If the two disagree, the
     * chest shows six rows, the player fills them, and the save writes 27 slots: rows 4-6 come back
     * empty on the next login, every login, with nothing in the log to explain it. Checked once per
     * player rather than assumed, because it is the difference between working and quietly eating
     * items.
     */
    private void verifyPersistable(Object container) throws ReflectiveOperationException {
        int reported = this.nms.reportedContainerSize(container);
        if (reported == Nms.SIX_ROWS || this.sizeMismatchLogged) {
            return;
        }
        this.sizeMismatchLogged = true;
        this.logger.severe("This server reports an ender chest size of " + reported + " even after it was"
            + " widened to " + Nms.SIX_ROWS + ", which means it will only ever save " + reported
            + " slots. Anything players put below that is lost at the next save. Disabling the plugin"
            + " and reporting this server build would be wise.");
    }

    /**
     * The slot indices in a set of entries, as read by {@link #slotOf}.
     *
     * <p>This is the one line that separates the two ways rows 4-6 can go missing: indices running up
     * to 53 mean the save wrote them and the restore is at fault, indices stopping at 26 mean they
     * never reached the disk, and {@code ?} means the entry carries no index this plugin can read.
     */
    private String describeSlots(List<Object> entries) {
        StringBuilder description = new StringBuilder();
        for (Object entry : entries) {
            int slot;
            try {
                slot = slotOf(entry);
            } catch (ReflectiveOperationException | RuntimeException e) {
                slot = -1;
            }
            description.append(description.isEmpty() ? "" : ",").append(slot < 0 ? "?" : slot);
        }
        return description.isEmpty() ? "(none)" : description.toString();
    }

    /** Whether the container's first three rows are completely empty. */
    private boolean lowerRowsEmpty(Object container) throws ReflectiveOperationException {
        for (int slot = 0; slot < Nms.THREE_ROWS; slot++) {
            if (!this.nms.isSlotEmpty(container, slot)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The slot index an {@code EnderItems} entry belongs in, or -1 if it does not say.
     *
     * <p>Two spellings are known — {@code Slot} as a byte up to 1.21.4, {@code slot} as an int from
     * 1.21.5 — but guessing names is how this silently returns nothing on a version that picked a
     * third. Falling back to a scan of the entry's own keys means the only way to miss the index is
     * for it not to be called "slot" at all, which is then reported rather than skipped.
     */
    private int slotOf(Object entry) throws ReflectiveOperationException {
        Object slot = this.nms.tag(entry, "Slot");
        if (slot == null) {
            slot = this.nms.tag(entry, "slot");
        }
        if (slot == null) {
            for (String key : this.nms.tagKeys(entry)) {
                if (key.equalsIgnoreCase("slot")) {
                    slot = this.nms.tag(entry, key);
                    break;
                }
            }
        }
        return slot == null ? -1 : Nms.numeric(slot);
    }

    /** Copies the raw playerdata file aside so nothing is unrecoverable when a restore goes wrong. */
    private void backup(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        List<Path> sources = locate(uuid, player.getName());
        if (sources.isEmpty()) {
            return;
        }
        Path source = sources.get(0);
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
