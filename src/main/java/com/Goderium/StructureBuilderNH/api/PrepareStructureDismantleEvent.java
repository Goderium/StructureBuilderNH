package com.Goderium.StructureBuilderNH.api;

import cpw.mods.fml.common.eventhandler.Event;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * Posted on the Forge event bus on the server before a site dismantle captures its block list.
 * A controller or an integration can restore its hidden blocks here and keep them physical
 * through teardown. Listeners must tolerate retries and world reloads.
 */
public final class PrepareStructureDismantleEvent extends Event {
    public final IGregTechTileEntity controller;
    public final EntityPlayerMP player;
    /** Private to this capture. Adjust tiers or channels to describe the physical structure. */
    public final ItemStack trigger;
    public boolean useSurvivalConstruct;
    private boolean waiting;

    public PrepareStructureDismantleEvent(IGregTechTileEntity controller, EntityPlayerMP player, ItemStack trigger,
            boolean useSurvivalConstruct) {
        this.controller = controller;
        this.player = player;
        this.trigger = trigger;
        this.useSurvivalConstruct = useSurvivalConstruct;
    }

    /** Call while restoration is in progress. The terminal will retry before scanning or harvesting. */
    public void awaitRestoration() {
        waiting = true;
    }

    public boolean isWaiting() {
        return waiting;
    }
}
