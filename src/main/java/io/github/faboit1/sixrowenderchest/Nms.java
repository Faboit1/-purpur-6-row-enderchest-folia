package io.github.faboit1.sixrowenderchest;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Every piece of server-internal access the plugin needs, resolved once at startup.
 *
 * <p>Two rules keep this survivable across Minecraft versions:
 * <ul>
 *   <li>Only Mojang-mapped names are used. Paper (and therefore Folia and Canvas) has shipped a
 *       Mojang-mapped runtime since 1.20.5, so {@code ChestMenu.sixRows} really is called that at
 *       runtime — no obfuscation table needed.</li>
 *   <li>Anything whose owning class name has moved between versions is looked up from a live
 *       object's class hierarchy instead of by hard-coded name.</li>
 * </ul>
 *
 * <p>{@link #bootstrap} resolves everything eagerly and throws if a single member is missing, so an
 * incompatible server fails loudly at enable time rather than half-working around a player's items.
 */
final class Nms {

    static final int THREE_ROWS = 27;
    static final int SIX_ROWS = 54;

    /** Container size field on {@code net.minecraft.world.SimpleContainer} ({@code private final int size}). */
    private final Field sizeField;
    /** Backing list field on {@code SimpleContainer} ({@code private final NonNullList<ItemStack> items}). */
    private final Field itemsField;
    /** {@code NonNullList#withSize(int, E)}. */
    private final Method withSize;
    /** {@code ItemStack.EMPTY}. */
    private final Object emptyStack;

    /** {@code ChestMenu#sixRows(int, Inventory, Container)}. */
    private final Method sixRows;
    /** {@code net.minecraft.world.inventory.MenuConstructor}, implemented below via a {@link Proxy}. */
    private final Class<?> menuConstructorType;
    /** {@code SimpleMenuProvider(MenuConstructor, Component)}. */
    private final Constructor<?> simpleMenuProvider;
    /** {@code Component.translatable("container.enderchest")} — the vanilla ender chest title. */
    private final Object enderChestTitle;

    /** {@code BlockPos(int, int, int)}. */
    private final Constructor<?> blockPos;

    /** {@code NbtIo#readCompressed(Path, NbtAccounter)}. */
    private final Method readCompressed;
    /** {@code NbtAccounter.unlimitedHeap()}. */
    private final Object nbtAccounter;
    /** {@code CompoundTag#get(String)} — stable across every version, unlike the typed getters. */
    private final Method compoundGet;

    /** {@code ItemStack.CODEC}. */
    private final Object itemStackCodec;
    /** {@code RegistryOps.create(NbtOps.INSTANCE, registryAccess)}. */
    private final Object registryOps;
    /** {@code Decoder#parse(DynamicOps, Object)}. */
    private final Method codecParse;
    /** {@code DataResult#result()}. */
    private final Method dataResultResult;

    /** {@code PiglinAi#angerNearbyPiglins(...)} — cosmetic, so this one is allowed to be absent. */
    private final Method angerNearbyPiglins;

    // Resolved from a live instance the first time it is needed, then cached. These live on
    // CraftBukkit classes whose package is version-suffixed on some server software.
    private volatile Method craftPlayerGetHandle;
    private volatile Method craftInventoryGetInventory;
    private volatile Method craftWorldGetHandle;
    private volatile Method getEnderChestInventory;
    private volatile Method openMenu;
    private volatile Method setActiveChest;
    private volatile Method getBlockEntity;

    private Nms(
        Field sizeField,
        Field itemsField,
        Method withSize,
        Object emptyStack,
        Method sixRows,
        Class<?> menuConstructorType,
        Constructor<?> simpleMenuProvider,
        Object enderChestTitle,
        Constructor<?> blockPos,
        Method readCompressed,
        Object nbtAccounter,
        Method compoundGet,
        Object itemStackCodec,
        Object registryOps,
        Method codecParse,
        Method dataResultResult,
        Method angerNearbyPiglins
    ) {
        this.sizeField = sizeField;
        this.itemsField = itemsField;
        this.withSize = withSize;
        this.emptyStack = emptyStack;
        this.sixRows = sixRows;
        this.menuConstructorType = menuConstructorType;
        this.simpleMenuProvider = simpleMenuProvider;
        this.enderChestTitle = enderChestTitle;
        this.blockPos = blockPos;
        this.readCompressed = readCompressed;
        this.nbtAccounter = nbtAccounter;
        this.compoundGet = compoundGet;
        this.itemStackCodec = itemStackCodec;
        this.registryOps = registryOps;
        this.codecParse = codecParse;
        this.dataResultResult = dataResultResult;
        this.angerNearbyPiglins = angerNearbyPiglins;
    }

    // ------------------------------------------------------------------ bootstrap

    static Nms bootstrap() throws ReflectiveOperationException {
        Class<?> simpleContainer = Class.forName("net.minecraft.world.SimpleContainer");
        Class<?> nonNullList = Class.forName("net.minecraft.core.NonNullList");
        Class<?> itemStack = Class.forName("net.minecraft.world.item.ItemStack");
        Class<?> chestMenu = Class.forName("net.minecraft.world.inventory.ChestMenu");
        Class<?> inventory = Class.forName("net.minecraft.world.entity.player.Inventory");
        Class<?> container = Class.forName("net.minecraft.world.Container");
        Class<?> menuConstructor = Class.forName("net.minecraft.world.inventory.MenuConstructor");
        Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        Class<?> simpleMenuProvider = Class.forName("net.minecraft.world.SimpleMenuProvider");
        Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        Class<?> nbtAccounter = Class.forName("net.minecraft.nbt.NbtAccounter");
        Class<?> nbtOps = Class.forName("net.minecraft.nbt.NbtOps");
        Class<?> compoundTag = Class.forName("net.minecraft.nbt.CompoundTag");
        Class<?> registryOpsClass = Class.forName("net.minecraft.resources.RegistryOps");
        Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
        Class<?> codec = Class.forName("com.mojang.serialization.Codec");
        Class<?> dataResult = Class.forName("com.mojang.serialization.DataResult");

        Field size = simpleContainer.getDeclaredField("size");
        size.setAccessible(true);
        Field items = simpleContainer.getDeclaredField("items");
        items.setAccessible(true);
        if (size.getType() != int.class || !nonNullList.isAssignableFrom(items.getType())) {
            throw new NoSuchFieldException(
                "SimpleContainer.size/items have unexpected types (" + size.getType() + ", " + items.getType() + ")");
        }

        Field empty = itemStack.getDeclaredField("EMPTY");
        empty.setAccessible(true);

        Field codecField = itemStack.getDeclaredField("CODEC");
        codecField.setAccessible(true);

        Field nbtOpsInstance = nbtOps.getDeclaredField("INSTANCE");
        nbtOpsInstance.setAccessible(true);

        // MinecraftServer#registryAccess() — needed so item components resolve against the live registries.
        Object bukkitServer = Bukkit.getServer();
        Object minecraftServer = bukkitServer.getClass().getMethod("getServer").invoke(bukkitServer);
        Object registryAccess = minecraftServer.getClass().getMethod("registryAccess").invoke(minecraftServer);

        Method registryOpsCreate = findStatic(registryOpsClass, "create", 2);
        Object registryOps = registryOpsCreate.invoke(null, nbtOpsInstance.get(null), registryAccess);

        Method unlimitedHeap = nbtAccounter.getMethod("unlimitedHeap");

        return new Nms(
            size,
            items,
            nonNullList.getMethod("withSize", int.class, Object.class),
            empty.get(null),
            chestMenu.getMethod("sixRows", int.class, inventory, container),
            menuConstructor,
            simpleMenuProvider.getConstructor(menuConstructor, component),
            component.getMethod("translatable", String.class).invoke(null, "container.enderchest"),
            blockPos.getConstructor(int.class, int.class, int.class),
            nbtIo.getMethod("readCompressed", Path.class, nbtAccounter),
            unlimitedHeap.invoke(null),
            compoundTag.getMethod("get", String.class),
            codecField.get(null),
            registryOps,
            codec.getMethod("parse", dynamicOps, Object.class),
            dataResult.getMethod("result"),
            findPiglinAnger()
        );
    }

    private static Method findStatic(Class<?> owner, String name, int arity) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                && method.getName().equals(name)
                && method.getParameterCount() == arity) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "/" + arity);
    }

    private static Method findPiglinAnger() {
        try {
            Class<?> piglinAi = Class.forName("net.minecraft.world.entity.monster.piglin.PiglinAi");
            for (Method method : piglinAi.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("angerNearbyPiglins")) {
                    return method;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Not fatal — piglin aggro is cosmetic.
        }
        return null;
    }

    private static Method resolve(Object instance, String name, Class<?>... params) throws NoSuchMethodException {
        return instance.getClass().getMethod(name, params);
    }

    // ------------------------------------------------------------------ container access

    /**
     * The player's live {@code PlayerEnderChestContainer}. Reached through the Bukkit wrapper rather
     * than {@code CraftPlayer#getHandle}, because {@code CraftInventory#getInventory()} returns the
     * very same container object the server persists.
     */
    Object enderChestContainer(Player player) throws ReflectiveOperationException {
        Object craftInventory = player.getEnderChest();
        Method getter = this.craftInventoryGetInventory;
        if (getter == null) {
            this.craftInventoryGetInventory = getter = resolve(craftInventory, "getInventory");
        }
        return getter.invoke(craftInventory);
    }

    int containerSize(Object container) throws ReflectiveOperationException {
        return this.sizeField.getInt(container);
    }

    /**
     * Grows the container to 54 slots in place, preserving whatever is already in it.
     *
     * <p>In place matters: {@code CraftHumanEntity} caches one {@code CraftInventory} wrapper around
     * this exact object at construction, and {@code Player.enderChestInventory} is read directly when
     * the server saves. Swapping in a different container instance would desync both. Both fields are
     * {@code final} but non-static, which reflection is allowed to write after {@code setAccessible}.
     */
    void resizeToSixRows(Object container) throws ReflectiveOperationException {
        int current = containerSize(container);
        if (current == SIX_ROWS) {
            return;
        }
        Object grown = this.withSize.invoke(null, SIX_ROWS, this.emptyStack);
        @SuppressWarnings("unchecked")
        List<Object> target = (List<Object>) grown;
        @SuppressWarnings("unchecked")
        List<Object> existing = (List<Object>) this.itemsField.get(container);
        for (int slot = 0; slot < Math.min(current, SIX_ROWS); slot++) {
            target.set(slot, existing.get(slot));
        }
        this.itemsField.set(container, grown);
        this.sizeField.setInt(container, SIX_ROWS);
    }

    /** Writes a decoded stack straight into the backing list. Only used while restoring rows 4-6. */
    void setSlot(Object container, int slot, Object itemStack) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        List<Object> backing = (List<Object>) this.itemsField.get(container);
        backing.set(slot, itemStack);
    }

    // ------------------------------------------------------------------ opening the menu

    /**
     * Opens a real six-row {@code ChestMenu} over the player's ender chest container — the same call
     * Purpur's patched {@code EnderChestBlock} makes.
     *
     * <p>Going through {@code Player#openMenu} rather than the Bukkit inventory API is what keeps the
     * behaviour identical to vanilla: {@code ChestMenu} calls {@code startOpen}/{@code stopOpen} on the
     * container, which drives the lid animation and the open/close sounds through the block entity that
     * {@code setActiveChest} pins, and CraftBukkit still fires {@code InventoryOpenEvent} from inside
     * {@code openMenu}.
     *
     * @param chest the ender chest block being opened, or {@code null} for a blockless open
     * @return whether the menu actually opened (a plugin may have cancelled {@code InventoryOpenEvent})
     */
    boolean openSixRows(Player player, Block chest) throws ReflectiveOperationException {
        Object container = enderChestContainer(player);
        Object serverPlayer = serverPlayer(player);

        Method activeChest = this.setActiveChest;
        if (activeChest == null) {
            this.setActiveChest = activeChest = findSingleArg(container.getClass(), "setActiveChest", null);
        }
        Object blockEntity = chest == null ? null : enderChestBlockEntity(chest);
        if (blockEntity != null && !activeChest.getParameterTypes()[0].isInstance(blockEntity)) {
            blockEntity = null; // The block changed out from under the interaction.
        }
        // Must happen before the menu is built: ChestMenu's constructor triggers startOpen, and the
        // container only forwards that to the block entity if the active chest is already pinned.
        activeChest.invoke(container, blockEntity);

        Object menuProvider = this.simpleMenuProvider.newInstance(
            menuConstructorFor(container), this.enderChestTitle);

        Method open = this.openMenu;
        if (open == null) {
            this.openMenu = open =
                findSingleArg(serverPlayer.getClass(), "openMenu", this.simpleMenuProvider.getDeclaringClass());
        }
        Object result = open.invoke(serverPlayer, menuProvider);
        boolean opened = result instanceof java.util.OptionalInt optional && optional.isPresent();
        if (!opened) {
            activeChest.invoke(container, (Object) null);
        }
        return opened;
    }

    /** A {@code MenuConstructor} that ignores the requested layout and always builds six rows. */
    private Object menuConstructorFor(Object container) {
        return Proxy.newProxyInstance(
            Nms.class.getClassLoader(),
            new Class<?>[] { this.menuConstructorType },
            (proxy, method, args) -> switch (method.getName()) {
                case "createMenu" -> this.sixRows.invoke(null, args[0], args[1], container);
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "SixRowEnderChestMenuConstructor";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    void angerNearbyPiglins(Player player, Block chest) {
        if (this.angerNearbyPiglins == null) {
            return;
        }
        try {
            Object serverPlayer = serverPlayer(player);
            // 1.21.2+ takes (ServerLevel, Player, boolean); older builds take (Player, boolean).
            Object[] args = this.angerNearbyPiglins.getParameterCount() == 3
                ? new Object[] { level(chest.getWorld()), serverPlayer, true }
                : new Object[] { serverPlayer, true };
            this.angerNearbyPiglins.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Cosmetic only.
        }
    }

    private Object serverPlayer(Player player) throws ReflectiveOperationException {
        Method handle = this.craftPlayerGetHandle;
        if (handle == null) {
            this.craftPlayerGetHandle = handle = resolve(player, "getHandle");
        }
        return handle.invoke(player);
    }

    private Object level(org.bukkit.World world) throws ReflectiveOperationException {
        Method handle = this.craftWorldGetHandle;
        if (handle == null) {
            this.craftWorldGetHandle = handle = resolve(world, "getHandle");
        }
        return handle.invoke(world);
    }

    private Object enderChestBlockEntity(Block chest) throws ReflectiveOperationException {
        Object level = level(chest.getWorld());
        Object pos = this.blockPos.newInstance(chest.getX(), chest.getY(), chest.getZ());
        Method getter = this.getBlockEntity;
        if (getter == null) {
            this.getBlockEntity =
                getter = findSingleArg(level.getClass(), "getBlockEntity", this.blockPos.getDeclaringClass());
        }
        return getter.invoke(level, pos);
    }

    /**
     * Finds a one-argument method by name, optionally requiring that it accepts {@code argType}.
     * The type check disambiguates overloads, whose order from {@link Class#getMethods()} is
     * unspecified.
     */
    private static Method findSingleArg(Class<?> owner, String name, Class<?> argType)
        throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)
                && method.getParameterCount() == 1
                && (argType == null || method.getParameterTypes()[0].isAssignableFrom(argType))) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "/1");
    }

    // ------------------------------------------------------------------ playerdata NBT

    /** Reads and decompresses a {@code playerdata/<uuid>.dat} file. Safe to call off the main thread. */
    Object readPlayerData(Path file) throws ReflectiveOperationException {
        return this.readCompressed.invoke(null, file, this.nbtAccounter);
    }

    /** {@code CompoundTag#get(String)}; {@code null} when the key is absent. */
    Object tag(Object compoundTag, String key) throws ReflectiveOperationException {
        return this.compoundGet.invoke(compoundTag, key);
    }

    /**
     * Decodes one {@code EnderItems} entry with the game's own {@code ItemStack.CODEC}.
     *
     * <p>The entry layout differs between versions — {@code Slot} as a byte before 1.21.5, {@code slot}
     * as an int after — but in both the stack itself is inlined into the same compound by
     * {@code ItemStack.MAP_CODEC}, and a record codec ignores the extra slot key. So one call covers
     * every version the plugin supports.
     */
    Optional<Object> decodeItem(Object itemTag) throws ReflectiveOperationException {
        Object parsed = this.codecParse.invoke(this.itemStackCodec, this.registryOps, itemTag);
        Object result = this.dataResultResult.invoke(parsed);
        @SuppressWarnings("unchecked")
        Optional<Object> stack = (Optional<Object>) result;
        return stack;
    }

    /** Reads an int out of any numeric tag, across the {@code getAsInt} to {@code intValue} rename. */
    static int numeric(Object tag) {
        for (String name : new String[] { "intValue", "getAsInt", "asInt" }) {
            try {
                Object value = tag.getClass().getMethod(name).invoke(tag);
                if (value instanceof Number number) {
                    return number.intValue();
                }
                if (value instanceof Optional<?> optional && optional.orElse(null) instanceof Number number) {
                    return number.intValue();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next spelling.
            }
        }
        throw new IllegalStateException("Cannot read a number out of " + tag.getClass().getName());
    }
}
