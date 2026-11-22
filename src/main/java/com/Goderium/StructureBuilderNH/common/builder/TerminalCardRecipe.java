package com.Goderium.StructureBuilderNH.common.builder;

import java.util.Arrays;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.world.World;

public final class TerminalCardRecipe extends ShapelessRecipes {
    // AE2FC uses a shared upgrade for both range cards.
    private static final String RANGE = "infinityBoosterCard";
    private static final String ENERGY = "InfinityEnergyCard";
    private final String upgrade;

    TerminalCardRecipe(ItemStack card, String upgrade) {
        super(install(new ItemStack(BuilderItem.ITEM), upgrade), Arrays.asList(new ItemStack(BuilderItem.ITEM), card));
        this.upgrade = upgrade;
    }

    public static void register() {
        register("ae2wct", "infinityBoosterCard", RANGE);
        register("ae2fc", "quantum_bridge_card", RANGE);
        register("ae2fc", "energy_card", ENERGY);
    }

    private static void register(String mod, String name, String upgrade) {
        ItemStack card = GameRegistry.findItemStack(mod, name, 1);
        if (card != null) GameRegistry.addRecipe(new TerminalCardRecipe(card, upgrade));
    }

    private static ItemStack install(ItemStack terminal, String upgrade) {
        ItemStack result = terminal.copy();
        result.stackSize = 1;
        BuilderTasks.tag(result).setBoolean(upgrade, true);
        return result;
    }

    static boolean hasRange(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound().getBoolean(RANGE);
    }

    static boolean hasEnergy(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound().getBoolean(ENERGY);
    }

    private static ItemStack terminal(InventoryCrafting grid) {
        for (int i = 0; i < grid.getSizeInventory(); i++) {
            ItemStack stack = grid.getStackInSlot(i);
            if (stack != null && stack.getItem() == BuilderItem.ITEM) return stack;
        }
        return null;
    }

    @Override
    public boolean matches(InventoryCrafting grid, World world) {
        if (!super.matches(grid, world)) return false;
        ItemStack stack = terminal(grid);
        return stack != null && !(stack.hasTagCompound() && stack.getTagCompound().getBoolean(upgrade));
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting grid) {
        ItemStack stack = terminal(grid);
        return stack == null ? null : install(stack, upgrade);
    }
}
