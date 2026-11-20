package com.Goderium.StructureBuilderNH.mixin;

import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.gtnewhorizon.structurelib.structure.SurvivalBuildStructureWalker", remap = false)
public interface SurvivalBuildWalkerAccessor<T> {
    @Accessor("object") T structurebuildernh$getContext();
    @Accessor("trigger") ItemStack structurebuildernh$getTrigger();
}
