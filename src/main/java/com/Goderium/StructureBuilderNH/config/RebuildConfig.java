package com.Goderium.StructureBuilderNH.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraftforge.common.config.Configuration;

public final class RebuildConfig {
    public static boolean enabled = true, protectTileEntities = false;
    private static final int DEFAULT_REBUILD_VOLUME = 4000000;
    public static int maxRebuildVolume = DEFAULT_REBUILD_VOLUME;
    public static boolean writeDebugLogs = true, logBlockDecisions = true;
    public static File logDirectory;
    public static Set<String> protectedMods = new HashSet<String>();
    public static Set<String> protectedBlocks = new HashSet<String>();
    public static Set<String> excludedControllers = new HashSet<String>();
    public static void load(File suggested) throws IOException {
        File file = new File(suggested.getParentFile(), "StructureBuilderNH.cfg");
        if (suggested.isFile() && (!file.exists() || Files.isSameFile(suggested.toPath(), file.toPath()))) {
            String existingName = suggested.toPath().toRealPath().getFileName().toString();
            // File.renameTo also changes the on-disk spelling on case-insensitive Windows volumes.
            if (!file.getName().equals(existingName) && !suggested.renameTo(file))
                throw new IOException("Could not rename " + suggested + " to " + file);
        }
        Configuration cfg = new Configuration(file);
        cfg.load();
        enabled = cfg.getBoolean("enabled", "general", true,
            "Allow creative sneaking blueprint rebuild ONLY with an explicit rebuild=1 subchannel. Without that channel, native placement is unchanged. False disables rebuilding for every blueprint");
        protectTileEntities = cfg.getBoolean("protectAllTileEntities", "protection", false,
            "Optional protection of every block entity. False allows ordinary structure block entities to clear. Whitelists, inventory/fluid handlers and GT controllers/hatches remain protected");
        if (cfg.hasKey("general", "maxVisitedBlocks")) {
            int previous = cfg.getCategory("general").remove("maxVisitedBlocks").getInt(DEFAULT_REBUILD_VOLUME);
            if (!cfg.hasKey("general", "maxRebuildVolume"))
                cfg.get("general", "maxRebuildVolume", DEFAULT_REBUILD_VOLUME).set(previous);
        }
        maxRebuildVolume = cfg.getInt("maxRebuildVolume", "general", DEFAULT_REBUILD_VOLUME, 1, 50000000,
            "Maximum volume of the enclosing cuboid of all selected pieces, including gaps. Abort BEFORE clearing if exceeded, out of height, or any chunk is unloaded");
        logDirectory = new File(file.getAbsoluteFile().getParentFile().getParentFile(), "logs/structurebuildernh");
        writeDebugLogs = cfg.getBoolean("writeDebugLogs", "logging", true,
            "Write one UTF-8 report per rebuild in logs/structurebuildernh: target, configuration, geometry, formation, phases, counts and exceptions");
        logBlockDecisions = cfg.getBoolean("logBlockDecisions", "logging", true,
            "Include every non-air clear/protect decision and native placement write/result. Large rebuilds produce large files. False keeps summaries and machine/geometry diagnostics");
        protectedMods = lower(cfg.getStringList("modWhitelist", "protection", new String[] {
            "appliedenergistics2", "ae2fc", "thaumicenergistics", "ae2wct", "betterp2p", "neenergistics",
            "extracells", "ae2stuff", "ae2extras", "ae2utilities", "avaritiaddons", "IronChest", "JABBA",
            "StorageDrawers", "StorageDrawersNatura", "StorageDrawersBop", "StorageDrawersForestry",
            "StorageDrawersErebus", "StorageDrawersMisc", "EnderStorage", "irontanks", "Backpack", "adventurebackpack"
        }, "Mod IDs/registry namespaces, case-insensitive. Protects ALL blocks, including non-container blocks. Defaults include installed GTNH 2.9.0 storage/AE mods and optional AE addons"));
        protectedBlocks = lower(cfg.getStringList("blockWhitelist", "protection", new String[] {
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest", "minecraft:hopper",
            "minecraft:dispenser", "minecraft:dropper", "minecraft:furnace", "minecraft:lit_furnace", "minecraft:brewing_stand"
        }, "registry:block or registry:block@metadata. Protected ONLY during pre-clearing; native blueprint placement may replace it"));
        excludedControllers = lower(cfg.getStringList("excludedControllerClasses", "general", new String[0],
            "Exact Java class names whose native blueprint behaviour should be retained without rebuilding"));
        if (cfg.hasChanged()) cfg.save();
    }
    private static Set<String> lower(String[] values) {
        Set<String> result = new HashSet<String>();
        for (String value : values) if (!value.trim().isEmpty()) result.add(value.trim().toLowerCase(Locale.ROOT));
        return result;
    }
}
