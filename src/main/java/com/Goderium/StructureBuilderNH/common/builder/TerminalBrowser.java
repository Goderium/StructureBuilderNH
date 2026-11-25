package com.Goderium.StructureBuilderNH.common.builder;

import static com.cleanroommc.modularui.network.NetworkUtils.readStringSafe;
import static com.cleanroommc.modularui.network.NetworkUtils.writeStringSafe;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.Goderium.StructureBuilderNH.client.TerminalBrowserWidget;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.gtnewhorizon.structurelib.structure.IChannelDefinition;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

/** One browser per open terminal container. Its preview edits never mutate a task until adopted. */
public final class TerminalBrowser {
    interface ApplyChannels {
        void apply(int tier, NBTTagCompound levels, Set<String> targets, Set<String> excluded, boolean share);
    }

    public interface Display {
        void accept(BrowserPacket packet);
        void settings(PacketBuffer packet);
        void close();
    }

    private final PanelSyncManager sync;
    private final ApplyChannels adopt;
    private final Runnable back;
    private final Supplier<BuildTask> selected;
    private final Map<String, IChannelDefinition> definitions = new LinkedHashMap<>();
    private Display display;
    private BuildTask source;
    private ItemStack openedTarget;
    private BrowserSession session;
    private NBTTagCompound levels;
    private int tier, taskRevision, known, observed, debounce, appliedEdit;
    private boolean wasWritable;
    private boolean channelDrawer, surveyed;
    private ChannelSurvey survey;
    private long generation;

    TerminalBrowser(PanelSyncManager sync, Supplier<BuildTask> selected,
            ApplyChannels adopt, Runnable back) {
        this.sync = sync;
        this.selected = selected;
        this.adopt = adopt;
        this.back = back;
        sync.registerSyncedAction("browserData", true, false, p -> {
            if (display != null) display.accept(BrowserPacket.read(p));
        });
        sync.registerSyncedAction("browserProfile", true, false, p -> {
            if (display != null) display.settings(p);
        });
        sync.registerSyncedAction("browserEnd", true, false, p -> close());
        sync.registerSyncedAction("browserEdit", false, true, this::edit);
        sync.registerSyncedAction("browserChannels", false, true, p -> {
            if (matches(p)) channelDrawer = p.readBoolean();
        });
        sync.registerSyncedAction("browserAdopt", false, true, p -> {
            if (!matches(p) || !writable()) return;
            boolean share = p.readBoolean();
            int count = p.readVarIntFromBuffer();
            if (count < 0 || count > definitions.size()) return;
            Set<String> targets = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                String key = readStringSafe(p);
                if (!definitions.containsKey(key)) return;
                targets.add(key);
            }
            Set<String> excluded = new LinkedHashSet<>();
            if (p.isReadable()) {
                int exclusions = p.readVarIntFromBuffer();
                if (exclusions < 0 || exclusions > definitions.size()) return;
                for (int i = 0; i < exclusions; i++) {
                    String key = readStringSafe(p);
                    if (!definitions.containsKey(key)) return;
                    excluded.add(key);
                }
            }
            adopt.apply(tier, (NBTTagCompound) levels.copy(), targets, excluded, share);
            if (source.tier == tier && source.channels.equals(levels)) taskRevision = source.revision;
            sendProfile();
        });
    }

    @SideOnly(Side.CLIENT)
    void install(ParentWidget<?> page, Supplier<String> name) {
        TerminalBrowserWidget widget = new TerminalBrowserWidget(this, name);
        page.child(widget.size(316, 232));
        display = widget;
    }

    public void back() {
        back.run();
    }

    public void edit(long generation, int sequence, String key, int value) {
        sync.callSyncedAction("browserEdit", p -> {
            p.writeLong(generation);
            p.writeInt(sequence);
            writeStringSafe(p, key);
            p.writeVarIntToBuffer(value);
        });
    }

    public void channelsVisible(long generation, boolean visible) {
        sync.callSyncedAction("browserChannels", p -> { p.writeLong(generation); p.writeBoolean(visible); });
    }

    public void adopt(long generation, Set<String> targets, Set<String> excluded, boolean share) {
        sync.callSyncedAction("browserAdopt", p -> {
            p.writeLong(generation);
            p.writeBoolean(share);
            p.writeVarIntToBuffer(targets.size());
            for (String key : targets) writeStringSafe(p, key);
            p.writeVarIntToBuffer(excluded.size());
            for (String key : excluded) writeStringSafe(p, key);
        });
    }

    void open(EntityPlayerMP player, BuildTask task, Collection<IChannelDefinition> channels) {
        close();
        source = task;
        openedTarget = task.target.copy();
        taskRevision = task.revision;
        levels = (NBTTagCompound) task.channels.copy();
        tier = task.tier;
        generation++;
        appliedEdit = 0;
        session = new BrowserSession(player, task, packet -> sync.callSyncedAction("browserData", packet::write));
        profile(channels);
    }

    boolean tick(BuildTask task, Collection<IChannelDefinition> channels, EntityPlayerMP player) {
        if (session == null || task == null || task != source || !Materials.equal(openedTarget, task.target)) return false;
        if (debounce > 0) {
            if (--debounce > 0) return true;
            session.configure(tier, (NBTTagCompound) levels.copy());
        }
        session.tick();
        if (survey != null && survey.plan != task.plan) { survey = null; surveyed = false; }
        boolean newSurvey = false;
        if (channelDrawer && !surveyed && task.existing && task.plan != null && task.plan.ready
                && player.dimension == task.dim && !task.plan.upgradeChannels().isEmpty()) {
            if (survey == null) survey = new ChannelSurvey(task.plan);
            newSurvey = surveyed = survey.advance(player.worldObj);
        }
        if (known != channels.size() || observed != session.observedChannels().size()
                || wasWritable != writable() || newSurvey) profile(channels);
        return true;
    }

    private void profile(Collection<IChannelDefinition> channels) {
        definitions.clear();
        for (IChannelDefinition d : channels) definitions.put(d.getChannel(), d);
        for (String key : session.observedChannels()) {
            if (!key.isEmpty() && !"chisel".equals(key) && !definitions.containsKey(key))
                definitions.put(key, IChannelDefinition.unbounded(key));
        }
        known = channels.size();
        observed = session.observedChannels().size();
        sendProfile();
    }

    private void sendProfile() {
        wasWritable = writable();
        sync.callSyncedAction("browserProfile", p -> {
            p.writeLong(generation);
            p.writeInt(appliedEdit);
            p.writeBoolean(wasWritable);
            p.writeBoolean(tier != source.tier || !levels.equals(source.channels));
            p.writeVarIntToBuffer(tier);
            p.writeVarIntToBuffer(definitions.size());
            for (IChannelDefinition d : definitions.values()) {
                String key = d.getChannel();
                writeStringSafe(p, key);
                p.writeVarIntToBuffer(levels.getInteger(key));
                p.writeVarIntToBuffer(d.getMinimumValue());
                p.writeVarIntToBuffer(d.getMaximumValue());
                boolean replaceable = source.existing && source.plan != null && source.plan.ready
                        && source.plan.upgradeChannels().contains(key);
                p.writeBoolean(replaceable);
                p.writeBoolean(source.upgradeChannels.contains(key));
                boolean site = replaceable && surveyed && survey != null && survey.plan == source.plan;
                p.writeBoolean(site);
                if (site) {
                    Set<Integer> found = survey.levels.getOrDefault(key, Collections.emptySet());
                    p.writeVarIntToBuffer(found.size());
                    for (int level : found) p.writeVarIntToBuffer(level);
                    p.writeBoolean(survey.incomplete.contains(key));
                }
            }
        });
    }

    private boolean matches(PacketBuffer p) {
        long id = p.readLong();
        return session != null && generation == id && selected.get() == source && Materials.equal(openedTarget, source.target);
    }

    private boolean writable() {
        return source.revision == taskRevision && source.canConfigureChannels()
                && (!source.existing || source.plan != null && source.plan.ready);
    }

    private void edit(PacketBuffer p) {
        if (!matches(p)) return;
        int sequence = p.readInt();
        if (sequence <= appliedEdit) return;
        String key = readStringSafe(p);
        int value = p.readVarIntFromBuffer();
        if (key.isEmpty()) {
            int next = Math.max(1, Math.min(4096, value));
            appliedEdit = sequence;
            if (tier == next) { sendProfile(); return; }
            tier = next;
        } else {
            IChannelDefinition d = definitions.get(key);
            if (d == null || value < 0) return;
            int next = value == 0 ? 0 : d.normalizeValue(value);
            appliedEdit = sequence;
            if (levels.getInteger(key) == next) { sendProfile(); return; }
            if (next == 0) levels.removeTag(key);
            else levels.setInteger(key, next);
        }
        debounce = 3;
        sendProfile();
    }

    void leave() {
        close();
        sync.callSyncedAction("browserEnd", p -> {});
    }

    void close() {
        if (session != null) session.close();
        if (display != null) display.close();
        session = null;
        source = null;
        openedTarget = null;
        levels = null;
        definitions.clear();
        debounce = 0;
        channelDrawer = surveyed = false;
        survey = null;
    }
}
