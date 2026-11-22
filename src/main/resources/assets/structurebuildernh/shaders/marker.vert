#version 120
varying vec4 surfaceColor;
uniform float selectedSlot;
varying vec2 atlasUV;
varying vec2 faceUV;
varying vec4 edges;
varying float marked;
varying float selected;
varying float textured;
void main() {
    gl_Position = ftransform();
    surfaceColor = gl_Color;
    atlasUV = gl_MultiTexCoord0.xy;
    float slot = gl_MultiTexCoord1.x;
    float tag = gl_MultiTexCoord1.y;
    marked = step(256.0, tag);
    textured = tag == 240.0 || tag >= 384.0 ? 1.0 : 0.0;
    selected = marked * float(slot == selectedSlot);
    faceUV = atlasUV;
    if (marked > 0.5 && textured > 0.5) {
        vec3 cell = vec3(mod(slot, 16.0), floor(slot / 256.0), mod(floor(slot / 16.0), 16.0));
        vec3 local = gl_Vertex.xyz - cell;
        float axis = floor(mod(tag, 128.0) / 32.0);
        vec2 offset = axis < 1.0 ? local.yz : axis < 2.0 ? local.zx : local.xy;
        // Display lists can bake the 16-block section origin into vertex positions.
        // Strip that translation at each vertex, preserving both 0 and 1 at face edges.
        faceUV = offset - 16.0 * floor(offset / 16.0 + 0.5);
    }
    edges = mod(floor(tag / vec4(1.0, 2.0, 4.0, 8.0)), 2.0);
}
