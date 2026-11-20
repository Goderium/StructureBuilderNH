package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.BrowserPacket;
import com.Goderium.StructureBuilderNH.util.Bounds;
import gregtech.api.util.GTUtility.ItemId;
import net.minecraft.world.ChunkPosition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Compact CPU model, independent of the world and GL. All cell traversal is resumable. */
final class BrowserScene {
    static final int MAX_QUEUED_PACKETS = 128;
    final UUID id;
    final int revision, originX, originY, originZ, controllerMeta, tier;
    int ambiguousDots, labelVersion;
    Bounds bounds;
    final String name;
    final Map<ChunkPosition, Section> sections = new HashMap<>();
    final List<Section> order = new ArrayList<>();
    final Map<Integer, Material> materials = new HashMap<>();
    final Map<Integer, BrowserPacket.Group> groups = new LinkedHashMap<>();
    final Deque<BrowserPacket> incoming = new ArrayDeque<>();
    final Deque<Section> indexing = new ArrayDeque<>();
    final Deque<Section> indexed = new ArrayDeque<>();
    final Deque<Section> removing = new ArrayDeque<>();
    final Deque<Replacement> replacements = new ArrayDeque<>();
    final Map<Object, Row> rows = new LinkedHashMap<>();
    final Map<Object, Row> planeRows = new LinkedHashMap<>();
    final Deque<Section> planeIndexing = new ArrayDeque<>();
    final Map<Integer, List<Section>>[] planes;
    String[] channelNames = new String[0];
    String error = "";
    int totalSections, sentSections, capturedCells, indexedSections;
    int axis = 1, layer, sliceGeneration;
    long rowsVersion, planeVersion;
    boolean ready, closed;
    private BrowserPacket applying;
    private int applyingIndex;
    private int planeScheduleCursor;

    @SuppressWarnings("unchecked")
    BrowserScene(BrowserPacket header) {
        id = header.id;
        revision = header.revision;
        originX = header.originX;
        originY = header.originY;
        originZ = header.originZ;
        controllerMeta = header.projection.controllerMeta;
        tier = header.tier;
        ambiguousDots = header.projection.ambiguousDots;
        Bounds b = header.projection.bounds;
        bounds = b == null ? Bounds.point(0, 0, 0) : new Bounds(b.minX - originX, b.minY - originY,
                b.minZ - originZ, b.maxX - originX, b.maxY - originY, b.maxZ - originZ);
        name = header.name == null ? "" : header.name;
        planes = new Map[] { new HashMap<Integer, List<Section>>(), new HashMap<Integer, List<Section>>(),
                new HashMap<Integer, List<Section>>() };
        layer = Math.max(bounds.minY, Math.min(bounds.maxY, 0));
        progress(header);
    }

    boolean matches(BrowserPacket packet) { return id.equals(packet.id) && revision == packet.revision; }

    void header(BrowserPacket packet) {
        if (ambiguousDots != packet.projection.ambiguousDots) {
            ambiguousDots = packet.projection.ambiguousDots;
            labelVersion++;
        }
        if (packet.projection.bounds != null) {
            Bounds b = packet.projection.bounds;
            bounds = new Bounds(b.minX - originX, b.minY - originY, b.minZ - originZ,
                    b.maxX - originX, b.maxY - originY, b.maxZ - originZ);
        }
        progress(packet);
    }

    void enqueue(BrowserPacket packet) {
        if (!matches(packet) || closed) return;
        if (incoming.size() >= MAX_QUEUED_PACKETS) {
            error = "Browser packet queue exceeded its budget";
            closed = true;
            incoming.clear();
        } else incoming.addLast(packet);
    }

    /** At most limit cheap dictionary/cell operations and never past the supplied frame deadline. */
    void advance(long deadline, int limit) {
        int work = 0;
        while (work < limit && System.nanoTime() < deadline) {
            Section old = removing.peekFirst();
            if (old != null) {
                if (old.removing == null) old.removing = old.counts.entrySet().iterator();
                if (old.removing.hasNext()) {
                    Map.Entry<Object, Row> entry = old.removing.next();
                    subtract(rows, entry.getKey(), old, entry.getValue());
                    rowsVersion++;
                    work++;
                    continue;
                }
                if (old.planeGeneration == sliceGeneration) {
                    if (old.planeRemoving == null) old.planeRemoving = old.planeCounts.entrySet().iterator();
                    if (old.planeRemoving.hasNext()) {
                        Map.Entry<Object, Row> entry = old.planeRemoving.next();
                        subtract(planeRows, entry.getKey(), old, entry.getValue());
                        planeVersion++;
                        work++;
                        continue;
                    }
                }
                removing.removeFirst();
                continue;
            }
            if (applying == null) {
                applying = incoming.pollFirst();
                applyingIndex = 0;
            }
            if (applying != null) {
                if (applyOne()) applying = null;
                work++;
                continue;
            }
            Section section = indexing.peekFirst();
            if (section != null) {
                if (sections.get(section.key) != section) {
                    indexing.removeFirst();
                    work++;
                    continue;
                }
                int stop = Math.min(4096, section.cursor + 64);
                while (section.cursor < stop) {
                    int slot = section.cursor++;
                    Material material = material(section, slot);
                    if (material == null) continue;
                    int x = section.x + (slot & 15) - originX;
                    int y = section.y + (slot >> 8) - originY;
                    int z = section.z + (slot >> 4 & 15) - originZ;
                    add(rows, section.counts, section, material, x, y, z);
                    section.recorded++;
                    section.hasHatches |= material.data.hatch;
                    if (!material.data.air) {
                        section.solid[slot >> 6] |= 1L << (slot & 63);
                        int coarse = ((slot & 15) >> 2) | (((slot >> 4 & 15) >> 2) << 2) | ((slot >> 10) << 4);
                        section.coarseMask |= 1L << coarse;
                    }
                }
                rowsVersion++;
                work += 64;
                if (section.cursor == 4096) {
                    indexing.removeFirst();
                    indexedSections++;
                    section.complete = true;
                    indexed.addLast(section);
                }
                continue;
            }
            List<Section> candidates = planeSections();
            if (planeScheduleCursor < candidates.size()) {
                Section next = candidates.get(planeScheduleCursor++);
                if (next.planeGeneration != sliceGeneration) queuePlane(next);
                work++;
                continue;
            }
            section = planeIndexing.peekFirst();
            if (section == null) break;
            if (section.planeGeneration != sliceGeneration || sections.get(section.key) != section) {
                planeIndexing.removeFirst();
                work++;
                continue;
            }
            int stop = Math.min(256, section.planeCursor + 64);
            int nativeLayer = layer + origin(axis);
            while (section.planeCursor < stop) {
                int slot = planeSlot(axis, nativeLayer & 15, section.planeCursor++);
                Material material = material(section, slot);
                if (material != null) add(planeRows, section.planeCounts, section, material,
                        section.x + (slot & 15) - originX, section.y + (slot >> 8) - originY,
                        section.z + (slot >> 4 & 15) - originZ);
            }
            planeVersion++;
            work += 64;
            if (section.planeCursor == 256) planeIndexing.removeFirst();
        }
    }

    private boolean applyOne() {
        BrowserPacket p = applying;
        switch (p.type) {
            case BrowserPacket.MATERIALS:
                if (p.materials != null && applyingIndex < p.materials.size()) {
                    BrowserPacket.Material data = p.materials.get(applyingIndex++);
                    if (!materials.containsKey(data.id)) materials.put(data.id, new Material(data));
                    return applyingIndex == p.materials.size();
                }
                return true;
            case BrowserPacket.GROUPS:
                if (p.groups != null && applyingIndex < p.groups.size()) {
                    BrowserPacket.Group group = p.groups.get(applyingIndex++);
                    groups.put(group.id, group);
                    return applyingIndex == p.groups.size();
                }
                return true;
            case BrowserPacket.SECTION:
                Section section = new Section(p);
                Section previous = sections.put(section.key, section);
                if (previous == null) {
                    section.orderIndex = order.size();
                    order.add(section);
                    for (int a = 0; a < 3; a++) {
                        List<Section> slab = planes[a].computeIfAbsent(section.coordinate(a) >> 4,
                                ignored -> new ArrayList<>());
                        section.planeIndices[a] = slab.size();
                        slab.add(section);
                    }
                } else {
                    section.orderIndex = previous.orderIndex;
                    order.set(section.orderIndex, section);
                    for (int a = 0; a < 3; a++) {
                        section.planeIndices[a] = previous.planeIndices[a];
                        planes[a].get(section.coordinate(a) >> 4).set(section.planeIndices[a], section);
                    }
                    if (previous.complete) indexedSections--;
                    removing.addLast(previous);
                    replacements.addLast(new Replacement(previous, section));
                }
                indexing.addLast(section);
                if (intersects(section, axis, layer)) queuePlane(section);
                return true;
            case BrowserPacket.CHANNELS:
                channelNames = p.channelNames == null ? new String[0] : p.channelNames;
                return true;
            case BrowserPacket.PROGRESS:
            case BrowserPacket.DONE:
                progress(p);
                return true;
            case BrowserPacket.ERROR:
                error = p.error == null ? "" : p.error;
                return true;
            case BrowserPacket.CLOSE:
                closed = true;
                return true;
            default: return true;
        }
    }

    private void progress(BrowserPacket p) {
        totalSections = p.totalSections;
        sentSections = p.sentSections;
        capturedCells = p.capturedCells;
        ready = p.ready;
    }

    boolean complete() {
        return !closed && error.isEmpty() && ready && incoming.isEmpty() && applying == null && indexing.isEmpty()
                && removing.isEmpty()
                && sections.size() >= totalSections;
    }

    boolean planeComplete() {
        return complete() && planeIndexing.isEmpty() && planeScheduleCursor >= planeSections().size();
    }

    void setSlice(int nextAxis, int nextLayer) {
        axis = Math.max(0, Math.min(2, nextAxis));
        layer = Math.max(min(axis), Math.min(max(axis), nextLayer));
        sliceGeneration++;
        planeRows.clear();
        planeIndexing.clear();
        planeVersion++;
        planeScheduleCursor = 0;
    }

    private void queuePlane(Section section) {
        section.planeGeneration = sliceGeneration;
        section.planeCursor = 0;
        section.planeCounts = new LinkedHashMap<>();
        planeIndexing.addLast(section);
    }

    List<Section> planeSections() {
        List<Section> result = planes[axis].get((layer + origin(axis)) >> 4);
        return result == null ? Collections.emptyList() : result;
    }

    boolean intersects(Section section, int a, int at) {
        int v = at + origin(a);
        return v >= section.coordinate(a) && v < section.coordinate(a) + 16;
    }

    int min(int a) { return a == 0 ? bounds.minX : a == 1 ? bounds.minY : bounds.minZ; }
    int max(int a) { return a == 0 ? bounds.maxX : a == 1 ? bounds.maxY : bounds.maxZ; }
    int origin(int a) { return a == 0 ? originX : a == 1 ? originY : originZ; }

    Material at(int x, int y, int z) {
        return nativeAt(x + originX, y + originY, z + originZ);
    }

    Material nativeAt(int x, int y, int z) {
        Section section = sections.get(new ChunkPosition(x >> 4, y >> 4, z >> 4));
        return section == null ? null : material(section, (x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
    }

    Material material(Section section, int slot) {
        int index = section.slots[slot] & 65535;
        return index == 0 || index > section.ids.length ? null : materials.get(section.ids[index - 1]);
    }

    private static void add(Map<Object, Row> destination, Map<Object, Row> local, Section section,
            Material material, int x, int y, int z) {
        Row contribution = local.get(material.key);
        if (contribution == null) {
            contribution = new Row(material, x, y, z);
            local.put(material.key, contribution);
        }
        contribution.count++;
        Row row = destination.get(material.key);
        if (row == null) {
            row = new Row(material, x, y, z);
            destination.put(material.key, row);
        }
        row.members.put(section, contribution);
        row.count++;
    }

    private static void subtract(Map<Object, Row> destination, Object key, Section section, Row contribution) {
        Row row = destination.get(key);
        if (row == null) return;
        row.count -= contribution.count;
        row.members.remove(section);
        if (row.count <= 0 || row.members.isEmpty()) destination.remove(key);
        else {
            Row sample = row.members.values().iterator().next();
            row.material = sample.material;
            row.x = sample.x;
            row.y = sample.y;
            row.z = sample.z;
        }
    }

    static int planeSlot(int axis, int layer, int uv) {
        int u = uv & 15, v = uv >> 4;
        return axis == 0 ? layer | (u << 4) | (v << 8)
                : axis == 1 ? u | (v << 4) | (layer << 8) : u | (layer << 4) | (v << 8);
    }

    static final class Material {
        final BrowserPacket.Material data;
        final Object key;
        BlockPreviewTextures.Appearance appearance;
        String hatchName;
        int labelVersion = -1;
        String displayName;
        int color;

        Material(BrowserPacket.Material data) {
            this.data = data;
            key = data.item == null ? data.air ? Boolean.TRUE : "unknown:" + data.id : ItemId.create(data.item);
        }
    }

    static final class Row {
        Material material;
        int x, y, z;
        long count;
        final Map<Section, Row> members = new LinkedHashMap<>();

        Row(Material material, int x, int y, int z) {
            this.material = material;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final class Section {
        final ChunkPosition key;
        final int x, y, z;
        final int[] ids;
        final short[] slots;
        final long[] solid = new long[64];
        final Map<Object, Row> counts = new LinkedHashMap<>();
        Map<Object, Row> planeCounts = new LinkedHashMap<>();
        java.util.Iterator<Map.Entry<Object, Row>> removing;
        java.util.Iterator<Map.Entry<Object, Row>> planeRemoving;
        final int[] planeIndices = new int[3];
        int orderIndex, neighborhoodVersion;
        long coarseMask;
        boolean detailed;
        int cursor, recorded, planeCursor, planeGeneration = -1;
        boolean complete, hasHatches;

        Section(BrowserPacket p) {
            x = p.projection.x * 16;
            y = p.projection.y * 16;
            z = p.projection.z * 16;
            key = new ChunkPosition(p.projection.x, p.projection.y, p.projection.z);
            ids = p.materialIds;
            slots = p.materialSlots;
        }

        int coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
    }

    static final class Replacement {
        final Section oldSection, newSection;
        Replacement(Section oldSection, Section newSection) {
            this.oldSection = oldSection;
            this.newSection = newSection;
        }
    }
}
