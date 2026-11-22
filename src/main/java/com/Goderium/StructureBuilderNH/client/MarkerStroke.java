package com.Goderium.StructureBuilderNH.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;

/** The section mesh stores its face, visible edges and cell in the unused preview lightmap coordinates. */
final class MarkerStroke {
    private static int program, slot, tint, atlasPass;
    private static UUID targetTask;
    private static int targetX, targetY, targetZ, targetSlot, targetColor, activeSlot;
    private static boolean failed;

    static int face(int cell, int side, int edges, boolean textured) {
        return cell | (256 + (textured ? 128 : 0) + side * 16 + edges) << 16;
    }

    static void target(UUID task, int x, int y, int z, int color) {
        targetTask = task;
        targetX = x >> 4;
        targetY = y >> 4;
        targetZ = z >> 4;
        targetSlot = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        targetColor = color;
    }

    static int slotFor(UUID task, int x, int y, int z) {
        return targetTask != null && targetX == (x >> 4) && targetY == (y >> 4) && targetZ == (z >> 4)
                && targetTask.equals(task) ? targetSlot : -1;
    }

    static boolean changesSlot(int next) { return program != 0 && activeSlot != next; }

    static void select(int next) {
        if (changesSlot(next)) {
            GL20.glUniform1f(slot, next);
            activeSlot = next;
        }
    }

    static int begin() { return begin(false); }

    static int begin(boolean allTextured) {
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program == 0 && !failed) {
            try {
                create();
            } catch (IOException | RuntimeException e) {
                failed = true;
                StructureBuilderNH.LOG.error("Could not initialize preview marker shading", e);
            }
        }
        if (program != 0) {
            GL20.glUseProgram(program);
            GL20.glUniform1i(atlasPass, allTextured ? 1 : 0);
            activeSlot = -1;
            GL20.glUniform1f(slot, activeSlot);
            int color = targetColor;
            GL20.glUniform3f(tint, (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f);
        }
        return previous;
    }

    static void end(int previous) {
        if (program != 0) GL20.glUseProgram(previous);
    }

    private static void create() throws IOException {
        int id = GL20.glCreateProgram();
        try {
            attach(id, GL20.GL_VERTEX_SHADER, "vert");
            attach(id, GL20.GL_FRAGMENT_SHADER, "frag");
            GL20.glLinkProgram(id);
            if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(id, 4096));
            atlasPass = GL20.glGetUniformLocation(id, "allTextured");
            slot = GL20.glGetUniformLocation(id, "selectedSlot");
            tint = GL20.glGetUniformLocation(id, "selectionColor");
            program = id;
        } finally {
            if (program == 0) GL20.glDeleteProgram(id);
        }
    }

    private static void attach(int id, int type, String extension) throws IOException {
        String file = "/assets/structurebuildernh/shaders/marker." + extension;
        try (InputStream input = MarkerStroke.class.getResourceAsStream(file)) {
            if (input == null) throw new IOException(file);
            int shader = GL20.glCreateShader(type);
            try {
                GL20.glShaderSource(shader, IOUtils.toString(input, StandardCharsets.UTF_8));
                GL20.glCompileShader(shader);
                if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(GL20.glGetShaderInfoLog(shader, 4096));
                GL20.glAttachShader(id, shader);
            } finally {
                GL20.glDeleteShader(shader);
            }
        }
    }
}
