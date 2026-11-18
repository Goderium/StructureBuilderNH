package com.Goderium.StructureBuilderNH.common.builder;

import appeng.api.AEApi;
import appeng.api.config.*;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.*;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.*;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileWireless;
import appeng.util.item.AEItemStack;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

final class Wireless {
    final IGridNode node;
    final IGrid grid;
    final ISecurityGrid security;
    final IMEMonitor<IAEItemStack> items;
    final PlayerSource source;

    private Wireless(IGridNode node, EntityPlayer player, IActionHost point) {
        this.node = node;
        grid = node.getGrid();
        security = grid.getCache(ISecurityGrid.class);
        items = grid.<IStorageGrid>getCache(IStorageGrid.class).getItemInventory();
        source = new PlayerSource(player, point);
    }

    static Wireless find(BuildTask t, World world, EntityPlayer player) {
        if (t.key.isEmpty()) return null;
        Object station;
        try {
            station =
                    AEApi.instance().registries().locatable().getLocatableBy(Long.parseLong(t.key));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!(station instanceof IGridHost)) return null;
        IGridNode node = ((IGridHost) station).getGridNode(ForgeDirection.UNKNOWN);
        if (node == null || !node.isActive() || node.getGrid() == null) return null;
        for (IGridNode ap : node.getGrid().getMachines(TileWireless.class)) {
            IWirelessAccessPoint point = (IWirelessAccessPoint) ap.getMachine();
            DimensionalCoord p = point.getLocation();
            double dx = (double) p.x - t.x,
                    dy = (double) p.y - t.y,
                    dz = (double) p.z - t.z,
                    r = point.getRange();
            if (point.isActive() && p.getWorld() == world && dx * dx + dy * dy + dz * dz < r * r)
                return new Wireless(ap, player, point);
        }
        return null;
    }

    boolean allows(SecurityPermissions permission) {
        return security.hasPermission(source.player, permission);
    }

    int take(ItemStack item, int count, Actionable mode) {
        if (count <= 0 || !allows(SecurityPermissions.EXTRACT)) return 0;
        IAEItemStack result =
                items.extractItems(AEItemStack.create(item).setStackSize(count), mode, source);
        return result == null ? 0 : (int) result.getStackSize();
    }

    boolean canCraft(ItemStack item) {
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        return !crafting.getCraftingFor(AEItemStack.create(item), null, -1, source.player.worldObj)
                .isEmpty();
    }

    int refund(Materials.Entry e) {
        if (!allows(SecurityPermissions.INJECT)) return 0;
        IAEItemStack left =
                items.injectItems(
                        AEItemStack.create(e.item).setStackSize(e.count),
                        Actionable.MODULATE,
                        source);
        return e.count - (left == null ? 0 : (int) left.getStackSize());
    }
}
