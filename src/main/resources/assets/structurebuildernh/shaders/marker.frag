#version 120
uniform sampler2D atlas;
uniform bool allTextured;
uniform vec3 selectionColor;
varying vec4 surfaceColor;
varying vec2 atlasUV;
varying vec2 faceUV;
varying vec4 edges;
varying float marked;
varying float selected;
varying float textured;
void main() {
    vec4 base = surfaceColor;
    if (allTextured || textured > 0.5) base *= texture2D(atlas, atlasUV);
    if (marked > 0.5) {
        if (selected > 0.5) base = vec4(selectionColor, 1.0);
        vec2 pixel = max(fwidth(faceUV), vec2(0.00001));
        vec4 distancePx = vec4(faceUV.x, 1.0-faceUV.x, faceUV.y, 1.0-faceUV.y) / pixel.xxyy;
        float strokePx = textured > 0.5 ? 3.2 : 1.8;
        float maxFraction = textured > 0.5 ? 0.09 : 0.055;
        vec4 width = min(vec4(strokePx), vec4(maxFraction) / pixel.xxyy);
        vec4 ink = edges * clamp(width + 0.5 - distancePx, 0.0, 1.0) * min(width, vec4(1.0));
        float line = max(max(ink.x, ink.y), max(ink.z, ink.w));
        base = mix(base, vec4(0.92, 0.96, 1.0, 1.0), line);
    }
    gl_FragColor = base;
}
