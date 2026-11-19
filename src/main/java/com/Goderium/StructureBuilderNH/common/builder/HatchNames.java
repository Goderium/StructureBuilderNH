package com.Goderium.StructureBuilderNH.common.builder;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;

import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.IItemContainer;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.util.ExoticEnergyInputHelper;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.HatchElementBuilder;
import gregtech.common.blocks.ItemMachines;

import net.minecraft.item.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/** Logical hatch categories only: no tier/skin enumeration and no registry scan. */
final class HatchNames {
    private static final String[] EMPTY = new String[0];
    private final Map<IStructureElement<?>, String[]> elements = new IdentityHashMap<>();
    private final Map<List<String>, String[]> combinations = new LinkedHashMap<>();

    /** Returned arrays are shared, immutable descriptions expressed as client translation keys. */
    String[] resolve(IStructureElement<?> element, Object context) {
        if (element == null) return EMPTY;
        String[] cached = elements.get(element);
        if (cached != null) return cached;
        // Guard decorated/cyclic definitions without traversing unrelated captured controller state.
        elements.put(element, EMPTY);
        Set<String> names = new TreeSet<>();
        if (element instanceof IStructureElementChain) {
            for (IStructureElement<?> child : ((IStructureElementChain<?>) element).fallbacks())
                Collections.addAll(names, resolve(child, context));
        } else {
            HatchElementBuilder<?> builder = builder(element);
            if (builder != null) {
                collectBuilder(builder, names);
            } else if (element.getClass().getEnclosingClass() == GTStructureUtility.class
                    && element.getClass().getEnclosingMethod() != null
                    && "ofHatchAdder".equals(element.getClass().getEnclosingMethod().getName())) {
                // This old native overload explicitly supplies the accepted MTE class.
                for (Field field : element.getClass().getDeclaredFields()) {
                    if (!"val$aMetaId".equals(field.getName())) continue;
                    Object value = read(field, element);
                    if (value instanceof Function) {
                        try {
                            @SuppressWarnings("unchecked")
                            Function<Object, ?> type = (Function<Object, ?>) value;
                            collectValue(type.apply(context), names, new IdentityHashMap<>(), 0);
                        } catch (RuntimeException | LinkageError ignored) {
                            // An optional/context-dependent provider cannot be safely inferred here.
                        }
                    }
                }
            }
        }
        String[] result = intern(names);
        elements.put(element, result);
        return result;
    }

    private void collectBuilder(HatchElementBuilder<?> builder, Set<String> names) {
        Supplier<List<String>> description = builder.getDescriptionNames();
        if (description == null) return;
        List<String> descriptions;
        try {
            descriptions = description.get();
        } catch (RuntimeException | LinkageError ignored) {
            descriptions = null;
        }
        // hatchClass(s) and descriptionFromStacks retain their actual class/item containers in
        // the native description supplier. Inspect only these bounded, typed captures.
        Map<Object, Boolean> captured = new IdentityHashMap<>();
        collectValue(description, names, captured, 0);
        if (descriptions != null) for (String key : descriptions) {
            boolean className = false;
            for (Object value : captured.keySet()) {
                if (value instanceof Class && ((Class<?>) value).getSimpleName().equals(key)) {
                    className = true;
                    break;
                }
            }
            if (!className) addKey(names, key);
        }
    }

    private static HatchElementBuilder<?> builder(IStructureElement<?> element) {
        if (element.getClass().getEnclosingClass() != HatchElementBuilder.class) return null;
        for (Field field : element.getClass().getDeclaredFields()) {
            if (HatchElementBuilder.class.isAssignableFrom(field.getType())) {
                Object value = read(field, element);
                if (value instanceof HatchElementBuilder) return (HatchElementBuilder<?>) value;
            }
        }
        return null;
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void collectValue(Object value, Set<String> names, Map<Object, Boolean> seen, int depth) {
        if (value == null || depth > 6 || seen.put(value, Boolean.TRUE) != null) return;
        if (value instanceof Class) {
            classify((Class<?>) value, names);
        } else if (value instanceof IHatchElement) {
            try {
                for (String key : ((IHatchElement<?>) value).getDescriptionLangKeys()) addKey(names, key);
            } catch (RuntimeException | LinkageError ignored) {
                // Unknown custom IHatchElement: omit a label rather than guess a different hatch.
            }
        } else if (value instanceof IItemContainer) {
            try {
                collectValue(((IItemContainer) value).get(1), names, seen, depth + 1);
            } catch (RuntimeException | LinkageError ignored) {
                // Unregistered/optional item container.
            }
        } else if (value instanceof ItemStack) {
            IMetaTileEntity machine = ItemMachines.getMetaTileEntity((ItemStack) value);
            if (machine != null) classify(machine.getClass(), names);
        } else if (value instanceof Object[]) {
            Object[] values = (Object[]) value;
            for (int i = 0; i < Math.min(values.length, 128); i++)
                collectValue(values[i], names, seen, depth + 1);
        } else if (value instanceof Map) {
            int count = 0;
            for (Object key : ((Map<?, ?>) value).keySet()) {
                if (count++ == 128) break;
                collectValue(key, names, seen, depth + 1);
            }
        } else if (value instanceof Iterable) {
            int count = 0;
            for (Object item : (Iterable<?>) value) {
                if (count++ == 128) break;
                collectValue(item, names, seen, depth + 1);
            }
        } else if (value instanceof Supplier
                && (value.getClass().isSynthetic() || value.getClass().isAnonymousClass())) {
            for (Field field : value.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                collectValue(read(field, value), names, seen, depth + 1);
            }
        }
    }

    private static void classify(Class<?> type, Set<String> names) {
        if (!IMetaTileEntity.class.isAssignableFrom(type)) return;
        // Native exotic registration is a short class list, not a scan of MTE registrations.
        for (Class<?> exotic : ExoticEnergyInputHelper.getAllClasses()) {
            if (exotic.isAssignableFrom(type)) {
                names.add(HatchElement.ExoticEnergy.getDescriptionLangKey());
                return;
            }
        }
        if (MTEHatchInputBus.class.isAssignableFrom(type)) names.add(HatchElement.InputBus.getDescriptionLangKey());
        else if (MTEHatchOutputBus.class.isAssignableFrom(type)) names.add(HatchElement.OutputBus.getDescriptionLangKey());
        else if (MTEHatchInput.class.isAssignableFrom(type)) names.add(HatchElement.InputHatch.getDescriptionLangKey());
        else if (MTEHatchOutput.class.isAssignableFrom(type)) names.add(HatchElement.OutputHatch.getDescriptionLangKey());
        else if (MTEHatchEnergy.class.isAssignableFrom(type)) names.add(HatchElement.Energy.getDescriptionLangKey());
        else if (MTEHatchDynamo.class.isAssignableFrom(type)) names.add(HatchElement.Dynamo.getDescriptionLangKey());
        else if (MTEHatchMaintenance.class.isAssignableFrom(type)) names.add(HatchElement.Maintenance.getDescriptionLangKey());
        else if (MTEHatchMuffler.class.isAssignableFrom(type)) names.add(HatchElement.Muffler.getDescriptionLangKey());
    }

    private static void addKey(Set<String> names, String key) {
        if (key == null || key.isEmpty() || key.indexOf(' ') >= 0 || key.indexOf('\n') >= 0
                || key.indexOf('\u00a7') >= 0) return;
        // Native descriptionFromStacks emits concrete MTE keys. Its typed item captures are
        // classified above instead. Other namespaces may legitimately contain custom role keys.
        if (key.startsWith("gt.blockmachines.")) return;
        names.add(key);
    }

    private String[] intern(Set<String> names) {
        if (names.isEmpty()) return EMPTY;
        List<String> key = new ArrayList<>(names);
        String[] shared = combinations.get(key);
        if (shared != null) return shared;
        shared = key.toArray(new String[0]);
        combinations.put(Collections.unmodifiableList(key), shared);
        return shared;
    }
}
