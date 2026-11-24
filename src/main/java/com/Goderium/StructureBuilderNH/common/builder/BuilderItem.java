package com.Goderium.StructureBuilderNH.common.builder;

import appeng.api.features.INetworkEncodable;
import appeng.core.AEConfig;
import appeng.core.localization.GuiText;
import appeng.items.tools.powered.powersink.AEBasePoweredItem;
import appeng.tile.networking.TileWireless;
import com.google.common.base.Optional;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;
import com.Goderium.StructureBuilderNH.config.RebuildConfig;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.factory.*;
import com.cleanroommc.modularui.screen.*;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.*;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

public final class BuilderItem extends AEBasePoweredItem
        implements IGuiHolder<PlayerInventoryGuiData>, INetworkEncodable {
    public static final BuilderItem ITEM = new BuilderItem();
    static final Item RECEIPT =
            new Item() {
                @Override
                public String getItemStackDisplayName(ItemStack s) {
                    ItemStack machine =
                            s.hasTagCompound()
                                    ? ItemStack.loadItemStackFromNBT(
                                            s.getTagCompound().getCompoundTag("controller"))
                                    : null;
                    return machine == null ? super.getItemStackDisplayName(s)
                            : StatCollector.translateToLocalFormatted("structurebuildernh.receipt", machine.getDisplayName());
                }
            }.setUnlocalizedName("sbnh.materials")
                    .setTextureName("structurelib:itemConstructableTrigger");

    private BuilderItem() {
        super(AEConfig.instance.wirelessTerminalBattery, Optional.absent());
        setUnlocalizedName("sbnh.builder");
        setTextureName("structurebuildernh:wireless_structure_terminal");
    }

    public static void register() {
        ITEM.setCreativeTab(StructureLib.creativeTab);
        GameRegistry.registerItem(ITEM, "wireless_builder");
        GameRegistry.registerItem(RECEIPT, "material_receipt");
        GregTechAPI.addDummyWorld(Blueprint.class);
        MinecraftForge.EVENT_BUS.register(ITEM);
    }

    public String getEncryptionKey(ItemStack s) {
        return s.hasTagCompound() ? s.getTagCompound().getString("network") : "";
    }

    public void setEncryptionKey(ItemStack s, String key, String name) {
        BuilderTasks.tag(s).setString("network", key == null ? "" : key);
    }

    static int rate(ItemStack s) {
        return clampRate(s != null && s.hasTagCompound() && s.getTagCompound().hasKey("rate")
                ? s.getTagCompound().getInteger("rate") : 256);
    }

    static int clampRate(int rate) {
        return Math.max(1, Math.min(RebuildConfig.maxBuildRate, rate));
    }

    private static ExtendedFacing facingPlayer(EntityPlayer p) {
        ForgeDirection[] dirs = {
            ForgeDirection.SOUTH, ForgeDirection.WEST, ForgeDirection.NORTH, ForgeDirection.EAST
        };
        int horizontal = MathHelper.floor_double(p.rotationYaw / 90.0 + 0.5) & 3;
        ForgeDirection look = p.rotationPitch > 65 ? ForgeDirection.DOWN
                : p.rotationPitch < -65 ? ForgeDirection.UP : dirs[horizontal];
        return ExtendedFacing.of(look.getOpposite());
    }

    @Override
    public boolean onItemUseFirst(
            ItemStack stack,
            EntityPlayer p,
            World w,
            int x,
            int y,
            int z,
            int side,
            float a,
            float b,
            float c) {
        if (!p.isSneaking() && w.getTileEntity(x, y, z) instanceof TileWireless) return false;
        if (w.isRemote) return StructureBuilderNH.proxy.editPreview(p);
        if (StructureBuilderNH.proxy.consumePreviewUse(p)) return true;
        TileEntity tile = w.getTileEntity(x, y, z);
        IGregTechTileEntity host = null;
        if (tile instanceof IGregTechTileEntity) {
            IGregTechTileEntity gt = (IGregTechTileEntity) tile;
            if (gt.getMetaTileEntity()
                    instanceof com.gtnewhorizon.structurelib.alignment.constructable.IConstructable)
                host = gt;
        }
        ExtendedFacing f = facingPlayer(p);
        if (host == null) {
            ForgeDirection d = ForgeDirection.getOrientation(side);
            x += d.offsetX;
            y += d.offsetY;
            z += d.offsetZ;
        } else
            f =
                    host.getMetaTileEntity() instanceof IAlignment
                            ? ((IAlignment) host.getMetaTileEntity()).getExtendedFacing()
                            : ExtendedFacing.of(host.getFrontFacing());
        BuilderTasks.tag(stack).setBoolean("openQueue", false);
        BuilderTasks.get().create(p, stack, x, y, z, host, f);
        PlayerInventoryGuiFactory.INSTANCE.openFromMainHand(p);
        return true;
    }

    @SubscribeEvent
    public void pickPreview(PlayerInteractEvent event) {
        // Only native item-use callbacks open server UIs. Protection checks can also post interact events.
        if (!event.world.isRemote || (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_AIR
                && event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)) return;
        EntityPlayer player = event.entityPlayer;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != this) return;
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && !player.isSneaking()
                && event.world.getTileEntity(event.x, event.y, event.z) instanceof TileWireless) return;
        if (StructureBuilderNH.proxy.editPreview(player)) event.setCanceled(true);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack s, World w, EntityPlayer p) {
        if (w.isRemote) {
            // Some item-use callers bypass Forge's RIGHT_CLICK_AIR event.
            StructureBuilderNH.proxy.editPreview(p);
            return s;
        }
        if (StructureBuilderNH.proxy.consumePreviewUse(p)) return s;
        if (!w.isRemote && getMovingObjectPositionFromPlayer(w, p, false) == null) {
            BuilderTasks.tag(s).setBoolean("openQueue", p.isSneaking());
            if (!p.isSneaking()) selectAtFeet(p, s);
            else if (BuilderTasks.get().selected(p, s) == null) {
                List<BuildTask> tasks = BuilderTasks.get().list(p, s);
                if (!tasks.isEmpty()) BuilderTasks.get().select(s, tasks.get(0));
            }
            PlayerInventoryGuiFactory.INSTANCE.openFromMainHand(p);
        }
        return s;
    }

    static void selectAtFeet(EntityPlayer player, ItemStack blueprint) {
        BuilderTasks.get().create(player, blueprint, MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.boundingBox.minY), MathHelper.floor_double(player.posZ),
                null, facingPlayer(player));
    }

    @Override
    public ModularPanel buildUI(
            PlayerInventoryGuiData d, PanelSyncManager sync, UISettings settings) {
        return new BuilderGui(d, sync).build();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public ModularScreen createScreen(PlayerInventoryGuiData d, ModularPanel panel) {
        return new ModularScreen("structurebuildernh", panel);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public boolean isFull3D() {
        return false;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addCheckedInformation(ItemStack s, EntityPlayer p, List<String> lines, boolean advanced) {
        super.addCheckedInformation(s, p, lines, advanced);
        boolean linked = !getEncryptionKey(s).isEmpty();
        lines.add((linked ? EnumChatFormatting.GREEN : EnumChatFormatting.RED)
                + (linked ? GuiText.Linked : GuiText.Unlinked).getLocal() + EnumChatFormatting.RESET);
        if (TerminalCardRecipe.hasRange(s)) lines.add(StatCollector.translateToLocal("structurebuildernh.unlimitedRange"));
        if (TerminalCardRecipe.hasEnergy(s)) lines.add(StatCollector.translateToLocal("structurebuildernh.unlimitedEnergy"));
    }
}
