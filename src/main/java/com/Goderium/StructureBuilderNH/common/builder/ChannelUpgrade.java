package com.Goderium.StructureBuilderNH.common.builder;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.util.GlassTier;
import gregtech.api.util.GTStructureUtility;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Read-only recognition of the finite, native GT glass and heating-coil channel families. */
final class ChannelUpgrade {
    enum Kind { SKIP, KEEP, REPLACE, BLOCKED }

    static final class Result {
        final Kind kind;
        final String channel;
        final ItemStack target, previous;
        final Block targetBlock, oldBlock;
        final int targetMeta, oldMeta, sourceValue, targetValue, sourceTier, targetTier;

        Result(Kind kind, Profile profile, Material previous, Block oldBlock, int oldMeta) {
            this.kind = kind;
            channel = profile == null ? "" : profile.channel;
            target = profile == null ? null : profile.target.item.copy();
            targetBlock = profile == null ? null : profile.target.block;
            targetMeta = profile == null ? 0 : profile.target.meta;
            targetValue = profile == null ? 0 : profile.targetValue;
            targetTier = profile == null ? 0 : profile.target.tier;
            this.previous = previous == null ? null : previous.item.copy();
            sourceValue = previous == null ? 0 : previous.value;
            sourceTier = previous == null ? 0 : previous.tier;
            this.oldBlock = oldBlock;
            this.oldMeta = oldMeta;
        }
    }

    /** A native construction output, cross-checked against its one obtainable, round-tripping item. */
    static final class Material {
        final Block block;
        final int meta;
        final ItemStack item;
        int value, tier;

        Material(Block block, int meta, ItemStack item) {
            this.block = block;
            this.meta = meta;
            this.item = item.copy();
            this.item.stackSize = 1;
        }

        boolean same(Block block, int meta) { return this.block == block && this.meta == meta; }
    }

    private static final class Proof {
        final String channel;
        final int maximum;
        Proof(String channel, int maximum) { this.channel = channel; this.maximum = maximum; }
    }

    private static final class Profile {
        final String channel;
        final int targetValue;
        final Material target;
        final Map<Block, Map<Integer, Material>> materials = new IdentityHashMap<>();

        Profile(String channel, int targetValue, Material target) {
            this.channel = channel;
            this.targetValue = targetValue;
            this.target = target;
        }

        void add(Material material) {
            Map<Integer, Material> variants = materials.computeIfAbsent(material.block, b -> new LinkedHashMap<>());
            Material old = variants.get(material.meta);
            // Several channel values can produce the same block. Keep one stable value for the material list.
            if (old == null || old.value < material.value) variants.put(material.meta, material);
        }

        Material find(Block block, int meta) {
            Map<Integer, Material> variants = materials.get(block);
            return variants == null ? null : variants.get(meta);
        }
    }

    private static final Result SKIP = new Result(Kind.SKIP, null, null, null, 0);
    private static final int MAX_NATIVE_VALUES = 256;
    private final Blueprint plan;
    private final Map<IStructureElement<?>, Proof> proofs = new IdentityHashMap<>();
    private final Map<Blueprint.Cell, Profile> profiles = new IdentityHashMap<>();
    private final Set<Blueprint.Cell> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Integer> active = new LinkedHashMap<>();
    private Set<String> channels = Collections.emptySet();

    ChannelUpgrade(Blueprint plan) { this.plan = plan; }

    Set<String> channels() { return plan.ready ? channels : Collections.emptySet(); }

    String channel(Blueprint.Cell cell) {
        Profile profile = profiles.get(cell);
        return !plan.ready || profile == null ? "" : profile.channel;
    }

    Result inspect(Blueprint.Cell cell, World world, int x, int y, int z, Set<String> enabled) {
        if (enabled == null || enabled.isEmpty() || world == null || !plan.ready
                || x == plan.x && y == plan.y && z == plan.z || plan.cellAt(x, y, z) != cell) return SKIP;
        Profile profile = profiles.get(cell);
        if (profile == null || !enabled.contains(profile.channel)) return SKIP;
        // The caller handles chunk acquisition. Recognition never loads a chunk as a side effect.
        if (!world.blockExists(x, y, z)) return new Result(Kind.BLOCKED, profile, null, null, 0);
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        if (world.getTileEntity(x, y, z) != null || block.hasTileEntity(meta))
            return new Result(Kind.BLOCKED, profile, null, block, meta);
        if (block == Blocks.air) return new Result(Kind.KEEP, profile, null, block, meta);
        Material source = profile.find(block, meta);
        if (profile.target.same(block, meta)) return new Result(Kind.KEEP, profile, source, block, meta);
        if (source == null) return new Result(Kind.BLOCKED, profile, null, block, meta);
        return new Result(Kind.REPLACE, profile, source, block, meta);
    }

    /** Called as the normal deadline-bounded blueprint walk records a position, never by a GUI query. */
    void record(Blueprint.Cell old, Blueprint.Cell cell, int x, int y, int z) {
        Profile previous = profiles.get(old);
        if (previous != null) count(previous.channel, -1);
        if (cell == null || cell.element == null || cell.air || cell.hatch || cell.inscribedRitual
                || cell.choice != null || cell.constraints != null || rejected.contains(cell)) return;
        Profile profile = profiles.get(cell);
        if (profile == null) {
            Proof proof;
            if (proofs.containsKey(cell.element)) proof = proofs.get(cell.element);
            else {
                proof = prove(cell.element);
                proofs.put(cell.element, proof);
            }
            if (proof == null) return;
            profile = sample(cell, proof, x, y, z);
            if (profile == null) { rejected.add(cell); return; }
            profiles.put(cell, profile);
        }
        count(profile.channel, 1);
    }

    private void count(String channel, int change) {
        boolean present = active.containsKey(channel);
        int count = active.getOrDefault(channel, 0) + change;
        if (count <= 0) active.remove(channel);
        else active.put(channel, count);
        if (present != active.containsKey(channel))
            channels = Collections.unmodifiableSet(new TreeSet<>(active.keySet()));
    }

    private Profile sample(Blueprint.Cell cell, Proof proof, int x, int y, int z) {
        try {
            ItemStack pieceTrigger = cell.pieceTrigger();
            if (pieceTrigger == null) return null;
            int value = Math.max(1, Math.min(proof.maximum,
                    ChannelDataAccessor.getChannelData(pieceTrigger, proof.channel)));
            Material target = plan.sampleUpgrade(cell, pieceTrigger.copy(), x, y, z);
            if (target == null || !target.same(cell.block, cell.meta) || !Materials.equal(target.item, cell.item))
                return null;
            if ("glass".equals(proof.channel)) {
                value = GlassTier.getGlassChannelValue(target.block, target.meta);
                Integer tier = GlassTier.getGlassBlockTier(target.block, target.meta);
                if (value < 1 || value > proof.maximum || tier == null) return null;
                target.tier = tier;
            } else target.tier = value;
            target.value = value;
            Profile profile = new Profile(proof.channel, value, target);
            for (int rank = 1; rank <= proof.maximum; rank++) {
                ItemStack trigger = pieceTrigger.copy();
                ChannelDataAccessor.setChannelData(trigger, proof.channel, rank);
                Material material;
                try {
                    material = plan.sampleUpgrade(cell, trigger, x, y, z);
                } catch (RuntimeException | LinkageError failure) {
                    material = null;
                }
                // GT's glass list also includes special blocks without a reversible item form.
                // They are never valid sources, but do not invalidate other proven glass variants.
                if (material == null) {
                    if (rank == value || !"glass".equals(proof.channel)) return null;
                    continue;
                }
                material.value = rank;
                if ("glass".equals(proof.channel)) {
                    Integer tier = GlassTier.getGlassBlockTier(material.block, material.meta);
                    if (tier == null) {
                        if (rank == value) return null;
                        continue;
                    }
                    material.tier = tier;
                } else material.tier = rank;
                if (rank == value && !material.same(target.block, target.meta)) return null;
                profile.add(material);
            }
            return profile;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** Only exact dependency implementations are unwrapped; no class-name/subclass guess grants replacement. */
    private static Proof prove(IStructureElement<?> element) {
        String channel = null;
        boolean glassFactory = false, glassTransform = false;
        Set<IStructureElement<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (int depth = 0; element != null && depth < 12 && seen.add(element); depth++) {
                if (nativeMethod(element, StructureUtility.class, "withChannel")) {
                    if (channel != null) return null;
                    channel = field(element, String.class);
                    if (!"glass".equals(channel) && !"coil".equals(channel)) return null;
                    element = field(element, IStructureElement.class);
                } else if (nativeMethod(element, GTStructureUtility.class, "triggerItemTransform")) {
                    Function<?, ?> transform = field(element, Function.class);
                    if (glassTransform || transform == null || transform.getClass() != GlassTransform.TYPE) return null;
                    glassTransform = true;
                    element = field(element, IStructureElement.class);
                } else if (element.getClass().getName().equals(
                        "com.gtnewhorizon.structurelib.structure.LazyStructureElement")) {
                    // Construction already initialized this native lazy element. Never invoke an unknown supplier.
                    element = field(element, IStructureElement.class);
                } else if (nativeMethod(element, GTStructureUtility.class, "chainAllGlassesImpl")) {
                    glassFactory = true;
                    element = ((GTStructureUtility.ProxyStructureElement<?, ?>) element).proxiedElement;
                } else if (nativeMethod(element, GTStructureUtility.class, "activeCoils")) {
                    element = ((GTStructureUtility.ProxyStructureElement<?, ?>) element).proxiedElement;
                } else if (nativeMethod(element, GTStructureUtility.class, "ofCoil")) {
                    int maximum = HeatingCoilLevel.getMaxTier() + 1;
                    return "coil".equals(channel) && !glassFactory && !glassTransform
                            && maximum > 0 && maximum <= MAX_NATIVE_VALUES ? new Proof(channel, maximum) : null;
                } else if (nativeMethod(element, StructureUtility.class, "ofBlocksTiered")) {
                    int maximum = GlassTier.getGlassList().size();
                    return "glass".equals(channel) && glassFactory && glassTransform
                            && maximum > 0 && maximum <= MAX_NATIVE_VALUES ? new Proof(channel, maximum) : null;
                } else return null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return null;
        }
        return null;
    }

    private static boolean nativeMethod(Object object, Class<?> owner, String name) {
        Method method = object.getClass().getEnclosingMethod();
        return object.getClass().getEnclosingClass() == owner && method != null && name.equals(method.getName());
    }

    private static <T> T field(Object object, Class<T> type) throws ReflectiveOperationException {
        T result = null;
        for (Field field : object.getClass().getDeclaredFields()) {
            if (!type.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object value = field.get(object);
            if (value == null) continue;
            if (result != null) return null;
            result = type.cast(value);
        }
        return result;
    }

    private static final class GlassTransform {
        // A method-reference call site has one JVM class. Generic trigger transforms do not share this class.
        static final Class<?> TYPE = type();
        private static Class<?> type() {
            try {
                Function<?, ?> function = field(GTStructureUtility.chainAllGlasses(), Function.class);
                return function == null ? null : function.getClass();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                return null;
            }
        }
    }
}
