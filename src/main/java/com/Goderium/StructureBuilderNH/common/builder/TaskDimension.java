package com.Goderium.StructureBuilderNH.common.builder;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Optional;
import micdoodle8.mods.galacticraft.api.galaxies.CelestialBody;
import micdoodle8.mods.galacticraft.api.galaxies.GalaxyRegistry;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

final class TaskDimension {
    // Save the name or translation key from the target world so unloaded dimensions keep their labels.
    static String identify(int id, World world) {
        if (id == 0) return "Overworld";
        if (id == -1) return "Nether";
        if (id == 1) return "The End";
        if (Loader.isModLoaded("GalacticraftCore")) {
            String key = celestialName(id);
            if (key != null) return key;
        }
        if (world == null) return null;
        String name = world.provider.getDimensionName();
        if (("Personal World " + id).equals(name)) return "structurebuildernh.dimension.personal";
        return name == null ? "" : name.trim();
    }

    @Optional.Method(modid = "GalacticraftCore")
    private static String celestialName(int id) {
        CelestialBody body = GalaxyRegistry.getCelestialBodyFromDimensionID(id);
        return body == null ? null : body.getUnlocalizedName();
    }

    static String localizedName(String name) {
        if (name == null || name.isEmpty()) return "";
        if (StatCollector.canTranslate(name)) return StatCollector.translateToLocal(name);
        String key = "gtnop.world." + name;
        if (!StatCollector.canTranslate(key)) key = key.replace(" ", "");
        return StatCollector.canTranslate(key) ? StatCollector.translateToLocal(key) : name;
    }
}
