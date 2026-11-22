package com.Goderium.StructureBuilderNH;

import com.Goderium.StructureBuilderNH.common.BuilderProxy;
import com.Goderium.StructureBuilderNH.common.RebuildEngine;
import com.Goderium.StructureBuilderNH.common.builder.*;
import com.Goderium.StructureBuilderNH.config.RebuildConfig;
import com.gtnewhorizon.structurelib.StructureLibAPI;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.registry.GameRegistry;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.ForgeChunkManager;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = "structurebuildernh",
        name = "StructureBuilderNH",
        version = Tags.VERSION,
        dependencies =
                "required-after:gregtech;required-after:structurelib;required-after:appliedenergistics2;required-after:modularui2",
        acceptableRemoteVersions = "*")
public class StructureBuilderNH {
    @Mod.Instance("structurebuildernh")
    public static StructureBuilderNH instance;

    @SidedProxy(
            clientSide = "com.Goderium.StructureBuilderNH.client.BuilderRenderer",
            serverSide = "com.Goderium.StructureBuilderNH.common.BuilderProxy")
    public static BuilderProxy proxy;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(new BuilderTasks.Events());
        PreviewStream.register();
        PreviewEdit.register();
        proxy.register();
        ItemStack terminal =
                appeng.api.AEApi.instance()
                        .definitions()
                        .items()
                        .wirelessTerminal()
                        .maybeStack(1)
                        .orNull();
        if (terminal != null)
            GameRegistry.addShapelessRecipe(
                    new ItemStack(BuilderItem.ITEM),
                    terminal,
                    StructureLibAPI.getDefaultHologramItem());
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        TerminalCardRecipe.register();
    }

    @Mod.EventHandler
    public void stop(FMLServerStoppingEvent event) {
        BuilderTasks.shutdown();
    }

    public static final Logger LOG = LogManager.getLogger("StructureBuilderNH");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) throws IOException {
        // Work tickets are temporary. Resumed tasks acquire fresh ones after their owner returns.
        ForgeChunkManager.setForcedChunkLoadingCallback(this,
                (tickets, world) -> tickets.forEach(ForgeChunkManager::releaseTicket));
        BuilderItem.register();
        RebuildConfig.load(event.getSuggestedConfigurationFile());
        StructureLibAPI.registerChannelDescription(
                RebuildEngine.REBUILD_CHANNEL,
                "structurebuildernh",
                "structurebuildernh.channel.rebuild");
    }
}
