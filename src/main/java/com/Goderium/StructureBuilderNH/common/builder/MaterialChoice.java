package com.Goderium.StructureBuilderNH.common.builder;

import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.ISpecialItemBlock;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.util.StatCollector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** One structure-element role. Identical items in fixed cells or other roles have independent quantities. */
final class MaterialChoice {
    private static final Comparator<ItemStack> ITEM_ORDER = Comparator
            .comparing((ItemStack s) -> Item.itemRegistry.getNameForObject(s.getItem()))
            .thenComparingInt(ItemStack::getMetadata);

    final String key;
    String legacyKey;
    final String piece;
    final char symbol;
    final List<ItemStack> items;
    final boolean allowsAir;
    final int defaultIndex;
    private final Block sampledFluid;
    private final int sampledMeta;
    private final BitSet fluidOptions = new BitSet();
    final int x, y, z;
    int count;

    private MaterialChoice(String key, String piece, char symbol, List<ItemStack> items, boolean allowsAir,
            int defaultIndex, int x, int y, int z, Blueprint.Cell cell) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.key = key;
        this.piece = piece;
        this.symbol = symbol;
        this.items = items;
        this.allowsAir = allowsAir;
        this.defaultIndex = defaultIndex;
        sampledFluid = cell.isFluid() ? cell.block : null;
        sampledMeta = cell.meta;
        for (int i = 0; i < items.size(); i++)
            if (StructureFluids.isFluid(optionBlock(i))) fluidOptions.set(i);
    }

    boolean groupSelectable() { return symbol != '\0'; }
    int optionCount() { return items.size() + (allowsAir ? 1 : 0); }
    boolean isAir(int index) { return allowsAir && index == items.size(); }
    ItemStack optionItem(int index) { return index >= 0 && index < items.size() ? items.get(index) : null; }
    boolean isFluid(int index) { return index >= 0 && fluidOptions.get(index); }
    Block optionBlock(int index) {
        if (index == 0 && sampledFluid != null) return sampledFluid;
        ItemStack item = optionItem(index);
        return item == null ? Blocks.air : Block.getBlockFromItem(item.getItem());
    }
    int optionMeta(int index) {
        if (index == 0 && sampledFluid != null) return sampledMeta;
        ItemStack item = optionItem(index);
        return item == null ? 0 : item.getItem().getMetadata(item.getMetadata());
    }
    ItemStack displayItem(int index) {
        if (!isFluid(index) || optionItem(index) != null) return optionItem(index);
        return gregtech.api.util.GTUtility.getFluidDisplayStack(StructureFluids.fluid(optionBlock(index)));
    }
    String optionName(int index) {
        if (isFluid(index)) {
            net.minecraftforge.fluids.Fluid fluid = StructureFluids.fluid(optionBlock(index));
            return fluid == null ? optionBlock(index).getLocalizedName() : fluid.getLocalizedName();
        }
        ItemStack item = optionItem(index);
        return item == null ? StatCollector.translateToLocal("structurebuildernh.previewAir") : item.getDisplayName();
    }

    String optionToken(int index) {
        if (isAir(index)) return "air";
        if (isFluid(index) && optionItem(index) == null)
            return "fluid:" + Block.blockRegistry.getNameForObject(optionBlock(index)) + "@" + optionMeta(index);
        // Optional native branches follow the channel's current example when its tier changes.
        if (allowsAir && items.size() == 1 && index == 0 && !isFluid(index)) return "native";
        ItemStack item = optionItem(index);
        return item == null ? "" : Item.itemRegistry.getNameForObject(item.getItem()) + "@" + item.getMetadata();
    }

    int optionIndex(String token) {
        for (int i = 0; i < optionCount(); i++) if (optionToken(i).equals(token)) return i;
        return -1;
    }

    // Only these native predicates are exact and side effect free, unlike tier setters or hatch adders.
    static boolean hasNativePredicate(IStructureElement<?> e) {
        return nativeElement(e, true);
    }

    private static boolean nativeElement(IStructureElement<?> e, boolean anyMeta) {
        if (e == null) return false;
        if (e == StructureUtility.isAir() || e == StructureUtility.notAir()) return anyMeta;
        if (e instanceof IStructureElementChain) {
            if (!nativeChain((IStructureElementChain<?>) e)) return false;
            for (IStructureElement<?> child : ((IStructureElementChain<?>) e).fallbacks())
                if (!nativeElement(child, anyMeta)) return false;
            return true;
        }
        if (e.getClass().getEnclosingClass() != StructureUtility.class) return false;
        Method method = e.getClass().getEnclosingMethod();
        if (method == null) return false;
        String name = method.getName();
        return name.equals("ofBlock") || name.equals("ofBlocksFlat") || name.equals("ofBlocksMap")
                || anyMeta && name.equals("ofBlockAnyMeta");
    }

    // Custom chains may override the native predicate or enumerate unrelated outputs.
    static boolean nativeChain(IStructureElementChain<?> chain) {
        Class<?> type = chain.getClass();
        try {
            return type.getMethod("check", Object.class, World.class, int.class, int.class, int.class)
                            .getDeclaringClass() == IStructureElementChain.class
                    && type.getMethod("couldBeValid", Object.class, World.class, int.class, int.class, int.class,
                                    ItemStack.class).getDeclaringClass() == IStructureElementChain.class
                    && type.getMethod("getBlocksToPlace", Object.class, World.class, int.class, int.class, int.class,
                                    ItemStack.class, AutoPlaceEnvironment.class)
                            .getDeclaringClass() == IStructureElementChain.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    static final class NativeMaterial {
        final ItemStack item;
        final Block block;
        final int meta;

        NativeMaterial(ItemStack item, Block block, int meta) {
            this.item = ItemStack.copyItemStack(item);
            if (this.item != null) this.item.stackSize = 1;
            this.block = block;
            this.meta = meta;
        }
    }

    // Native construction/hint defaults need not belong to the element's accepted block set.
    static NativeMaterial nativeDefault(Blueprint plan, IStructureElement<?> e, ItemStack candidate,
            Block block, int meta, int x, int y, int z, Object context, ItemStack trigger) {
        if (!nativeElement(e, false)) return null;
        @SuppressWarnings("unchecked")
        IStructureElement<Object> element = (IStructureElement<Object>) e;
        IStructureElement.BlocksToPlace choices = element.getBlocksToPlace(
                context, plan, x, y, z, trigger, plan.env);
        if (choices == null || choices.getStacks() == null) return null;
        ItemStack first = null;
        for (ItemStack s : choices.getStacks()) {
            if (s == null || !(s.getItem() instanceof ItemBlock) || s.getMetadata() == 32767) continue;
            if (Materials.equal(candidate, s)) return new NativeMaterial(s, block, meta);
            if (first == null || ITEM_ORDER.compare(s, first) < 0) first = s;
        }
        if (first == null) return new NativeMaterial(null, block, meta);
        return new NativeMaterial(first, Block.getBlockFromItem(first.getItem()),
                first.getItem().getMetadata(first.getMetadata()));
    }

    static MaterialChoice discover(Blueprint plan, Blueprint.Cell cell, int x, int y, int z, Object context, ItemStack trigger) {
        boolean air = Blueprint.allowsAir(cell.element);
        // Any-meta elements have exact world predicates, but cannot enumerate all of their item variants.
        if (cell.hatch || cell.inscribedRitual
                || !nativeElement(cell.element, false)
                        && !((air || cell.isFluid()) && cell.element instanceof IStructureElementChain))
            return null;
        IStructureElement.BlocksToPlace choices = cell.element.getBlocksToPlace(
                context, plan, x, y, z, trigger, plan.env);
        if (choices == null || choices.getStacks() == null) return null;
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack s : choices.getStacks()) {
            // StructureLib supplies a null-item stack for blocks without an item form (e.g. water).
            if (s == null || s.getItem() == null) continue;
            if (!(s.getItem() instanceof ItemBlock) || s.hasTagCompound()
                    || s.getMetadata() == 32767 || Block.getBlockFromItem(s.getItem()).hasTileEntity(s.getMetadata()))
                return null;
            if (items.stream().noneMatch(a -> Materials.equal(a, s))) {
                ItemStack unit = s.copy();
                unit.stackSize = 1;
                items.add(unit);
            }
        }
        items.sort(ITEM_ORDER);
        if (cell.isFluid()) {
            if (items.stream().noneMatch(s -> !StructureFluids.isFluid(Block.getBlockFromItem(s.getItem())))) return null;
            ItemStack sampled = null;
            for (ItemStack option : items) {
                if (Block.getBlockFromItem(option.getItem()) == cell.block
                        && option.getItem().getMetadata(option.getMetadata()) == cell.meta) { sampled = option; break; }
            }
            // The actual construction example has a real block state even if it has no ItemBlock.
            if (sampled != null) items.remove(sampled);
            items.add(0, sampled);
        } else if (items.size() < (air ? 1 : 2)
                || !air && items.stream().noneMatch(s -> Materials.equal(cell.item, s))) return null;
        else if (cell.item != null && items.stream().anyMatch(s -> Materials.equal(cell.item, s))) {
            items.removeIf(s -> Materials.equal(s, cell.item));
            items.add(0, cell.item);
        }
        int defaultIndex = 0;
        if (air && !cell.isFluid()) {
            defaultIndex = items.size();
            // Keep saved option indices stable while following the block sampled from native construction.
            for (int i = 0; i < items.size(); i++) {
                ItemStack option = items.get(i);
                if (Block.getBlockFromItem(option.getItem()) == cell.block
                        && option.getItem().getMetadata(option.getMetadata()) == cell.meta) {
                    defaultIndex = i;
                    break;
                }
            }
        }
        MaterialChoice group = new MaterialChoice(cell.role, cell.piece, cell.symbol, items, air, defaultIndex, x, y, z, cell);
        StringBuilder legacy = new StringBuilder().append(x-plan.x).append(',').append(y-plan.y).append(',').append(z-plan.z);
        for (ItemStack item : items) if (item != null) legacy.append('/').append(Item.itemRegistry.getNameForObject(item.getItem()))
                .append('@').append(item.getMetadata());
        group.legacyKey = legacy.toString();
        return group;
    }

    int indexAt(World world, int x, int y, int z) {
        Block b = world.getBlock(x, y, z);
        if (b == Blocks.air) return allowsAir ? items.size() : -1;
        if (sampledFluid == b && world.getBlockMetadata(x, y, z) == sampledMeta) return 0;
        Item item = Item.getItemFromBlock(b);
        int damage = b.getDamageValue(world, x, y, z);
        if (item instanceof ISpecialItemBlock)
            damage = ((ISpecialItemBlock) item).getItemMetaFromBlockMeta(b, damage);
        for (int i = 0; i < items.size(); i++) {
            ItemStack choice = items.get(i);
            if (choice != null && choice.getItem() == item && choice.getMetadata() == damage) return i;
        }
        return -1;
    }

    int[] allocation(NBTTagCompound selections, boolean useNativeOptionalDefaults) {
        int[] quantities = selections.getIntArray(key).clone();
        if (quantities.length != optionCount()) quantities = new int[optionCount()];
        if (!selections.hasKey(key)) {
            int preferred = allowsAir && sampledFluid == null && !useNativeOptionalDefaults ? items.size() : defaultIndex;
            quantities[preferred] = count;
            return quantities;
        }
        int left = count;
        for (int i = 1; i < quantities.length; i++) {
            quantities[i] = Math.min(left, Math.max(0, quantities[i]));
            left -= quantities[i];
        }
        quantities[0] = left;
        return quantities;
    }

}
