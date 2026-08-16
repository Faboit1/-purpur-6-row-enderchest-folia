# SixRowEnderChest

Six-row ender chests on **Folia / Canvas / Paper**, matching Purpur's
`settings.blocks.ender_chest.six-rows`.

No GUI of its own, no backpack, no database, no commands, no permissions. The ender chest just has
54 slots instead of 27.

---

## How Purpur actually does it

Purpur's feature is one patch, [`0003-Barrels-and-enderchests-6-rows.patch`][patch]. Ignoring the
barrel half and the optional per-permission row counts, the ender chest part is three edits:

1. **`PlayerEnderChestContainer`** — the container every player carries — is constructed wider:

   ```java
   public PlayerEnderChestContainer(Player owner) {
       super(org.purpurmc.purpur.PurpurConfig.enderChestSixRows ? 54 : 27);
   }
   ```

2. **`EnderChestBlock`** opens `ChestMenu.sixRows(...)` instead of `ChestMenu.threeRows(...)`.

3. **`CraftContainer`** maps `InventoryType.ENDER_CHEST` to `GENERIC_9x6`, so plugins calling
   `player.openInventory(player.getEnderChest())` get six rows too.

Persistence is the part that matters, and Purpur writes no persistence code at all. The container's
`storeAsSlots` / `fromSlots` (`createTag` / `fromTag` before 1.21.5) are both bounded by
`getContainerSize()`, so a 54-slot container saves and loads 54 slots through the ordinary
`EnderItems` list in the player's NBT. Nothing else changes.

That is the whole feature — and it is why it cannot duplicate items: there is exactly one place
ender chest contents live, and it is the same place vanilla already used.

[patch]: https://github.com/PurpurMC/Purpur/blob/HEAD/purpur-server/minecraft-patches/features/0003-Barrels-and-enderchests-6-rows.patch

## Can it be done on Canvas without dupes?

Yes. Canvas is a Folia fork and Folia is a Paper fork, so all of the above classes exist and behave
identically; Canvas simply has no such patch, and it has no mixin loader for plugins either. So the
work has to happen from a plugin, and one thing genuinely does not port cleanly.

**The problem.** Purpur's edit is in a *constructor*. `PlayerEnderChestContainer` is created inside
the `Player` constructor, and the server reads `EnderItems` into it before any Bukkit event hands
a plugin the player. By the time `PlayerJoinEvent` fires, the loader has already run against a
27-slot container and discarded every entry with a slot index of 27 or higher. A plugin cannot get
in front of that. (`PlayerLoginEvent` does fire earlier, but on current Paper it only does so via an
explicitly deprecated compatibility path — `HorriblePlayerLoginEventHack` — that is documented for
removal, so it is not something to build on.)

**The fix.** Widen the container at join, then re-read the dropped slots **from the same file the
server just read** — `playerdata/<uuid>.dat`. Rows 4-6 are decoded with the game's own
`ItemStack.CODEC` and written into slots 27-53.

That keeps the dupe-safety property intact, which is the only thing that really matters here:

* The plugin **never writes ender chest contents anywhere.** Once the container is 54 slots wide,
  the server's own save code writes all six rows to `EnderItems` unaided, exactly as under Purpur.
* There is therefore still **one store, not two.** Rows 1-3 and rows 4-6 always come out of the same
  file, written by the same atomic save. A crash rolls both halves back together.
* Duplication needs two copies of an item that can be restored independently. There is no second
  copy to restore.

This is also why the plugin does *not* keep its own inventory in a `.yml`, a database, or the PDC.
Any of those would introduce a second store with its own write schedule, and a crash landing between
the two writes is precisely how "6-row ender chest" plugins dupe.

**What the plugin does at runtime**

| | |
|---|---|
| `AsyncPlayerPreLoginEvent` | Reads and parses the player's `.dat` off-thread, while they are not yet online and nothing can be writing that file. |
| `PlayerJoinEvent` (`LOWEST`) | Grows the live container to 54 slots in place and restores slots 27-53. Runs first, so every other plugin already sees 54 slots. |
| `PlayerInteractEvent` (`HIGH`) | Opens a real `ChestMenu.sixRows` over the container, the same call Purpur's patched `EnderChestBlock` makes. |

The container is grown **in place** rather than replaced, because `CraftHumanEntity` caches one
`CraftInventory` wrapper around that exact object and the server reads the field directly when
saving — swapping the instance would desync both.

Interception is needed for opening because vanilla's `EnderChestBlock` hard-codes
`ChestMenu.threeRows`; a wider container alone would just hide rows 4-6 behind a three-row screen.
Going through `Player#openMenu` rather than the Bukkit inventory API keeps the rest of the behaviour
vanilla: lid animation and open/close sounds (via `setActiveChest` and the container's
`startOpen`/`stopOpen`), `InventoryOpenEvent`, the `open_enderchest` statistic, and piglin aggro.
The guards mirror vanilla's own — sneaking with a full hand, a solid block above, spectators, and
anything another plugin has already cancelled or denied all behave as before.

`player.getEnderChest()` returns 54 slots for other plugins, and `player.openInventory(...)` on it
opens six rows on its own — CraftBukkit's `CraftContainer.getNotchInventoryType` already picks the
menu type from the inventory's size, so Purpur's third edit needs no equivalent here.

## Migrating from Purpur

**Copy the world across and start the server. That is the whole migration.**

Purpur keeps all six rows in the ordinary vanilla `EnderItems` list in `playerdata/<uuid>.dat` — it
writes no ender chest data of its own anywhere else (see the patch above: `storeAsSlots` just runs to
54 instead of 27). This plugin reads that exact same list. So a Purpur world dropped onto Canvas
already contains everyone's rows 4-6, and each player gets them back on their first join.

Nothing to export, no conversion command, no separate database. It also works in reverse — go back to
Purpur later and the rows are still where Purpur expects them.

Two things worth knowing:

* **Install the plugin before players log in on the new server.** Without it, the first join loads a
  27-slot container and the first save rewrites `EnderItems` with three rows' worth of items — rows
  4-6 are gone at that point, and no plugin can get them back. If that has already happened, restore
  those players' `.dat` files from a backup of the Purpur world first.
* **Migrating across a Minecraft version at the same time is fine.** The plugin runs the game's own
  data fixer (`DataFixTypes.PLAYER`) over the file before reading rows 4-6, the same call the server
  makes for rows 1-3, so item NBT from an older version is upgraded rather than discarded. Coming from
  a Purpur old enough to predate the 1.20.5 item component rewrite works for that reason.

If the plugin ever cannot decode something, it does not quietly drop it: it copies the raw `.dat` to
`plugins/SixRowEnderChest/recovery/` and logs loudly before anything is overwritten.

Purpur's `enderchest-permission-rows` has no equivalent here — this plugin gives everyone six rows.
Players who were limited to fewer rows on Purpur still keep whatever was stored above their limit
(Purpur persists all 54 slots when `persist-hidden-rows` is on), so they gain access to it rather than
losing it.

## Compatibility

* **Canvas, Folia, Paper, and forks of them**, Minecraft **1.21.4 and newer** (tested surface:
  1.21.4 → 26.2). Declares `folia-supported: true`; all work happens on the thread that already owns
  the player, so nothing crosses a region boundary.
* **Java 21+.**
* Not for Spigot/CraftBukkit — those do not ship a Mojang-mapped runtime.

The plugin talks to server internals by reflection, using Mojang-mapped names (Paper has shipped a
Mojang-mapped runtime since 1.20.5, so `ChestMenu.sixRows` really is called that at runtime). Every
member is resolved at startup: if anything is missing, the plugin logs the failure and **disables
itself** rather than half-working around your players' items.

## Install

Drop the jar in `plugins/` and restart. Players get six rows on their next join.

Grab the jar from the [latest release][releases], or from the artifact on any
[Build run][actions], or build it yourself:

```sh
./gradlew build      # -> build/libs/SixRowEnderChest-<version>.jar
```

[releases]: https://github.com/Faboit1/-purpur-6-row-enderchest-folia/releases
[actions]: https://github.com/Faboit1/-purpur-6-row-enderchest-folia/actions/workflows/build.yml

## Config

`plugins/SixRowEnderChest/config.yml`:

```yaml
six-rows: true
```

## Before you turn it off

⚠️ **Rows 4-6 are dropped when the chest goes back to three rows.** They live in the player's normal
ender chest data, so a 27-slot container rewrites `EnderItems` with three rows' worth of items at
that player's next save and the rest is gone. This applies to setting `six-rows: false`, removing the
plugin, and Purpur's own setting equally — it is inherent to storing the rows in the vanilla slot
list. **Empty rows 4-6 before disabling.**

As a safety net, if the plugin ever widens a chest whose rows 4-6 it could not read back (a corrupt
file, or item NBT from an older Minecraft version), it copies the raw `.dat` to
`plugins/SixRowEnderChest/recovery/` and logs loudly before anything is overwritten.

After a Minecraft version upgrade, rows 4-6 are put through the game's data fixer before being read,
the same as the server does for rows 1-3, and the first join on the new version logs a line saying so.
Only if a server build exposes no data fixer at all does it fall back to reading them as-is, with a
warning.

## Alternative

If you would rather carry the patch than the plugin, Purpur's patch applies to Canvas nearly
verbatim — the classes it edits are untouched by Folia's region threading. That gets you exact
upstream behaviour including the per-permission row counts, at the cost of maintaining a server fork.
This plugin exists for people who want a drop-in jar instead.

## License

MIT
