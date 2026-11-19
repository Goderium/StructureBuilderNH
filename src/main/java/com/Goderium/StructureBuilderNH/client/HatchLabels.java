package com.Goderium.StructureBuilderNH.client;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Client-local display labels. Structure declarations remain the authority for accepted hatch types. */
@SideOnly(Side.CLIENT)
final class HatchLabels {
    static final String SEPARATOR = " | ";
    private static final String[] NO_HINTS = new String[0];
    private static final Map<String, HatchRole> KEY_ROLES = standardRoles();
    private static final Map<String, Hint> HINTS = new LinkedHashMap<String, Hint>(32, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Hint> entry) {
            return size() > 128;
        }
    };
    private static LocaleData locale;

    private static Map<String, HatchRole> standardRoles() {
        Map<String, HatchRole> roles = new HashMap<>();
        roles.put("GT5U.MBTT.InputBus", HatchRole.INPUT_BUS);
        roles.put("GTPP.MBTT.SteamInputBus", HatchRole.INPUT_BUS);
        roles.put("GT5U.MBTT.OutputBus", HatchRole.OUTPUT_BUS);
        roles.put("GTPP.MBTT.SteamOutputBus", HatchRole.OUTPUT_BUS);
        roles.put("GT5U.MBTT.InputHatch", HatchRole.INPUT_HATCH);
        roles.put("GTPP.MBTT.SteamHatch", HatchRole.INPUT_HATCH);
        roles.put("GT5U.MBTT.OutputHatch", HatchRole.OUTPUT_HATCH);
        roles.put("GT5U.MBTT.InputAny", HatchRole.MULTI);
        roles.put("GT5U.MBTT.OutputAny", HatchRole.MULTI);
        roles.put("GT5U.MBTT.EnergyHatch", HatchRole.ENERGY);
        roles.put("GT5U.MBTT.ExoticEnergyHatch", HatchRole.ENERGY);
        roles.put("GT5U.MBTT.MultiampEnergyHatch", HatchRole.ENERGY);
        roles.put("GT5U.MBTT.LaserTargetHatch", HatchRole.ENERGY);
        roles.put("GT5U.MBTT.DynamoHatch", HatchRole.DYNAMO);
        roles.put("GT5U.MBTT.ExoticEnergyDynamo", HatchRole.DYNAMO);
        roles.put("GT5U.MBTT.LaserSourceHatch", HatchRole.DYNAMO);
        roles.put("GT5U.MBTT.MaintenanceHatch", HatchRole.MAINTENANCE);
        roles.put("GT5U.MBTT.MufflerHatch", HatchRole.MUFFLER);
        return Collections.unmodifiableMap(roles);
    }

    /** Also call this on a resource reload that keeps the same language. */
    static void clear() {
        locale = null;
        HINTS.clear();
    }

    static String[] dotHints(int controllerMeta, int tier) {
        try {
            if (controllerMeta < 0 || controllerMeta >= GregTechAPI.METATILEENTITIES.length) return NO_HINTS;
            IMetaTileEntity controller = GregTechAPI.METATILEENTITIES[controllerMeta];
            if (!(controller instanceof IConstructable)) return NO_HINTS;
            ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem(), Math.max(1, tier));
            String[] description = ((IConstructable) controller).getStructureDescription(trigger);
            if (description == null || description.length == 0) return NO_HINTS;
            List<String> lines = new ArrayList<>(description.length);
            for (String line : description) {
                String clean = plain(line);
                if (!clean.isEmpty()) lines.add(clean);
            }
            String[] hints = new String[17];
            LocaleData language = locale();
            boolean found = false;
            for (int dots = 1; dots <= 16; dots++) {
                String prefix = translated("structurelib.blockhint." + (dots - 1) + ".name");
                if (prefix.isEmpty()) continue;
                Set<String> names = new LinkedHashSet<>();
                for (String line : lines) {
                    if (!line.startsWith(prefix)) continue;
                    String suffix = line.substring(prefix.length()).trim();
                    // GT's public hint format uses a literal colon, independent of the selected language.
                    if (!suffix.startsWith(":")) continue;
                    for (String token : suffix.substring(1).trim().split(", ", -1)) {
                        String name = nativeName(token);
                        if (usable(name, language)) names.add(name);
                    }
                }
                if (!names.isEmpty()) {
                    // Preserve GT's original token separator until select() classifies standard versus custom names.
                    hints[dots] = String.join(", ", names);
                    found = true;
                }
            }
            return found ? hints : NO_HINTS;
        } catch (RuntimeException | LinkageError ignored) {
            // Optional/legacy constructables may not support a prototype tooltip. Keep the rest of the GUI usable.
            return NO_HINTS;
        }
    }

    static String categories(String[] keys) {
        Set<String> names = new LinkedHashSet<>();
        LocaleData language = locale();
        if (keys != null)
            for (String key : keys) {
                String name = translated(key);
                if (usable(name, language)) names.add(name);
            }
        return String.join(SEPARATOR, names);
    }

    static String select(String dotHint, String[] keys, boolean ambiguous) {
        String exact = categories(keys);
        if (ambiguous) return exact;
        Hint hint = hint(dotHint);
        return hint.dedicated || exact.isEmpty() ? hint.text : exact;
    }

    static HatchRole role(String dotHint, String[] keys, boolean ambiguous) {
        HatchRole exact = null;
        boolean declared = false;
        if (keys != null)
            for (String key : keys) {
                if (key == null || key.trim().isEmpty()) continue;
                declared = true;
                exact = combine(exact, KEY_ROLES.getOrDefault(key, HatchRole.SPECIAL));
            }
        if (ambiguous || dotHint == null || dotHint.trim().isEmpty())
            return exact == null ? HatchRole.SPECIAL : exact;
        Hint hint = hint(dotHint);
        if (hint.dedicated) return HatchRole.SPECIAL;
        return declared ? exact : hint.role;
    }

    private static HatchRole combine(HatchRole first, HatchRole next) {
        if (first == null) return next;
        if (first == HatchRole.SPECIAL || next == HatchRole.SPECIAL) return HatchRole.SPECIAL;
        return first == next ? first : HatchRole.MULTI;
    }

    private static Hint hint(String text) {
        String clean = plain(text);
        if (clean.isEmpty()) return Hint.NONE;
        LocaleData language = locale();
        Hint cached = HINTS.get(clean);
        if (cached != null) return cached;
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : clean.split(", ", -1)) {
            String name = nativeName(token);
            if (usable(name, language)) tokens.add(name);
        }
        if (language.standard == null) {
            language.standard = new HashMap<>();
            for (Map.Entry<String, HatchRole> entry : KEY_ROLES.entrySet()) {
                String name = translated(entry.getKey());
                if (usable(name, language))
                    language.standard.merge(name, entry.getValue(), HatchLabels::combine);
            }
        }
        HatchRole role = null;
        for (String name : tokens)
            role = combine(role, language.standard.getOrDefault(name, HatchRole.SPECIAL));
        boolean dedicated = tokens.size() == 1 && !language.standard.containsKey(tokens.iterator().next());
        Hint result = new Hint(String.join(SEPARATOR, tokens), dedicated, role == null ? HatchRole.SPECIAL : role);
        HINTS.put(clean, result);
        return result;
    }

    private static final class Hint {
        static final Hint NONE = new Hint("", false, HatchRole.SPECIAL);
        final String text;
        final boolean dedicated;
        final HatchRole role;

        Hint(String text, boolean dedicated, HatchRole role) {
            this.text = text;
            this.dedicated = dedicated;
            this.role = role;
        }
    }

    private static final class LocaleData {
        final String language;
        final Set<String> ignored = new HashSet<>();
        Map<String, HatchRole> standard;

        LocaleData(String language) {
            this.language = language;
            for (String key : new String[] {"GT5U.MBTT.Air", "structurebuildernh.previewAir", "tile.air.name"}) {
                String name = translated(key);
                if (!name.isEmpty()) ignored.add(name);
            }
        }
    }

    private static LocaleData locale() {
        Minecraft minecraft = Minecraft.getMinecraft();
        String language = minecraft == null || minecraft.gameSettings == null ? "" : minecraft.gameSettings.language;
        if (locale == null || !Objects.equals(locale.language, language)) {
            locale = new LocaleData(language);
            HINTS.clear();
        }
        return locale;
    }

    private static String translated(String key) {
        if (key == null || key.trim().isEmpty()) return "";
        try {
            return StatCollector.canTranslate(key) ? plain(StatCollector.translateToLocal(key)) : "";
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    private static boolean usable(String name, LocaleData language) {
        if (name == null || name.isEmpty() || "?".equals(name) || "unknown".equalsIgnoreCase(name)
                || "null".equalsIgnoreCase(name)) return false;
        return !language.ignored.contains(name);
    }

    private static String nativeName(String text) {
        String name = plain(text);
        String translation = translated(name);
        if (!translation.isEmpty()) return translation;
        // The public tooltip contains display text, but a missing translation may leak its key.
        // Restrict this check to a dotted identifier so ordinary one-word dedicated names stay valid.
        boolean dot = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') dot = true;
            else if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '$') return name;
        }
        return dot ? "" : name;
    }

    private static String plain(String text) {
        return text == null ? "" : EnumChatFormatting.getTextWithoutFormattingCodes(text).trim();
    }

}
