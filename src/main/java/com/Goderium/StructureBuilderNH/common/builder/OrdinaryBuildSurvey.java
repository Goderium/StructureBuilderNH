package com.Goderium.StructureBuilderNH.common.builder;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * An incremental, read-only ordinary-build snapshot. Counts describe the visited prefix until complete;
 * unvisited and unavailable positions are never reported as missing material. The caller owns refreshes
 * after world changes. Like the native structure walk, a pass is not an atomic world snapshot.
 */
final class OrdinaryBuildSurvey {
    final Blueprint plan;
    final int revision, size;
    private final int manualEdits;
    final Materials total = new Materials(), installed = new Materials(), missing = new Materials(),
            blocked = new Materials(), unverified = new Materials();
    int visited, totalCells, installedCells, missingCells, blockedCells, unknownCells, unloadedCells;
    int airCells, ritualCells;

    private final BuildTask task;
    private final BuildTask.OrdinaryCell cell = new BuildTask.OrdinaryCell();
    private World observedWorld;
    private boolean stale;

    OrdinaryBuildSurvey(BuildTask task) {
        if (task == null || task.plan == null || !task.plan.ready || task.upgrading())
            throw new IllegalArgumentException("An ordinary, ready blueprint is required");
        this.task = task;
        plan = task.plan;
        revision = task.revision;
        manualEdits = task.manualEditGeneration;
        size = plan.size;
    }

    boolean valid(BuildTask candidate) {
        return !stale && candidate == task && task.plan == plan && plan.ready && plan.size == size
                && task.revision == revision && task.manualEditGeneration == manualEdits && !task.upgrading();
    }

    /** Complete traversal does not imply a fully built structure or zero unavailable positions. */
    boolean complete() {
        return valid(task) && visited == size;
    }

    /** A small default tick slice; callers sharing a larger tick budget may supply their own deadline. */
    boolean advance(World world) {
        advance(world, System.nanoTime() + 350_000L, 2048);
        return complete();
    }

    /**
     * Returns the number of positions inspected. No chunk acquisition, task confirmation, reservation,
     * placement or persistence occurs. One native classification cannot be preempted mid-call.
     */
    int advance(World world, long deadline, int maxCells) {
        if (!valid(task) || world == null || world.provider == null || world.provider.dimensionId != task.dim) return 0;
        if (observedWorld != null && observedWorld != world) {
            stale = true;
            return 0;
        }
        if (maxCells <= 0 || visited == size || System.nanoTime() >= deadline) return 0;
        observedWorld = world;
        int start = visited;
        int limit = Math.min(maxCells, 8192);
        while (visited < size && visited - start < limit && System.nanoTime() < deadline) {
            try {
                task.ordinaryCell(visited, world, cell);
            } catch (RuntimeException | LinkageError unavailable) {
                // A failed provider has not proved absence or even a safe material identity.
                unknownCells++;
                visited++;
                continue;
            }
            record();
            visited++;
        }
        return visited - start;
    }

    private void record() {
        BuildTask.OrdinaryStatus status = cell.status;
        if (status == null) {
            unknownCells++;
            return;
        }
        if (status == BuildTask.OrdinaryStatus.AIR) {
            airCells++;
            return;
        }

        ItemStack material = status == BuildTask.OrdinaryStatus.INSTALLED && cell.installedMaterial != null
                ? cell.installedMaterial : cell.material;
        if (cell.counted) {
            totalCells++;
            if (material != null) total.add(material, 1);
        }
        switch (status) {
            case INSTALLED:
                if (cell.counted) {
                    installedCells++;
                    if (material != null) installed.add(material, 1);
                }
                break;
            case MISSING:
                if (cell.counted) {
                    missingCells++;
                    if (cell.blankRitual) ritualCells++;
                    else if (material != null) missing.add(material, 1);
                }
                break;
            case BLOCKED:
                blockedCells++;
                if (cell.counted && material != null) blocked.add(material, 1);
                break;
            case UNLOADED:
            case UNKNOWN:
                if (status == BuildTask.OrdinaryStatus.UNLOADED) unloadedCells++;
                // Both unavailable categories remain separate from proven empty positions.
                unknownCells++;
                if (cell.counted && material != null) unverified.add(material, 1);
                break;
            default:
                throw new IllegalStateException("Unhandled ordinary status " + status);
        }
    }
}
