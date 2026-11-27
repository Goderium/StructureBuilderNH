package com.Goderium.StructureBuilderNH.common.builder;

import static com.cleanroommc.modularui.network.NetworkUtils.readStringSafe;
import static com.cleanroommc.modularui.network.NetworkUtils.writeStringSafe;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
        void apply(int tier, NBTTagCompound levels, Set<String> changedChannels, boolean share);
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
    private Map<String, Integer> observedMaximums = Collections.emptyMap();
    private Display display;
    private BuildTask source;
    private ItemStack openedTarget;
    private BrowserSession session;
    private NBTTagCompound levels, openingLevels;
    private int tier, taskRevision, known, observed, debounce, appliedEdit;
    private boolean wasWritable, wasSitePending, channelDrawer, localSite, waitingForSiteTier, mainChannelEdited;
    private ChannelSurvey survey, completedSurvey;
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
        sync.registerSyncedAction("browserCache", false, true, p -> {
            if (!matches(p)) return;
            UUID stream = new UUID(p.readLong(), p.readLong());
            session.answerCache(stream, p.readInt(), p.readBoolean());
        });
        sync.registerSyncedAction("browserChannels", false, true, p -> {
            if (matches(p)) channelDrawer = p.readBoolean();
        });
        sync.registerSyncedAction("browserAdopt", false, true, p -> {
            if (!matches(p) || !writable()) return;
            boolean share = p.readBoolean();
            Set<String> changes = changedChannels();
            if (!share && tier == source.tier && changes.isEmpty()) return;
            adopt.apply(tier, (NBTTagCompound) levels.copy(), changes, share);
            taskRevision = source.revision;
            if (!share) openingLevels = (NBTTagCompound) levels.copy();
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

    public void answerCache(long generation, BrowserPacket offer, boolean hit) {
        sync.callSyncedAction("browserCache", p -> {
            p.writeLong(generation);
            p.writeLong(offer.id.getMostSignificantBits());
            p.writeLong(offer.id.getLeastSignificantBits());
            p.writeInt(offer.revision);
            p.writeBoolean(hit);
        });
    }

    public void channelsVisible(long generation, boolean visible) {
        sync.callSyncedAction("browserChannels", p -> { p.writeLong(generation); p.writeBoolean(visible); });
    }

    public void adopt(long generation, boolean share) {
        sync.callSyncedAction("browserAdopt", p -> {
            p.writeLong(generation);
            p.writeBoolean(share);
        });
    }

    void open(EntityPlayerMP player, BuildTask task, Collection<IChannelDefinition> channels) {
        close();
        source = task;
        localSite = player.dimension == task.dim;
        waitingForSiteTier = localSite && task.checkingSiteTier();
        openedTarget = task.target.copy();
        taskRevision = task.revision;
        levels = (NBTTagCompound) task.channels.copy();
        BuildTask.removeInternalPreviewChannel(openedTarget, levels);
        openingLevels = (NBTTagCompound) levels.copy();
        tier = task.tier;
        generation++;
        appliedEdit = 0;
        session = new BrowserSession(player, task, packet -> sync.callSyncedAction("browserData", packet::write));
        if (task.plan != null && task.plan.ready) observedMaximums = task.plan.channelMaximums();
        profile(channels);
    }

    boolean tick(BuildTask task, Collection<IChannelDefinition> channels, EntityPlayerMP player) {
        if (session == null || task == null || task != source || !Materials.equal(openedTarget, task.target)) return false;
        localSite = player.dimension == task.dim;
        if (localSite && task.checkingSiteTier()) {
            if (!waitingForSiteTier) {
                waitingForSiteTier = true;
                profile(channels);
            }
            return true;
        }
        if (waitingForSiteTier) {
            boolean drawerOpen = channelDrawer;
            open(player, task, channels);
            channelDrawer = drawerOpen;
        }
        if (debounce > 0) {
            if (--debounce > 0) return true;
            session.configure(tier, (NBTTagCompound) levels.copy(), mainChannelEdited);
        }
        if (survey != null && survey.plan != task.plan) survey = null;
        boolean newSurvey = false;
        if ((channelDrawer || siteValuesPending()) && !hasCurrentSurvey() && task.existing && localSite
                && task.plan != null && task.plan.ready && !task.plan.upgradeChannels().isEmpty()) {
            if (survey == null) survey = new ChannelSurvey(task.plan);
            newSurvey = survey.advance(player.worldObj);
            if (newSurvey) {
                completedSurvey = survey;
                adoptSiteLevels();
                session.configure(tier, levels, mainChannelEdited);
            }
        }
        // A local installed structure can reveal the native channel levels after its blueprint is captured.
        if (!siteValuesPending()) session.tick();
        Map<String, Integer> maximums = session.channelMaximums();
        boolean boundsChanged = maximums != null && !maximums.equals(observedMaximums);
        if (boundsChanged) observedMaximums = maximums;
        if (known != channels.size() || observed != session.observedChannels().size() || boundsChanged
                || wasWritable != writable() || wasSitePending != siteValuesPending() || newSurvey) profile(channels);
        return true;
    }

    private void adoptSiteLevels() {
        for (Map.Entry<String, Set<Integer>> row : survey.values.entrySet()) {
            String key = row.getKey();
            if (openingLevels.hasKey(key) || row.getValue().size() != 1 || survey.incomplete.contains(key)) continue;
            int value = row.getValue().iterator().next();
            openingLevels.setInteger(key, value);
            if (!levels.hasKey(key)) levels.setInteger(key, value);
        }
    }

    private Set<String> changedChannels() {
        Set<String> changed = new LinkedHashSet<>();
        for (String key : definitions.keySet()) {
            if (levels.getInteger(key) != openingLevels.getInteger(key)) changed.add(key);
        }
        return changed;
    }

    private void profile(Collection<IChannelDefinition> channels) {
        definitions.clear();
        for (IChannelDefinition d : channels) definitions.put(d.getChannel(), d);
        for (String key : session.observedChannels()) {
            if (!key.isEmpty() && !"chisel".equals(key) && !definitions.containsKey(key))
                definitions.put(key, IChannelDefinition.unbounded(key));
        }
        definitions.keySet().removeIf(key -> BuildTask.isInternalPreviewChannel(openedTarget, key));
        definitions.replaceAll((key, definition) -> ChannelUpgrade.withObservedMaximum(definition, observedMaximums));
        for (IChannelDefinition definition : definitions.values()) {
            String key = definition.getChannel();
            int value = levels.getInteger(key), maximum = definition.getMaximumValue();
            if (value > maximum) {
                levels.setInteger(key, maximum);
                debounce = 3;
            }
        }
        known = channels.size();
        observed = session.observedChannels().size();
        sendProfile();
    }

    private void sendProfile() {
        wasWritable = writable();
        wasSitePending = siteValuesPending();
        sync.callSyncedAction("browserProfile", p -> {
            p.writeLong(generation);
            p.writeInt(appliedEdit);
            p.writeBoolean(wasWritable);
            p.writeBoolean(tier != source.tier || !changedChannels().isEmpty());
            p.writeVarIntToBuffer(tier);
            p.writeVarIntToBuffer(definitions.size());
            for (IChannelDefinition d : definitions.values()) {
                String key = d.getChannel();
                writeStringSafe(p, key);
                p.writeVarIntToBuffer(levels.getInteger(key));
                p.writeVarIntToBuffer(d.getMinimumValue());
                p.writeVarIntToBuffer(d.getMaximumValue());
                writeSiteChannel(p, key);
            }
            p.writeBoolean(wasSitePending);
            p.writeVarIntToBuffer(openedTarget.getMetadata());
        });
    }

    private void writeSiteChannel(PacketBuffer p, String key) {
        // Applying settings invalidates the target blueprint while the installed structure is unchanged.
        boolean site = source.existing && completedSurvey != null && completedSurvey.plan.upgradeChannels().contains(key);
        p.writeBoolean(site);
        if (!site) return;
        Set<Integer> found = completedSurvey.values.getOrDefault(key, Collections.emptySet());
        p.writeVarIntToBuffer(found.size());
        for (int level : found) p.writeVarIntToBuffer(level);
        p.writeBoolean(completedSurvey.incomplete.contains(key));
    }

    private boolean matches(PacketBuffer p) {
        long id = p.readLong();
        return session != null && generation == id && selected.get() == source && Materials.equal(openedTarget, source.target);
    }

    private boolean writable() {
        return !waitingForSiteTier && source.revision == taskRevision && source.canConfigureChannels()
                && (!source.existing || source.plan != null && source.plan.ready
                        && (source.plan.upgradeChannels().isEmpty() || hasCurrentSurvey()));
    }

    private boolean siteValuesPending() {
        if (waitingForSiteTier) return true;
        if (!localSite || !source.existing || completedSurvey != null || session.closed()
                || "unsupported".equals(source.reason)) return false;
        if (source.plan != null && source.plan.ready) return !source.plan.upgradeChannels().isEmpty();
        return source.canConfigureChannels();
    }

    private boolean hasCurrentSurvey() {
        return completedSurvey != null && completedSurvey.plan == source.plan;
    }

    private void edit(PacketBuffer p) {
        if (!matches(p) || siteValuesPending()) return;
        int sequence = p.readInt();
        if (sequence <= appliedEdit) return;
        String key = readStringSafe(p);
        int value = p.readVarIntFromBuffer();
        if (key.isEmpty()) {
            int next = Math.max(1, Math.min(4096, value));
            appliedEdit = sequence;
            if (tier == next && mainChannelEdited) { sendProfile(); return; }
            mainChannelEdited = true;
            tier = next;
        } else {
            IChannelDefinition d = definitions.get(key);
            if (d == null || value < 0) return;
            int next = value == 0 ? 0
                    : Math.min(d.getMaximumValue(), Math.max(d.getMinimumValue(), d.normalizeValue(value)));
            appliedEdit = sequence;
            if (levels.getInteger(key) == next) { sendProfile(); return; }
            if (next == 0 && !source.existing) levels.removeTag(key);
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
        levels = openingLevels = null;
        definitions.clear();
        observedMaximums = Collections.emptyMap();
        debounce = 0;
        channelDrawer = localSite = waitingForSiteTier = mainChannelEdited = false;
        survey = completedSurvey = null;
    }
}
