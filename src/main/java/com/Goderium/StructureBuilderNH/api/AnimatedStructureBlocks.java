package com.Goderium.StructureBuilderNH.api;

/** Implement on a live controller to identify structure blocks currently held by its animation. */
public interface AnimatedStructureBlocks {
    /** Read-only, in world coordinates. Return true only while this controller holds the block. */
    boolean ownsAnimatedBlock(int x, int y, int z);
}
