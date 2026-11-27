package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.common.ShapeRegistry;
import com.Goderium.StructureBuilderNH.StructureBuilderNH;
import com.Goderium.StructureBuilderNH.util.Bounds;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.*;
import com.mojang.authlib.GameProfile;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.util.HatchElementBuilder;
import gregtech.api.util.GlassTier;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.GTDummyWorld;
import gregtech.common.blocks.ItemMachines;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBeacon;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.*;
import net.minecraft.world.chunk.*;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.*;
import java.util.function.IntPredicate;

/** The native construct call describes the pieces. Only this isolated world defers their walks. */
public final class Blueprint extends GTDummyWorld {
    final List<Section> sections = new ArrayList<>();
    final BrowserChannels browserChannels = new BrowserChannels();
    private BrowserListeners browserListeners;
    long browserWalks;
    private final Map<ChunkPosition, Section> index = new HashMap<>();
    private final Deque<Piece<?>> pieces = new ArrayDeque<>();
    private final Map<IStructureElement<?>, Integer> hatches = new IdentityHashMap<>();
    private final Map<IStructureElement<?>, Integer> hatchDirections = new IdentityHashMap<>();
    private final HatchNames hatchLabels = new HatchNames();
    private final ChannelUpgrade upgrades = new ChannelUpgrade(this);
    private int ambiguousHatches = -1;
    private final Map<IStructureElement<?>, Boolean> predicateCache = new IdentityHashMap<>();
    private final Map<Object, List<PieceInputs>> inputsByContext = new IdentityHashMap<>();
    private int nextInputId;
    final List<MaterialChoice> choices = new ArrayList<>();
    private final Map<String, MaterialChoice> choiceCache = new HashMap<>();
    private final Map<ForgeDirection, Map<String, Cell>> roleCells = new EnumMap<>(ForgeDirection.class);
    private final Map<String, Integer> pieceScopes = new HashMap<>();
    final Materials bill = new Materials();
    final ItemStack trigger;
    final int mainChannel;
    final int x, y, z;
    final IMetaTileEntity controller;
    final AutoPlaceEnvironment env;
    private final IntPredicate initiallyConfirmed;
    Bounds bounds;
    int size;
    long shapeEntries, shapeFingerprint;
    private int[] positions = new int[1024];
    private int sx, sy, sz;
    private Block sample = Blocks.air;
    private int meta;
    private boolean wroteAir;
    private TileEntity host, tile;
    boolean ready;
    int solidityRevision;

    static final class Section {
        final int x, y, z, id;
        final Cell[] cells = new Cell[4096];
        final int[] ordinals = new int[4096];
        final long[] solid = new long[64], hatch = new long[64];
        final long[] confirmed = new long[64];
        private BitSet airChoiceSlots;
        BitSet fluidSlots;

        Section(int x, int y, int z, int id) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
        }

        BitSet structureCells() {
            BitSet members = BitSet.valueOf(solid);
            members.or(BitSet.valueOf(hatch));
            // A material slot may display air and still accept a real structure block.
            if (airChoiceSlots != null) members.or(airChoiceSlots);
            return members;
        }
    }

    static final class Cell {
        final IStructureElement<Object> element;
        final ItemStack item;
        final Block block;
        final int meta;
        final boolean air, hatch, inscribedRitual, nativePredicate;
        final int dots;
        ForgeDirection front = ForgeDirection.UNKNOWN;
        MaterialChoice choice;
        ChannelUpgrade.Proof channelProof;
        private final boolean previewFluid;
        private PieceInputs inputs;
        String piece = "";
        String role = "";
        char symbol;
        List<Cell> constraints;
        Cell hatchSource;

        ForgeDirection previewFront(ForgeDirection fallback) {
            return front == ForgeDirection.UNKNOWN ? fallback : front;
        }

        boolean isFluid() {
            return previewFluid;
        }

        ItemStack pieceTrigger() {
            return inputs.trigger;
        }

        Cell(IStructureElement<Object> e, ItemStack s, Block b, int m, boolean a, int dots, boolean nativePredicate) {
            element = e;
            previewFluid = StructureFluids.isFluid(b);
            item = previewFluid || StructureFluids.isFluidItem(s) ? null : s;
            block = b;
            inscribedRitual = isInscribedRitual(b, m);
            meta = m;
            air = a;
            this.dots = dots;
            hatch = dots >= 0;
            this.nativePredicate = nativePredicate;
        }
    }

    static IMetaTileEntity prototype(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemMachines)) return null;
        int id = stack.getMetadata();
        if (id <= 0 || id >= GregTechAPI.METATILEENTITIES.length) return null;
        IMetaTileEntity m = GregTechAPI.METATILEENTITIES[id];
        return m instanceof IConstructable ? m : null;
    }

    /** Shared logical type translation keys; callers must not modify the returned array. */
    String[] hatchNames(Cell cell) {
        if (cell != null && cell.hatchSource != null) cell = cell.hatchSource;
        return hatchLabels.resolve(cell == null || !cell.hatch ? null : cell.element, controller);
    }

    /** Same-dot elements with distinct logical categories cannot share one tooltip-only label. */
    int ambiguousHatchDots() {
        if (!ready) return 0;
        if (ambiguousHatches >= 0) return ambiguousHatches;
        int ambiguous = 0;
        Map<Integer, String[]> categories = new HashMap<>();
        for (Map.Entry<IStructureElement<?>, Integer> entry : hatches.entrySet()) {
            int dots = entry.getValue();
            if (dots < 0 || dots > 16) continue;
            String[] names = hatchLabels.resolve(entry.getKey(), controller);
            String[] previous = categories.putIfAbsent(dots, names);
            if (previous != null && !Arrays.equals(previous, names)) ambiguous |= 1 << dots;
        }
        ambiguousHatches = ambiguous;
        return ambiguous;
    }

    static Blueprint read(ItemStack target, ItemStack trigger, ExtendedFacing facing, int x, int y, int z,
            EntityPlayer actor, IntPredicate initiallyConfirmed, boolean actual) {
        return read(target, trigger, facing, x, y, z, actor, initiallyConfirmed, true, actual);
    }

    static Blueprint read(ItemStack target, ItemStack trigger, ExtendedFacing facing, int x, int y, int z,
            EntityPlayer actor, IntPredicate initiallyConfirmed, boolean useSurvivalConstruct, boolean actual) {
        Blueprint plan = new Blueprint(target, trigger.copy(), facing, x, y, z, actor, initiallyConfirmed, actual);
        if (useSurvivalConstruct && plan.controller instanceof ISurvivalConstructable) {
            try {
                EntityPlayer reader = new PreviewPlayer(plan);
                ISurvivalBuildEnvironment capture = ISurvivalBuildEnvironment.create(plan.env.getSource(), reader);
                ((ISurvivalConstructable) plan.controller).survivalConstruct(plan.trigger.copy(), 256, capture);
                if (!plan.pieces.isEmpty() || plan.size > 1) return plan;
            } catch (RuntimeException | LinkageError failure) {
                StructureBuilderNH.LOG.warn("Falling back to construct for {}", plan.controller.getClass().getName(), failure);
            }
            // A rejected survival reader may already have written blocks or changed its controller.
            plan = new Blueprint(target, trigger.copy(), facing, x, y, z, actor, initiallyConfirmed, actual);
        }
        ((IConstructable) plan.controller).construct(plan.trigger, false);
        if (plan.pieces.isEmpty() && plan.size <= 1) throw new IllegalArgumentException("Controller supplied no structure");
        return plan;
    }

    private Blueprint(ItemStack target, ItemStack trigger, ExtendedFacing facing, int x, int y, int z,
            EntityPlayer actor, IntPredicate initiallyConfirmed, boolean actual) {
        this.initiallyConfirmed = initiallyConfirmed;
        this.x = x;
        this.y = y;
        this.z = z;
        this.trigger = trigger;
        mainChannel = trigger.stackSize;
        bounds = Bounds.point(x, y, z);
        env =
                AutoPlaceEnvironment.fromLegacy(
                        (p, s, n) -> Collections.emptyMap(), actor, chat -> {});
        int m = prototype(target).getTileEntityBaseType();
        host = GregTechAPI.sBlockMachines.createTileEntity(this, m);
        setTileEntity(x, y, z, host);
        IGregTechTileEntity base = (IGregTechTileEntity) host;
        base.setInitialValuesAsNBT(target.hasTagCompound() ? (NBTTagCompound) target.getTagCompound().copy() : null,
                (short) target.getMetadata());
        base.setFrontFacing(facing.getDirection());
        controller = base.getMetaTileEntity();
        if (controller instanceof IAlignment) ((IAlignment) controller).setExtendedFacing(facing);
        if (!actual && controller instanceof INEIPreviewModifier)
            ((INEIPreviewModifier) controller).onPreviewConstruct(trigger);
        Cell anchor = new Cell(null, target, GregTechAPI.sBlockMachines, m, false, -1, false);
        anchor.front = facing.getDirection();
        put(x, y, z, anchor);
    }

    /** Native readers may inspect their actor. Keep its world and inventory inside this preview. */
    private static final class PreviewPlayer extends EntityPlayer {
        private static final GameProfile PROFILE =
                new GameProfile(UUID.fromString("3f479cef-3f7e-4281-a7d9-98905d271c6d"), "[StructureView]");

        PreviewPlayer(Blueprint world) {
            super(world, PROFILE);
            setPosition(world.x + 0.5, world.y, world.z + 0.5);
        }

        @Override public void addChatMessage(IChatComponent message) {}
        @Override public boolean canCommandSenderUseCommand(int level, String command) { return false; }
        @Override public ChunkCoordinates getCommandSenderPosition() {
            Blueprint world = (Blueprint) worldObj;
            return new ChunkCoordinates(world.x, world.y, world.z);
        }
    }

    public <T> void capture(
            IStructureElement<T>[] elements,
            ExtendedFacing facing,
            int px,
            int py,
            int pz,
            int a,
            int b,
            int c,
            IStructureWalker<T> walker) {
        capture(elements, facing, px, py, pz, a, b, c, walker, controller, trigger);
    }

    public <T> void captureSurvival(IStructureElement<T>[] elements, ExtendedFacing facing, int px, int py, int pz,
            int a, int b, int c, T context, ItemStack pieceTrigger) {
        // Machines can reuse and mutate the same trigger between native piece calls.
        ItemStack snapshot = ItemStack.copyItemStack(pieceTrigger);
        IStructureWalker<T> sampler = (element, world, x, y, z, da, db, dc) -> {
            element.placeBlock(context, world, x, y, z, snapshot);
            return true;
        };
        capture(elements, facing, px, py, pz, a, b, c, sampler, context, snapshot);
    }

    private <T> void capture(IStructureElement<T>[] elements, ExtendedFacing facing, int px, int py, int pz,
            int a, int b, int c, IStructureWalker<T> walker, Object context, ItemStack pieceTrigger) {
        shapeEntries += elements.length;
        long piece = Objects.hash(System.identityHashCode(elements), facing, px, py, pz, a, b, c);
        shapeFingerprint = 31 * shapeFingerprint + piece;
        try {
            bounds = bounds.union(ShapeRegistry.bounds(elements, facing, px, py, pz, a, b, c));
        } catch (IllegalStateException unknown) {
            /* Explicit elements will extend the bounds as they arrive. */
        }
        ShapeRegistry.Shape shape = ShapeRegistry.get(elements);
        PieceInputs inputs = pieceInputs(context, pieceTrigger);
        int scope = shape == null ? 0 : inputs.shapeScopes.computeIfAbsent(shape,
                value -> pieceScopes.merge(value.piece, 1, Integer::sum) - 1);
        pieces.add(new Piece<>(elements, facing, px, py, pz, a, b, c, walker, scope, inputs));
    }

    private PieceInputs pieceInputs(Object context, ItemStack trigger) {
        List<PieceInputs> known = inputsByContext.computeIfAbsent(context, ignored -> new ArrayList<>());
        for (PieceInputs inputs : known)
            if (ItemStack.areItemStacksEqual(inputs.trigger, trigger)) return inputs;
        PieceInputs inputs = new PieceInputs(context, trigger, nextInputId++);
        known.add(inputs);
        return inputs;
    }

    private static final class PieceInputs {
        final Object context;
        final ItemStack trigger;
        final int id;
        final Map<ShapeRegistry.Shape, Integer> shapeScopes = new IdentityHashMap<>();
        final Map<IStructureElement<?>, Cell> last = new IdentityHashMap<>();
        final Map<IStructureElement<?>, ChannelUpgrade.Proof> channelProofs = new IdentityHashMap<>();
        final Map<IStructureElement<?>, MaterialChoice.NativeMaterial> materialDefaults = new IdentityHashMap<>();

        PieceInputs(Object context, ItemStack trigger, int id) {
            this.context = context;
            this.trigger = trigger;
            this.id = id;
        }
    }

    private static final class Piece<T> {
        final IStructureElement<T>[] elements;
        final ExtendedFacing facing;
        final int x, y, z, a, b, c;
        final int[] abc, xyz = new int[3];
        final IStructureWalker<T> walker;
        final PieceInputs inputs;
        final ShapeRegistry.Shape shape;
        final IStructureDefinition<?> definition;
        final int scope;
        int cursor;

        Piece(
                IStructureElement<T>[] e,
                ExtendedFacing f,
                int x,
                int y,
                int z,
                int a,
                int b,
                int c,
                IStructureWalker<T> w, int scope, PieceInputs inputs) {
            elements = e;
            shape = ShapeRegistry.get(e);
            definition = shape == null ? null : shape.definition();
            this.scope = scope;
            this.inputs = inputs;
            facing = f;
            this.x = x;
            this.y = y;
            this.z = z;
            this.a = -a;
            this.b = -b;
            this.c = -c;
            abc = new int[] {-a, -b, -c};
            walker = w;
        }

        void next(Blueprint world) {
            int elementIndex = cursor++;
            IStructureElement<T> e = elements[elementIndex];
            if (e.isNavigating()) {
                abc[0] = (e.resetA() ? a : abc[0]) + e.getStepA();
                abc[1] = (e.resetB() ? b : abc[1]) + e.getStepB();
                abc[2] = (e.resetC() ? c : abc[2]) + e.getStepC();
                return;
            }
            facing.getWorldOffset(abc, xyz);
            int px = x + xyz[0], py = y + xyz[1], pz = z + xyz[2];
            if (px != world.x || py != world.y || pz != world.z) {
                world.sx = px;
                world.sy = py;
                world.sz = pz;
                world.wroteAir = false;
                world.sample = Blocks.air;
                world.meta = 0;
                world.tile = null;
                world.browserChannels.begin();
                try {
                    walker.visit(e, world, px, py, pz, abc[0], abc[1], abc[2]);
                    char symbol = shape == null ? '\0' : shape.symbol(elementIndex);
                    world.record(e, px, py, pz, this, shape == null ? "" : shape.piece, symbol,
                            symbol == '\0' ? null : scope == 0 ? shape.role(symbol) : "scope:" + scope + ":" + shape.role(symbol));
                } finally {
                    world.browserChannels.end();
                }
            }
            abc[0]++;
        }
    }

    void advance(long deadline) {
        for (int n = 0; n < 8192 && !pieces.isEmpty() && System.nanoTime() < deadline; n++) {
            Piece<?> piece = pieces.peek();
            if (piece.cursor < piece.elements.length) { piece.next(this); browserWalks++; }
            if (piece.cursor == piece.elements.length) pieces.remove();
        }
        ready = pieces.isEmpty();
    }

    void addBrowserObserver(java.util.function.Consumer<Section> observer) {
        if (browserListeners == null) browserListeners = new BrowserListeners();
        browserListeners.add(observer);
    }

    void removeBrowserObserver(java.util.function.Consumer<Section> observer) {
        if (browserListeners != null && browserListeners.remove(observer)) browserListeners = null;
    }

    @SuppressWarnings("unchecked")
    private void record(IStructureElement<?> raw, int px, int py, int pz, Piece<?> walk, String piece, char symbol, String role) {
        IStructureElement<Object> e = (IStructureElement<Object>) raw;
        PieceInputs inputs = walk.inputs;
        int dots = hatches.computeIfAbsent(e, Blueprint::hatchDots);
        ForgeDirection front = tile instanceof IGregTechTileEntity
                ? ((IGregTechTileEntity) tile).getFrontFacing() : ForgeDirection.UNKNOWN;
        if (dots >= 0 && walk.definition != null) {
            int allowed = hatchDirections.computeIfAbsent(e, Blueprint::allowedHatchDirections);
            ForgeDirection inferred = hatchFront(walk.definition, piece, walk.facing, walk.abc[0] - walk.a,
                    walk.abc[1] - walk.b, walk.abc[2] - walk.c, allowed);
            if (inferred != ForgeDirection.UNKNOWN) front = inferred;
        }
        boolean nativePredicate = predicateCache.computeIfAbsent(e, MaterialChoice::hasNativePredicate);
        // The construction default can be air even when the native structure predicate rejects it.
        boolean sampledFluid = StructureFluids.isFluid(sample);
        boolean air = !sampledFluid && (allowsAir(e) || wroteAir && sample == Blocks.air
                && e.couldBeValid(inputs.context, this, px, py, pz, inputs.trigger));
        ItemStack item = null;
        ItemStack constructedItem = null;
        if (!air && !sampledFluid) {
            if (tile instanceof IGregTechTileEntity
                    && ((IGregTechTileEntity) tile).getMetaTileEntity() != null)
                item = ((IGregTechTileEntity) tile).getMetaTileEntity().getStackForm(1);
            else if (sample != Blocks.air
                    && sample != StructureLibAPI.getBlockHint()
                    && Item.getItemFromBlock(sample) != null)
                item =
                        IStructureElement.BlocksToPlace.create(sample, meta)
                                .getStacks()
                                .iterator()
                                .next();
            constructedItem = item;
            if (e == StructureUtility.notAir()) item = new ItemStack(Blocks.cobblestone);
            if (item == null || !(item.getItem() instanceof ItemBlock)) {
                IStructureElement.BlocksToPlace choices =
                        e.getBlocksToPlace(inputs.context, this, px, py, pz, inputs.trigger, env);
                if (choices != null && choices.getStacks() != null)
                    for (ItemStack s : choices.getStacks()) {
                        if (s != null && s.getItem() instanceof ItemBlock) {
                            item = s;
                            break;
                        }
                    }
                if (item == null && choices != null && hatches.computeIfAbsent(e, Blueprint::hatchDots) >= 0) {
                    Cell known = inputs.last.get(e);
                    if (known != null
                            && known.item != null
                            && choices.getPredicate().test(known.item)) item = known.item;
                    else
                        for (IMetaTileEntity m : GregTechAPI.METATILEENTITIES)
                            if (m instanceof MTEHatch) {
                                ItemStack s = m.getStackForm(1);
                                if (choices.getPredicate().test(s)) {
                                    item = s;
                                    break;
                                }
                            }
                }
            }
        }
        Block block = sample;
        int blockMeta = meta;
        if (!air && nativePredicate && (!sampledFluid
                || !e.couldBeValid(inputs.context, this, px, py, pz, inputs.trigger))) {
            if (!inputs.materialDefaults.containsKey(e))
                inputs.materialDefaults.put(e, MaterialChoice.nativeDefault(
                        this, e, constructedItem, block, blockMeta, px, py, pz, inputs.context, inputs.trigger));
            MaterialChoice.NativeMaterial material = inputs.materialDefaults.get(e);
            if (material != null) {
                item = material.item;
                block = material.block;
                blockMeta = material.meta;
            }
        }
        if ((block == Blocks.air || block == StructureLibAPI.getBlockHint()) && item != null
                && StructureFluids.isFluid(Block.getBlockFromItem(item.getItem()))) {
            block = Block.getBlockFromItem(item.getItem());
            blockMeta = item.getItem().getMetadata(item.getMetadata());
        }
        if (StructureFluids.isFluid(block)) item = null;
        // Inscribed ritual stones have no obtainable item variant. Charge a blank and inscribe after placement.
        if (isInscribedRitual(block, blockMeta))
            item = new ItemStack(block, 1, 0);
        if (role == null) {
            role = "cell:" + (px-x) + "," + (py-y) + "," + (pz-z);
            if (inputs.id != 0) role = "inputs:" + inputs.id + ":" + role;
        }
        Map<String, Cell> orientedCells = roleCells.computeIfAbsent(front, ignored -> new HashMap<>());
        Cell cell = orientedCells.get(role);
        if (cell == null || cell.inputs != inputs || cell.element != e || cell.block != block || cell.meta != blockMeta
                || cell.air != air || !Materials.equal(cell.item, item)) {
            cell = new Cell(
                    e,
                    item,
                    block,
                    blockMeta,
                    air,
                    dots,
                    nativePredicate);
            cell.front = front;
            cell.inputs = inputs;
            if (!inputs.channelProofs.containsKey(e))
                inputs.channelProofs.put(e, upgrades.observe(e, inputs.context, inputs.trigger));
            cell.channelProof = inputs.channelProofs.get(e);
            cell.piece = piece;
            cell.role = role;
            cell.symbol = symbol;
            if (!choiceCache.containsKey(role)) {
                MaterialChoice group = MaterialChoice.discover(this, cell, px, py, pz, inputs.context, inputs.trigger);
                choiceCache.put(role, group);
                if (group != null) choices.add(group);
            }
            cell.choice = choiceCache.get(role);
            inputs.last.put(e, cell);
            orientedCells.put(role, cell);
        }
        put(px, py, pz, cell);
    }

    private static boolean isInscribedRitual(Block block, int meta) {
        return meta >= 1
                && meta <= 6
                && "AWWayofTime:ritualStone".equals(Block.blockRegistry.getNameForObject(block));
    }

    static boolean allowsAir(IStructureElement<?> e) {
        if (e == null) return false;
        if (e == StructureUtility.isAir()) return true;
        if (e instanceof IStructureElementChain)
            for (IStructureElement<?> child : ((IStructureElementChain<?>) e).fallbacks())
                if (allowsAir(child)) return true;
        return false;
    }

    private static int allowedHatchDirections(IStructureElement<?> element) {
        if (element instanceof IStructureElementChain) {
            for (IStructureElement<?> child : ((IStructureElementChain<?>) element).fallbacks()) {
                int mask = allowedHatchDirections(child);
                if (mask >= 0) return mask;
            }
        }
        HatchElementBuilder<?> builder = HatchNames.builder(element);
        if (builder == null) return -1;
        int mask = 0;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
            if (!builder.getDisallowedDirection().contains(side)) mask |= side.flag;
        return mask;
    }

    // Match GT HatchElementBuilder.survivalPlaceBlock, including its horizontal preference
    // and its UP preference when both vertical faces are free. ABC's B axis points down.
    static ForgeDirection hatchFront(IStructureDefinition<?> definition, String piece, ExtendedFacing facing,
            int a, int b, int c, int allowed) {
        int exposed = 0;
        if (allowed <= 0) return ForgeDirection.UNKNOWN;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if ((allowed & side.flag) == 0
                    || definition.isContainedInStructure(piece, a + side.offsetX, b - side.offsetY, c + side.offsetZ)) continue;
            exposed |= facing.getWorldDirection(side.offsetY == 0 ? side : side.getOpposite()).flag;
        }
        int horizontal = exposed & ~(ForgeDirection.UP.flag | ForgeDirection.DOWN.flag);
        if (horizontal != 0) return ForgeDirection.getOrientation(Integer.numberOfTrailingZeros(horizontal));
        if ((exposed & ForgeDirection.UP.flag) != 0) return ForgeDirection.UP;
        return exposed == 0 ? ForgeDirection.UNKNOWN : ForgeDirection.DOWN;
    }

    // GT's structure element captures its builder; getHint() is the native one-based dot count.
    private static int hatchDots(IStructureElement<?> e) {
        if (e.getClass().getEnclosingClass() == HatchElementBuilder.class) {
            HatchElementBuilder<?> builder = HatchNames.builder(e);
            return builder == null ? 0 : Math.max(0, Math.min(16, builder.getHint()));
        }
        if (e.getClass().getEnclosingClass() == gregtech.api.util.GTStructureUtility.class
                && e.getClass().getEnclosingMethod() != null
                && "ofHatchAdder".equals(e.getClass().getEnclosingMethod().getName())) {
            try {
                java.lang.reflect.Field block = e.getClass().getDeclaredField("val$aHintBlock");
                java.lang.reflect.Field meta = e.getClass().getDeclaredField("val$aHintMeta");
                block.setAccessible(true);
                meta.setAccessible(true);
                if (block.get(e) == StructureLibAPI.getBlockHint())
                    return Math.max(0, Math.min(16, meta.getInt(e) + 1));
            } catch (ReflectiveOperationException ignored) {
            }
            return 0;
        }
        if (e instanceof IStructureElementChain) {
            for (IStructureElement<?> child : ((IStructureElementChain<?>) e).fallbacks()) {
                int dots = hatchDots(child);
                if (dots >= 0) return dots;
            }
        }
        return -1;
    }

    private void put(int px, int py, int pz, Cell cell) {
        ChunkPosition key = new ChunkPosition(px >> 4, py >> 4, pz >> 4);
        Section s = index.get(key);
        if (s == null) {
            s = new Section(key.chunkPosX, key.chunkPosY, key.chunkPosZ, sections.size());
            sections.add(s);
            index.put(key, s);
        }
        int i = slot(px, py, pz);
        Cell old = s.cells[i];
        if (old == null) {
            if (size == positions.length) positions = Arrays.copyOf(positions, size * 2);
            if (initiallyConfirmed.test(size)) s.confirmed[i >> 6] |= 1L << (i & 63);
            s.ordinals[i] = size;
            positions[size++] = s.id << 12 | i;
        } else if (old.choice != null) old.choice.count--;
        else if (old.item != null) bill.add(old.item, -1);
        if (old != null && old.element != null) {
            List<Cell> constraints = new ArrayList<>();
            if (old.constraints == null) constraints.add(old);
            else constraints.addAll(old.constraints);
            if (cell.constraints == null) constraints.add(cell);
            else constraints.addAll(cell.constraints);
            Cell primary = old.choice != null ? old : cell;
            // One piece may define the material choice and the other the hatch at this same position.
            Cell hatch = primary;
            if (!hatch.hatch) hatch = old.hatch ? old : cell;
            Cell combined = new Cell(primary.element, primary.item, primary.block, primary.meta,
                    primary.air, hatch.dots, primary.nativePredicate);
            combined.front = hatch.hatch ? hatch.front : primary.front;
            if (hatch.hatch) combined.hatchSource = hatch.hatchSource == null ? hatch : hatch.hatchSource;
            combined.inputs = primary.inputs;
            combined.piece = primary.piece;
            combined.role = primary.role;
            combined.symbol = primary.symbol;
            combined.choice = primary.choice;
            combined.constraints = constraints;
            cell = combined;
        }
        s.cells[i] = cell;
        setFluid(s, i, cell.isFluid());
        browserChannels.put(old, cell);
        if (cell.choice != null) cell.choice.count++;
        else if (cell.item != null) bill.add(cell.item, 1);
        long bit = 1L << (i & 63);
        s.solid[i >> 6] = (s.solid[i >> 6] & ~bit) | (cell.air ? 0 : bit);
        s.hatch[i >> 6] = (s.hatch[i >> 6] & ~bit) | (cell.hatch ? bit : 0);
        if (cell.choice != null && (cell.air || cell.choice.allowsAir)) {
            if (s.airChoiceSlots == null) s.airChoiceSlots = new BitSet();
            s.airChoiceSlots.set(i);
        } else if (s.airChoiceSlots != null) s.airChoiceSlots.clear(i);
        upgrades.record(old, cell, px, py, pz);
        if (browserListeners != null) browserListeners.changed(s);
        if (!bounds.contains(px, py, pz)) bounds = bounds.union(Bounds.point(px, py, pz));
    }

    static int slot(int x, int y, int z) {
        return (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
    }

    Section setConfirmed(int cursor, boolean value) {
        int pos = positions[cursor], i = pos & 4095;
        Section section = sections.get(pos >>> 12);
        long bit = 1L << (i & 63);
        if (value) section.confirmed[i >> 6] |= bit;
        else section.confirmed[i >> 6] &= ~bit;
        return section;
    }

    Cell at(int cursor, int[] xyz) {
        int pos = positions[cursor], i = pos & 4095;
        Section s = sections.get(pos >>> 12);
        xyz[0] = s.x * 16 + (i & 15);
        xyz[1] = s.y * 16 + (i >> 8);
        xyz[2] = s.z * 16 + (i >> 4 & 15);
        return s.cells[i];
    }

    int indexAt(int px, int py, int pz) {
        Section section = index.get(new ChunkPosition(px >> 4, py >> 4, pz >> 4));
        if (section == null || section.cells[slot(px, py, pz)] == null) return -1;
        return section.ordinals[slot(px, py, pz)];
    }

    boolean projectsBlock(int x, int y, int z) {
        if (this.x == x && this.y == y && this.z == z) return true;
        Section section = index.get(new ChunkPosition(x >> 4, y >> 4, z >> 4));
        if (section == null) return false;
        int slot = slot(x, y, z);
        return ((section.solid[slot >> 6] | section.hatch[slot >> 6]) & (1L << slot)) != 0
                && (section.fluidSlots == null || !section.fluidSlots.get(slot));
    }

    Cell cellAt(int px, int py, int pz) {
        Section section = index.get(new ChunkPosition(px >> 4, py >> 4, pz >> 4));
        return section == null ? null : section.cells[slot(px, py, pz)];
    }

    /** Actual supported native named channels, independent of the current world's blocks. */
    Set<String> upgradeChannels() { return upgrades.channels(); }

    Map<String, Integer> channelMaximums() { return upgrades.maximums(); }

    /** O(1), populated by the normal incremental blueprint walk; never queries a world or loads a chunk. */
    String upgradeChannel(Cell cell) { return upgrades.channel(cell); }

    ChannelUpgrade.Result upgrade(Cell cell, World world, int px, int py, int pz, Set<String> enabled) {
        return upgrades.inspect(cell, world, px, py, pz, enabled);
    }

    boolean identifiesOptionalSiteMaterial(Cell cell, World world, int x, int y, int z) {
        return upgrades.identifiesOptionalSiteMaterial(cell, world, x, y, z);
    }

    ChannelUpgrade.Proof optionalTieredBranch(Cell cell, IStructureElementChain<?> chain) {
        IStructureElement<?> solid = null;
        boolean air = false;
        for (IStructureElement<?> child : chain.fallbacks()) {
            if (child == StructureUtility.isAir()) air = true;
            else if (solid != null) return null;
            else solid = child;
        }
        return air && solid != null ? ChannelUpgrade.prove(solid, cell.inputs.context, cell.inputs.trigger) : null;
    }

    /** Native writes stay in this private world's one sampling slot and are restored before returning. */
    ChannelUpgrade.Material sampleUpgrade(Cell cell, ItemStack trigger, int px, int py, int pz, boolean allowsAir) {
        int ox = sx, oy = sy, oz = sz, om = meta;
        Block previous = sample;
        TileEntity previousTile = tile;
        boolean previousAir = wroteAir;
        try {
            sx = px; sy = py; sz = pz;
            sample = Blocks.air; meta = 0; tile = null; wroteAir = false;
            boolean placed = cell.element.placeBlock(cell.inputs.context, this, px, py, pz, trigger);
            if (sx != px || sy != py || sz != pz) return null;
            // isAir() records the air write but returns false from placeBlock.
            if (allowsAir && wroteAir && sample == Blocks.air) return new ChannelUpgrade.Material(Blocks.air, 0, null);
            if (!placed || sample == Blocks.air
                    || sample == StructureLibAPI.getBlockHint() || StructureFluids.isFluid(sample)
                    || tile != null || sample.hasTileEntity(meta)) return null;
            Block block = sample;
            int blockMeta = meta;
            IStructureElement.BlocksToPlace choices = cell.element.getBlocksToPlace(
                    cell.inputs.context, this, px, py, pz, trigger, env);
            if (choices == null || choices.getStacks() == null) return null;
            ItemStack item = null;
            for (ItemStack candidate : choices.getStacks()) {
                if (candidate == null || item != null || !(candidate.getItem() instanceof ItemBlock)
                        || candidate.hasTagCompound() || candidate.getMetadata() == 32767) return null;
                item = candidate;
            }
            if (item == null || Block.getBlockFromItem(item.getItem()) != block
                    || item.getItem().getMetadata(item.getMetadata()) != blockMeta) return null;
            return new ChannelUpgrade.Material(block, blockMeta, item);
        } finally {
            sx = ox; sy = oy; sz = oz; meta = om; sample = previous; tile = previousTile; wroteAir = previousAir;
        }
    }

    private static boolean setFluid(Section section, int slot, boolean fluid) {
        if (section.fluidSlots == null) {
            if (!fluid) return false;
            section.fluidSlots = new BitSet();
        }
        if (section.fluidSlots.get(slot) == fluid) return false;
        section.fluidSlots.set(slot, fluid);
        return true;
    }

    void displayChoice(int cursor, boolean air, boolean fluid) {
        int position = positions[cursor], slot = position & 4095;
        Section section = sections.get(position >>> 12);
        long bit = 1L << (slot & 63);
        long current = section.solid[slot >> 6];
        long updated = current & ~bit | (air ? 0 : bit);
        if (setFluid(section, slot, fluid) || current != updated) {
            section.solid[slot >> 6] = updated;
            solidityRevision++;
        }
    }

    boolean accepts(Cell cell, int option, int px, int py, int pz) {
        int ox = sx, oy = sy, oz = sz, om = meta;
        Block previous = sample;
        TileEntity previousTile = tile;
        try {
            sx = px; sy = py; sz = pz;
            sample = cell.choice.optionBlock(option);
            meta = cell.choice.optionMeta(option);
            tile = null;
            return matches(cell, this, px, py, pz);
        } finally {
            sx = ox; sy = oy; sz = oz; meta = om; sample = previous; tile = previousTile;
        }
    }

    boolean matches(Cell c, World world, int px, int py, int pz) {
        if (c.constraints != null) {
            for (Cell source : c.constraints) if (!matchesSingle(source, world, px, py, pz)) return false;
            return true;
        }
        return matchesSingle(c, world, px, py, pz);
    }

    /** Hatch adders can update both the controller's hatch lists and the hatch itself. Check copies. */
    boolean checkManualHatch(Cell cell, World world, int px, int py, int pz, ItemStack target, ExtendedFacing facing) {
        int ox = sx, oy = sy, oz = sz, om = meta;
        Block previous = sample;
        TileEntity oldHost = host, oldTile = tile;
        try {
            sx = px; sy = py; sz = pz;
            sample = world.getBlock(px, py, pz);
            meta = world.getBlockMetadata(px, py, pz);
            TileEntity installed = world.getTileEntity(px, py, pz);
            tile = null;
            if (installed != null) {
                net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
                installed.writeToNBT(tag);
                TileEntity copy = sample.createTileEntity(this, meta);
                if (copy == null) return false;
                setTileEntity(px, py, pz, copy);
                copy.readFromNBT(tag);
            }
            TileEntity detached = GregTechAPI.sBlockMachines.createTileEntity(this, prototype(target).getTileEntityBaseType());
            setTileEntity(x, y, z, detached);
            IGregTechTileEntity base = (IGregTechTileEntity) detached;
            base.setInitialValuesAsNBT(target.getTagCompound(), (short) target.getMetadata());
            base.setFrontFacing(facing.getDirection());
            IMetaTileEntity inspector = base.getMetaTileEntity();
            if (inspector instanceof IAlignment) ((IAlignment) inspector).setExtendedFacing(facing);
            if (inspector instanceof INEIPreviewModifier) ((INEIPreviewModifier) inspector).onPreviewConstruct(trigger);
            if (cell.constraints == null) return cell.element.check(inspector, this, px, py, pz);
            for (Cell source : cell.constraints)
                if (source.element != null && !source.element.check(inspector, this, px, py, pz)) return false;
            return true;
        } finally {
            host = oldHost; tile = oldTile; sx = ox; sy = oy; sz = oz; meta = om; sample = previous;
        }
    }

    private boolean matchesSingle(Cell c, World world, int px, int py, int pz) {
        Block b = world.getBlock(px, py, pz);
        if (b == Blocks.air) return c.air;
        if (c.element == StructureUtility.notAir()) return true;
        if (c.element != null && !c.element.couldBeValid(c.inputs.context, world, px, py, pz, c.inputs.trigger))
            return false;
        if (c.choice != null) return c.choice.allowsAir || c.choice.indexAt(world, px, py, pz) >= 0;
        if (c.nativePredicate) return true;
        if (c.element != null && c.element.getClass().getEnclosingClass() == GTStructureUtility.class
                && c.element.getClass().getEnclosingMethod() != null
                && "triggerItemTransform".equals(c.element.getClass().getEnclosingMethod().getName())
                && GlassTier.getGlassBlockTier(c.block, c.meta) != null) return true;
        TileEntity te = world.getTileEntity(px, py, pz);
        // The pack replaces vanilla beacons in the live world. Honor elements that accept the
        // replacement.
        if (c.block == Blocks.beacon
                && b != c.block
                && b instanceof BlockBeacon
                && te instanceof TileEntityBeacon
                && c.element != null) {
            return c.element.check(c.inputs.context, world, px, py, pz);
        }
        if (te instanceof IGregTechTileEntity) {
            IGregTechTileEntity gt = (IGregTechTileEntity) te;
            if (c.hatch && gt.getMetaTileEntity() instanceof MTEHatch) return true;
            if (c.item != null && c.item.getItem() instanceof ItemMachines)
                return gt.getMetaTileID() == c.item.getMetadata();
        }
        if (c.item != null
                && (c.block == Blocks.air || c.block == StructureLibAPI.getBlockHint())) {
            return Item.getItemFromBlock(b) == c.item.getItem()
                    && b.damageDropped(world.getBlockMetadata(px, py, pz)) == c.item.getMetadata();
        }
        return c.air && c.element != StructureUtility.isAir() && allowsAir(c.element)
                || b == c.block && world.getBlockMetadata(px, py, pz) == c.meta;
    }

    @Override
    public boolean blockExists(int px, int py, int pz) {
        return true;
    }

    @Override
    public Chunk getChunkFromChunkCoords(int px, int pz) {
        return new EmptyChunk(this, px, pz);
    }

    @Override
    public Block getBlock(int px, int py, int pz) {
        if (px == x && py == y && pz == z && host != null) return GregTechAPI.sBlockMachines;
        return px == sx && py == sy && pz == sz ? sample : Blocks.air;
    }

    @Override
    public int getBlockMetadata(int px, int py, int pz) {
        return px == sx && py == sy && pz == sz ? meta : 0;
    }

    @Override
    public boolean setBlock(int px, int py, int pz, Block b, int m, int flags) {
        sx = px;
        sy = py;
        sz = pz;
        wroteAir = b == Blocks.air;
        sample = b;
        meta = m;
        tile = null;
        if (b.hasTileEntity(m)) {
            TileEntity te = b.createTileEntity(this, m);
            if (te != null) setTileEntity(px, py, pz, te);
        }
        return true;
    }

    @Override
    public void setTileEntity(int px, int py, int pz, TileEntity te) {
        te.setWorldObj(this);
        te.xCoord = px;
        te.yCoord = py;
        te.zCoord = pz;
        if (px == x && py == y && pz == z) host = te;
        else tile = te;
    }

    @Override
    public TileEntity getTileEntity(int px, int py, int pz) {
        if (px == x && py == y && pz == z) return host;
        return px == sx && py == sy && pz == sz ? tile : null;
    }

    @Override
    public void removeTileEntity(int px, int py, int pz) {
        if (px == sx && py == sy && pz == sz) tile = null;
    }
}
