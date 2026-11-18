package com.Goderium.StructureBuilderNH.loader;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("StructureBuilderNH")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({"com.Goderium.StructureBuilderNH.loader."})
public class EarlyLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {
    public String[] getASMTransformerClass() { return new String[0]; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) { }
    public String getAccessTransformerClass() { return null; }
    public String getMixinConfig() { return "mixins.structurebuildernh.early.json"; }
    public List<String> getMixins(Set<String> mods) { return Arrays.asList("WorldMixin", "PlacementChunkMixin"); }
}
