package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.common.ShapeRegistry;
import com.Goderium.StructureBuilderNH.util.Bounds;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.*;

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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.world.*;
import net.minecraft.world.chunk.*;

import java.util.*;
import java.util.function.IntPredicate;

/** The native construct call describes the pieces. Only this isolated world defers their walks. */
public final class Blueprint extends GTDummyWorld {
    final List<Section> sections = new ArrayList<>();
    private final Map<ChunkPosition, Section> index = new HashMap<>();
    private final Deque<Piece<?>> pieces = new ArrayDeque<>();
    private final Map<IStructureElement<?>, Integer> hatches = new IdentityHashMap<>();
    private final HatchNames hatchLabels = new HatchNames();
    private int ambiguousHatches = -1;
    private final Map<IStructureElement<?>, Cell> last = new IdentityHashMap<>();
    private final Map<IStructureElement<?>, Boolean> predicateCache = new IdentityHashMap<>();
    private final Map<IStructureElement<?>, MaterialChoice.NativeMaterial> materialDefaults = new IdentityHashMap<>();
    final List<MaterialChoice> choices = new ArrayList<>();
    private final Map<String, MaterialChoice> choiceCache = new HashMap<>();
    private final Map<String, Cell> roleCells = new HashMap<>();
    private final Map<ShapeRegistry.Shape, Integer> shapeScopes = new IdentityHashMap<>();
    private final Map<String, Integer> pieceScopes = new HashMap<>();
    final Materials bill = new Materials();
    final ItemStack trigger;
    final int x, y, z;
    final IMetaTileEntity controller;
    final AutoPlaceEnvironment env;
    private final IntPredicate initiallyConfirmed;
    Bounds bounds;
    int size;
    private int[] positions = new int[1024];
    private int sx, sy, sz;
    private Block sample = Blocks.air;
    private int meta;
    private boolean wroteAir;
    private TileEntity host, tile;
    boolean ready;

    static final class Section {
        final int x, y, z, id;
        final Cell[] cells = new Cell[4096];
        final int[] ordinals = new int[4096];
        final long[] solid = new long[64], hatch = new long[64];
        final long[] confirmed = new long[64];

        Section(int x, int y, int z, int id) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
        }
    }

    static final class Cell {
        final IStructureElement<Object> element;
        final ItemStack item;
        final Block block;
        final int meta;
        final boolean air, hatch, inscribedRitual, nativePredicate;
        final int dots;
        MaterialChoice choice;
        String piece = "";
        String role = "";
        char symbol;
        List<Cell> constraints;

        Cell(IStructureElement<Object> e, ItemStack s, Block b, int m, boolean a, int dots, boolean nativePredicate) {
            element = e;
            item = s;
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

    Blueprint(
            ItemStack target,
            ItemStack trigger,
            ExtendedFacing facing,
            int x,
            int y,
            int z,
            EntityPlayer actor,
            IntPredicate initiallyConfirmed) {
        this.initiallyConfirmed = initiallyConfirmed;
        this.x = x;
        this.y = y;
        this.z = z;
        this.trigger = trigger;
        bounds = Bounds.point(x, y, z);
        env =
                AutoPlaceEnvironment.fromLegacy(
                        (p, s, n) -> Collections.emptyMap(), actor, chat -> {});
        int m = prototype(target).getTileEntityBaseType();
        host = GregTechAPI.sBlockMachines.createTileEntity(this, m);
        setTileEntity(x, y, z, host);
        IGregTechTileEntity base = (IGregTechTileEntity) host;
        base.setInitialValuesAsNBT(target.getTagCompound(), (short) target.getMetadata());
        base.setFrontFacing(facing.getDirection());
        controller = base.getMetaTileEntity();
        if (controller instanceof IAlignment) ((IAlignment) controller).setExtendedFacing(facing);
        if (controller instanceof INEIPreviewModifier)
            ((INEIPreviewModifier) controller).onPreviewConstruct(trigger);
        put(x, y, z, new Cell(null, target, GregTechAPI.sBlockMachines, m, false, -1, false));
        ((IConstructable) controller).construct(trigger, false);
        if (pieces.isEmpty() && size <= 1)
            throw new IllegalArgumentException("Controller supplied no structure");
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
        try {
            bounds = bounds.union(ShapeRegistry.bounds(elements, facing, px, py, pz, a, b, c));
        } catch (IllegalStateException unknown) {
            /* Explicit elements will extend the bounds as they arrive. */
        }
        ShapeRegistry.Shape shape = ShapeRegistry.get(elements);
        int scope = shape == null ? 0 : shapeScopes.computeIfAbsent(shape,
                value -> pieceScopes.merge(value.piece, 1, Integer::sum) - 1);
        pieces.add(new Piece<>(elements, facing, px, py, pz, a, b, c, walker, scope));
    }

    private static final class Piece<T> {
        final IStructureElement<T>[] elements;
        final ExtendedFacing facing;
        final int x, y, z, a, b, c;
        final int[] abc, xyz = new int[3];
        final IStructureWalker<T> walker;
        final ShapeRegistry.Shape shape;
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
                IStructureWalker<T> w, int scope) {
            elements = e;
            shape = ShapeRegistry.get(e);
            this.scope = scope;
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
                walker.visit(e, world, px, py, pz, abc[0], abc[1], abc[2]);
                char symbol = shape == null ? '\0' : shape.symbol(elementIndex);
                world.record(e, px, py, pz, shape == null ? "" : shape.piece, symbol,
                        symbol == '\0' ? null : scope == 0 ? shape.role(symbol) : "scope:" + scope + ":" + shape.role(symbol));
            }
            abc[0]++;
        }
    }

    void advance(long deadline) {
        for (int n = 0; n < 8192 && !pieces.isEmpty() && System.nanoTime() < deadline; n++) {
            Piece<?> piece = pieces.peek();
            if (piece.cursor < piece.elements.length) piece.next(this);
            if (piece.cursor == piece.elements.length) pieces.remove();
        }
        ready = pieces.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void record(IStructureElement<?> raw, int px, int py, int pz, String piece, char symbol, String role) {
        IStructureElement<Object> e = (IStructureElement<Object>) raw;
        boolean nativePredicate = predicateCache.computeIfAbsent(e, MaterialChoice::hasNativePredicate);
        // The construction default can be air even when the native structure predicate rejects it.
        boolean air = allowsAir(e) || wroteAir && sample == Blocks.air
                && e.couldBeValid(controller, this, px, py, pz, trigger);
        ItemStack item = null;
        ItemStack constructedItem = null;
        if (!air) {
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
                        e.getBlocksToPlace(controller, this, px, py, pz, trigger, env);
                if (choices != null && choices.getStacks() != null)
                    for (ItemStack s : choices.getStacks()) {
                        if (s != null && s.getItem() instanceof ItemBlock) {
                            item = s;
                            break;
                        }
                    }
                if (item == null && choices != null && hatches.computeIfAbsent(e, Blueprint::hatchDots) >= 0) {
                    Cell known = last.get(e);
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
        if (!air && nativePredicate) {
            if (!materialDefaults.containsKey(e))
                materialDefaults.put(e, MaterialChoice.nativeDefault(
                        this, e, constructedItem, block, blockMeta, px, py, pz));
            MaterialChoice.NativeMaterial material = materialDefaults.get(e);
            if (material != null) {
                item = material.item;
                block = material.block;
                blockMeta = material.meta;
            }
        }
        // Inscribed ritual stones have no obtainable item variant. Charge a blank and inscribe after placement.
        if (isInscribedRitual(block, blockMeta))
            item = new ItemStack(block, 1, 0);
        if (role == null) role = "cell:" + (px-x) + "," + (py-y) + "," + (pz-z);
        Cell cell = roleCells.get(role);
        if (cell == null || cell.element != e || cell.block != block || cell.meta != blockMeta
                || cell.air != air || !Materials.equal(cell.item, item)) {
            cell = new Cell(
                    e,
                    item,
                    block,
                    blockMeta,
                    air,
                    hatches.computeIfAbsent(e, Blueprint::hatchDots),
                    nativePredicate);
            cell.piece = piece;
            cell.role = role;
            cell.symbol = symbol;
            if (!choiceCache.containsKey(role)) {
                MaterialChoice group = MaterialChoice.discover(this, cell, px, py, pz);
                choiceCache.put(role, group);
                if (group != null) choices.add(group);
            }
            cell.choice = choiceCache.get(role);
            last.put(e, cell);
            roleCells.put(role, cell);
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

    // GT's structure element captures its builder; getHint() is the native one-based dot count.
    private static int hatchDots(IStructureElement<?> e) {
        if (e.getClass().getEnclosingClass() == HatchElementBuilder.class) {
            for (java.lang.reflect.Field field : e.getClass().getDeclaredFields()) {
                if (field.getType() != HatchElementBuilder.class) continue;
                try {
                    field.setAccessible(true);
                    return Math.max(
                            0, Math.min(16, ((HatchElementBuilder<?>) field.get(e)).getHint()));
                } catch (ReflectiveOperationException ignored) {
                    break;
                }
            }
            return 0;
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
            Cell combined = new Cell(primary.element, primary.item, primary.block, primary.meta,
                    primary.air, primary.dots, primary.nativePredicate);
            combined.piece = primary.piece;
            combined.role = primary.role;
            combined.symbol = primary.symbol;
            combined.choice = primary.choice;
            combined.constraints = constraints;
            cell = combined;
        }
        s.cells[i] = cell;
        if (cell.choice != null) cell.choice.count++;
        else if (cell.item != null) bill.add(cell.item, 1);
        long bit = 1L << (i & 63);
        s.solid[i >> 6] = (s.solid[i >> 6] & ~bit) | (cell.air ? 0 : bit);
        s.hatch[i >> 6] = (s.hatch[i >> 6] & ~bit) | (cell.hatch ? bit : 0);
        if (!bounds.contains(px, py, pz)) bounds = bounds.union(Bounds.point(px, py, pz));
    }

    private static int slot(int x, int y, int z) {
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

    Cell cellAt(int px, int py, int pz) {
        Section section = index.get(new ChunkPosition(px >> 4, py >> 4, pz >> 4));
        return section == null ? null : section.cells[slot(px, py, pz)];
    }

    void displayAir(int cursor, boolean air) {
        int position = positions[cursor], slot = position & 4095;
        Section section = sections.get(position >>> 12);
        long bit = 1L << (slot & 63);
        section.solid[slot >> 6] = section.solid[slot >> 6] & ~bit | (air ? 0 : bit);
    }

    boolean accepts(Cell cell, ItemStack item, int px, int py, int pz) {
        int ox = sx, oy = sy, oz = sz, om = meta;
        Block previous = sample;
        TileEntity previousTile = tile;
        try {
            sx = px; sy = py; sz = pz;
            sample = item == null ? Blocks.air : Block.getBlockFromItem(item.getItem());
            meta = item == null ? 0 : item.getItem().getMetadata(item.getMetadata());
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

    private boolean matchesSingle(Cell c, World world, int px, int py, int pz) {
        Block b = world.getBlock(px, py, pz);
        if (b == Blocks.air) return c.air;
        if (c.element == StructureUtility.notAir()) return true;
        if (c.element != null && !c.element.couldBeValid(controller, world, px, py, pz, trigger))
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
            return c.element.check(controller, world, px, py, pz);
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
