#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

// Aftr Hrs claim walls: an additive "energy curtain". A hex lattice that breathes, a scan band climbing the wall,
// streaks rising like fireflies and a bright seam at the foot. Fades out towards the top and the bottom.

layout(location = 0) in vec2 wallCoord;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 worldPos;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

const float WALL_HEIGHT = 5.0;

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    return color;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Distance to the nearest edge of a hex cell (0 on the edges).
float hexEdge(vec2 p) {
    const vec2 s = vec2(1.0, 1.7320508);
    vec2 a = mod(p, s) - s * 0.5;
    vec2 b = mod(p + s * 0.5, s) - s * 0.5;
    vec2 g = dot(a, a) < dot(b, b) ? a : b;
    vec2 q = abs(g);
    return 0.5 - max(dot(q, normalize(s)), q.x);
}

void main() {
    // GameTime is the day fraction; this gives seconds that wrap every 20 minutes.
    float t = GameTime * 1200.0;
    float u = wallCoord.x;
    float v = clamp(wallCoord.y, 0.0, 1.0);
    vec2 p = vec2(u, v * WALL_HEIGHT);

    float fade = smoothstep(0.0, 0.10, v) * (1.0 - smoothstep(0.45, 1.0, v));

    float edge = hexEdge(p * 1.6 + vec2(0.0, t * 0.15));
    float lattice = 1.0 - smoothstep(0.0, 0.06, edge);
    float breathe = 0.55 + 0.45 * sin(t * 1.6 + hash(floor(p * 1.6)) * 6.2831);

    float band = fract(v * 0.8 - t * 0.22);
    float scan = exp(-pow((band - 0.5) * 9.0, 2.0));

    float lane = floor(u * 2.0);
    float rise = fract(v * 1.4 - t * (0.35 + hash(vec2(lane, 3.0)) * 0.4) + hash(vec2(lane, 7.0)));
    float streak = smoothstep(0.92, 1.0, rise) * step(0.55, hash(vec2(lane, 11.0)));

    float seam = exp(-v * 22.0);

    float glow = 0.18 + lattice * 0.55 * breathe + scan * 0.45 + streak * 0.8;
    vec3 rgb = vertexColor.rgb * glow + vec3(seam * 0.9) + vertexColor.rgb * seam * 1.2;
    float alpha = vertexColor.a * (fade * glow + seam);

    vec4 color = vec4(rgb, clamp(alpha, 0.0, 1.0));
    if (color.a <= 0.003) {
        discard;
    }
    color *= ColorModulator;

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
