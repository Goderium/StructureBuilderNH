package com.Goderium.StructureBuilderNH.common;

import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;
import com.Goderium.StructureBuilderNH.common.builder.PreviewEdit;

public class BuilderProxy {
    public void register() {}

    public boolean deleteShortcutKey(int key) { return false; }
    public int hotbarSlot(int key) { return -1; }
    public void rejectDrag(java.util.UUID task, long sequence) {}

    public boolean editPreview(net.minecraft.entity.player.EntityPlayer player) {
        return false;
    }

    public boolean consumePreviewUse(net.minecraft.entity.player.EntityPlayer player) {
        return PreviewEdit.consumeUse(player);
    }

    public void previewEditResult(PreviewEdit.Result result) {}

    public void preview(PreviewStream.Packet packet) {}
}
