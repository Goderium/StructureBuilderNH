package com.Goderium.StructureBuilderNH.client;

import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** Numeric StructureLib level with single-step wheel input. */
public class ChannelLevelField extends TextFieldWidget {
    private final IntSupplier number, maximum;
    private final IntBinaryOperator step;

    public ChannelLevelField(IntSupplier number, IntBinaryOperator step, IntSupplier maximum) {
        this.number = number;
        this.step = step;
        this.maximum = maximum;
    }

    @Override
    protected void onTextChanged() {
        String input = getText();
        int max = maximum.getAsInt();
        long value = 0;
        for (int i = 0; i < input.length(); i++) {
            value = value * 10 + input.charAt(i) - '0';
            if (value > max) {
                String clamped = Integer.toString(max);
                setText(clamped);
                handler.setCursor(0, clamped.length(), true);
                break;
            }
        }
        super.onTextChanged();
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        int from = number.getAsInt();
        if (isFocused() && !getText().isEmpty()) from = (int) parse(getText());
        int result = step.applyAsInt(from, direction.modifier);
        setText(Integer.toString(result));
        markTooltipDirty();
        return true;
    }
}
