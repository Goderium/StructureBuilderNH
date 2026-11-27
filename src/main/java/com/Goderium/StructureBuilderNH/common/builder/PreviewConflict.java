package com.Goderium.StructureBuilderNH.common.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.Goderium.StructureBuilderNH.util.Bounds;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

/** Read-only survey of a placed or proposed projection, advanced on the server tick. */
final class PreviewConflict {
    int x, y, z;
    long sequence;
    ExtendedFacing pose;
    boolean reported;
    private Blueprint plan;
    private PreviewPose transform;
    private int revision, section, slot, wait;
    private final int[] point = new int[3], witness = new int[3];
    private final BuildTask.OrdinaryCell siteCell = new BuildTask.OrdinaryCell();
    private final List<BuildTask> nearby = new ArrayList<>();

    boolean update(BuildTask task, World world, PreviewDragMessage proposal, List<BuildTask> tasks, Set<String> visible) {
        int nx = proposal == null ? task.x : proposal.surveyX();
        int ny = proposal == null ? task.y : proposal.surveyY();
        int nz = proposal == null ? task.z : proposal.surveyZ();
        long seq = proposal == null ? 0 : proposal.surveySequence();
        ExtendedFacing next = proposal == null ? task.facing : ExtendedFacing.byIndex(proposal.surveyPose());
        boolean reset = plan != task.plan || revision != task.revision;
        boolean moved = reset || pose != next || x != nx || y != ny || z != nz || sequence != seq;
        boolean before = reported;
        if (moved) {
            if (reset) { section = slot = 0; reported = false; }
            if (reset || pose != next) transform = next == task.facing ? null : new PreviewPose(task.facing, next);
            plan = task.plan; revision = task.revision;
            x = nx; y = ny; z = nz; sequence = seq; pose = next;
            wait = 0;
        }
        if (wait > 0) { wait--; return false; }
        nearby.clear();
        for (BuildTask other : tasks) {
            if (other != task && other.dim == task.dim && other.owner.equals(task.owner) && other.previewLayers != 0
                    && other.plan != null && other.plan.ready && visible.contains(other.id)) nearby.add(other);
        }
        if (reported) {
            reported = plan.projectsBlock(witness[0], witness[1], witness[2])
                    && blocked(task, world, witness[0], witness[1], witness[2]);
        }
        if (!reported) {
            long deadline = System.nanoTime() + 400_000L;
            reported = blocked(task, world, task.x, task.y, task.z) || scan(task, world, deadline);
        }
        if (reported) wait = 2;
        return before != reported || moved && reported;
    }

    private boolean scan(BuildTask task, World world, long deadline) {
        for (int work = 0; section < plan.sections.size() && work < 4096; work++) {
            if (work != 0 && (work & 15) == 0 && System.nanoTime() >= deadline) return false;
            Blueprint.Section part = plan.sections.get(section);
            int ox = part.x << 4, oy = part.y << 4, oz = part.z << 4;
            if (slot == 0 && !potential(world, ox, oy, oz)) { section++; continue; }
            int word = slot >> 6;
            long bits = (part.solid[word] | part.hatch[word]) & (-1L << slot);
            if (bits != 0) {
                int i = (word << 6) + Long.numberOfTrailingZeros(bits);
                slot = i + 1;
                if (slot == 4096) { slot = 0; section++; }
                if ((part.fluidSlots == null || !part.fluidSlots.get(i))
                        && blocked(task, world, ox + (i & 15), oy + (i >> 8), oz + (i >> 4 & 15))) return true;
            } else {
                slot = (word + 1) << 6;
                if (slot == 4096) { slot = 0; section++; }
            }
        }
        if (section >= plan.sections.size()) { section = slot = 0; wait = 5; }
        return false;
    }

    private boolean potential(World world, int sx, int sy, int sz) {
        project(sx, sy, sz);
        int x0 = point[0], y0 = point[1], z0 = point[2];
        project(sx + 15, sy + 15, sz + 15);
        Bounds box = new Bounds(Math.min(x0, point[0]), Math.min(y0, point[1]), Math.min(z0, point[2]),
                Math.max(x0, point[0]), Math.max(y0, point[1]), Math.max(z0, point[2]));
        for (BuildTask other : nearby) if (other.plan.bounds.intersects(box)) return true;
        for (int cx = box.minX >> 4; cx <= box.maxX >> 4; cx++) {
            for (int cz = box.minZ >> 4; cz <= box.maxZ >> 4; cz++) {
                if (!world.getChunkProvider().chunkExists(cx, cz)) continue;
                ExtendedBlockStorage[] storage = world.getChunkFromChunkCoords(cx, cz).getBlockStorageArray();
                for (int cy = Math.max(0, box.minY >> 4); cy <= Math.min(storage.length - 1, box.maxY >> 4); cy++)
                    if (storage[cy] != null && !storage[cy].isEmpty()) return true;
            }
        }
        return false;
    }

    private boolean blocked(BuildTask task, World world, int sx, int sy, int sz) {
        project(sx, sy, sz);
        int px = point[0], py = point[1], pz = point[2];
        boolean conflict = worldBlocked(task, world, sx, sy, sz, px, py, pz);
        if (!conflict) for (BuildTask other : nearby) {
            if (other.plan.bounds.contains(px, py, pz) && other.plan.projectsBlock(px, py, pz)) {
                conflict = true;
                break;
            }
        }
        if (conflict) { witness[0] = sx; witness[1] = sy; witness[2] = sz; }
        return conflict;
    }

    private boolean worldBlocked(BuildTask task, World world, int sx, int sy, int sz, int px, int py, int pz) {
        if (sequence == 0 && task.existing) {
            // An applied channel change is reviewed against both the old and new plan by SiteAdjustment.
            if (task.adjustment != null) return false;
            if (task.surveysExistingSite()) {
                if (!world.blockExists(px, py, pz) || world.isAirBlock(px, py, pz)) return false;
                task.ordinaryCell(plan.indexAt(sx, sy, sz), world, siteCell, false);
                return siteCell.status == BuildTask.OrdinaryStatus.BLOCKED;
            }
        }
        return task.previewObstruction(world, sx, sy, sz, px, py, pz, pose);
    }

    private void project(int sx, int sy, int sz) {
        int dx = sx - plan.x, dy = sy - plan.y, dz = sz - plan.z;
        point[0] = x + (transform == null ? dx : transform.xx * dx + transform.xy * dy + transform.xz * dz);
        point[1] = y + (transform == null ? dy : transform.yx * dx + transform.yy * dy + transform.yz * dz);
        point[2] = z + (transform == null ? dz : transform.zx * dx + transform.zy * dy + transform.zz * dz);
    }
}
