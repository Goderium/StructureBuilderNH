package com.Goderium.StructureBuilderNH.common.builder;

import appeng.api.config.PowerUnits;
import com.Goderium.StructureBuilderNH.config.RebuildConfig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/** Reserves the full operation before callbacks can alter the world or start another edit. */
enum TerminalEnergy {
    PLACE, REMOVE, REPLACE;

    /** Returns the charge reserved in AE, or a negative value if the operation must wait. */
    double reserve(EntityPlayer player, ItemStack terminal) {
        if (player.capabilities.isCreativeMode || !RebuildConfig.terminalEnergyEnabled
                || TerminalCardRecipe.hasEnergy(terminal)) return 0;
        long eu = this == REMOVE ? 0 : RebuildConfig.placeEU;
        if (this != PLACE) eu += RebuildConfig.removeEU;
        if (eu == 0) return 0;
        double ae = PowerUnits.EU.convertTo(PowerUnits.AE, eu);
        if (BuilderItem.ITEM.getAECurrentPower(terminal) < ae) return -1;
        BuilderItem.ITEM.extractAEPower(terminal, ae);
        return ae;
    }

    static void refund(ItemStack terminal, double reserved) {
        if (reserved > 0) BuilderItem.ITEM.injectAEPower(terminal, reserved);
    }
}
