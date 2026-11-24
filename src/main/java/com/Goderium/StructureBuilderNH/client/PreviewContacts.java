package com.Goderium.StructureBuilderNH.client;

import java.util.Arrays;
import java.util.StringJoiner;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** Shares the sampling and mesh budgets across the six outer faces of one projection. */
final class PreviewContacts {
    private final PreviewFooting[] surfaces = new PreviewFooting[6];
    private final PreviewFooting.Face[] sources = new PreviewFooting.Face[6];
    private final ContactEdges edges = new ContactEdges();
    private int[][] indexedFooting;
    private int samplingStart, compileStart;

    PreviewContacts() {
        for (int i = 0; i < surfaces.length; i++) surfaces[i] = new PreviewFooting();
    }

    int sample(World world, ProjectionOccupancy observer, int[][] footing, PreviewGrip moving,
            double eyeX, double eyeY, double eyeZ, int distance, PreviewGrip acceptedDrop, long deadline) {
        if (indexedFooting != footing) {
            Arrays.fill(sources, null);
            indexedFooting = footing;
        }
        int cx = (int) Math.floor(eyeX) >> 4, cy = (int) Math.floor(eyeY) >> 4, cz = (int) Math.floor(eyeZ) >> 4;
        int contacts = 0;
        for (int i = 0; i < surfaces.length; i++) {
            int side = (samplingStart + i) % surfaces.length;
            int source = moving == null ? side : moving.sourceFaces[side];
            int[] rows = footing == null ? null : footing[source];
            PreviewFooting.Face face = sources[source];
            if (rows == null || rows.length == 0) face = null;
            else if (face == null || face.rows != rows) sources[source] = face = new PreviewFooting.Face(rows, source);
            long now = System.nanoTime();
            long turnEnd = now + Math.max(0L, deadline - now) / (surfaces.length - i);
            if (surfaces[side].sample(world, observer, face, ForgeDirection.getOrientation(side), moving,
                    cx, cy, cz, distance, acceptedDrop, turnEnd)) contacts |= 1 << side;
        }
        samplingStart = (samplingStart + 1) % surfaces.length;
        return contacts;
    }

    void setBuilding(boolean building) {
        for (PreviewFooting surface : surfaces) surface.building = building;
    }

    boolean writeDepth(Frustrum camera, boolean allowCompile) {
        boolean compiled = false;
        int start = compileStart;
        for (int i = 0; i < surfaces.length; i++) {
            int side = (start + i) % surfaces.length;
            if (surfaces[side].writeDepth(camera, allowCompile && !compiled)) {
                compiled = true;
                compileStart = (side + 1) % surfaces.length;
            }
        }
        return compiled;
    }

    void drawSurface() {
        PreviewFooting.drawSurfaces(surfaces, edges);
    }

    void chunkChanged(int sx, int sz, boolean unload) {
        for (PreviewFooting surface : surfaces) surface.chunkChanged(sx, sz, unload);
    }

    boolean clear() {
        boolean done = true;
        for (PreviewFooting surface : surfaces) done &= surface.clear();
        if (indexedFooting != null) {
            Arrays.fill(sources, null);
            indexedFooting = null;
        }
        return done;
    }

    void worldChanged() {
        for (PreviewFooting surface : surfaces) surface.worldChanged();
        Arrays.fill(sources, null);
        indexedFooting = null;
    }

    static String label(int mask, ExtendedFacing facing) {
        ForgeDirection front = facing.getDirection();
        // Keep gravity as the vertical reference. Unpitch a vertical controller to recover its horizontal heading.
        if (front == ForgeDirection.UP) front = facing.getRelativeDownInWorld();
        else if (front == ForgeDirection.DOWN) front = facing.getRelativeUpInWorld();
        // Looking at the controller from the front reverses its local left and right.
        ForgeDirection left = ExtendedFacing.of(front).getRelativeRightInWorld();
        StringJoiner labels = new StringJoiner(" / ");
        addLabel(labels, mask, ForgeDirection.DOWN, "groundContact");
        addLabel(labels, mask, ForgeDirection.UP, "contact.top");
        addLabel(labels, mask, left, "contact.left");
        addLabel(labels, mask, left.getOpposite(), "contact.right");
        addLabel(labels, mask, front, "contact.front");
        addLabel(labels, mask, front.getOpposite(), "contact.back");
        return labels.toString();
    }

    private static void addLabel(StringJoiner labels, int mask, ForgeDirection direction, String key) {
        if ((mask & (1 << direction.ordinal())) != 0)
            labels.add(StatCollector.translateToLocal("structurebuildernh." + key));
    }
}
