package com.Goderium.StructureBuilderNH.client;

import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** Numeric StructureLib level with single-step wheel input. */
public class ChannelLevelField extends TextFieldWidget {
    private final IntSupplier number;
    private final IntUnaryOperator update;

    public ChannelLevelField(IntSupplier number, IntUnaryOperator update) {
        this.number = number;
        this.update = update;
        tooltip(t -> t.addLine(IKey.lang("structurebuildernh.browser.levelHint")));
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        int from = number.getAsInt();
        if (isFocused() && !getText().isEmpty()) from = (int) parse(getText());
        long next = (long) from + direction.modifier;
        int result = update.applyAsInt((int) Math.max(0, Math.min(Integer.MAX_VALUE, next)));
        setText(Integer.toString(result));
        markTooltipDirty();
        return true;
    }
}
