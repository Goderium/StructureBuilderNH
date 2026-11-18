package com.Goderium.StructureBuilderNH.mixin;

import com.Goderium.StructureBuilderNH.common.WorldHooks;

import net.minecraft.block.Block;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A successful replacement ends the task's ownership, including direct chunk edits. */
@Mixin(value = Chunk.class, remap = false)
public abstract class PlacementChunkMixin {
    @Inject(
            method = {"setBlockIDWithMetadata", "func_150807_a"},
            at = @At("RETURN"),
            require = 1,
            remap = false)
    private void builder$changed(
            int x,
            int y,
            int z,
            Block block,
            int metadata,
            CallbackInfoReturnable<Boolean> result) {
        WorldHooks.BlockChange observer = WorldHooks.trackedChanges;
        if (observer == null || !result.getReturnValue()) return;
        Chunk chunk = (Chunk) (Object) this;
        if (chunk.worldObj instanceof WorldServer)
            observer.changed(
                    chunk.worldObj, (chunk.xPosition << 4) + x, y, (chunk.zPosition << 4) + z);
    }
}
