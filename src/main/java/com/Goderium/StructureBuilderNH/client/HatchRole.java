package com.Goderium.StructureBuilderNH.client;

/** Logical port colors, independent of voltage tier, concrete model and client language. */
enum HatchRole {
    INPUT_BUS(0x78be91),
    OUTPUT_BUS(0xe6aa65),
    INPUT_HATCH(0x70c9cf),
    OUTPUT_HATCH(0xe58c86),
    ENERGY(0x71ade0),
    DYNAMO(0xdac26e),
    MAINTENANCE(0xadb7bc),
    MUFFLER(0xabb872),
    MULTI(0x91b6b0),
    SPECIAL(0xdb97b7);

    final int color;

    HatchRole(int color) {
        this.color = color;
    }
}
