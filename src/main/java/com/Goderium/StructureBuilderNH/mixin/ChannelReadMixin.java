package com.Goderium.StructureBuilderNH.mixin;

import com.Goderium.StructureBuilderNH.common.builder.ChannelProbe;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChannelDataAccessor.class, remap = false)
public abstract class ChannelReadMixin {
    @Inject(
            method = {
                "withChannel",
                "getChannelData",
                "hasSubChannel(Lnet/minecraft/item/ItemStack;Ljava/lang/String;)Z"
            },
            at = @At("HEAD"),
            remap = false)
    private static void rebuild$observe(
            ItemStack trigger, String name, CallbackInfoReturnable<?> ci) {
        ChannelProbe.record(name);
    }
}
