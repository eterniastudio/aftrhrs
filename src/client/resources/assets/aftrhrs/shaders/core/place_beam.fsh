#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

// Aftr Hrs waypoint beams: a soft column of light with a bright core, motes drifting up and a gentle pulse.
// wallCoord.x: 0..1 across the beam, wallCoord.y: 0 at the foot .. 1 at the top.

layout(location = 0) in vec2 wallCoord;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 worldPos;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    return color;
}

void main() {
    float t = GameTime * 1200.0;
    float x = wallCoord.x - 0.5;
    float v = clamp(wallCoord.y, 0.0, 1.0);
    float core = exp(-x * x * 90.0);
    float halo = exp(-x * x * 10.0) * 0.45;
    float motes = smoothstep(0.86, 1.0, fract(v * 9.0 - t * 0.6 + floor(wallCoord.x * 7.0) * 0.37)) * exp(-x * x * 30.0);
    float pulse = 0.8 + 0.2 * sin(t * 2.2);
    float fade = smoothstep(0.0, 0.04, v) * (1.0 - smoothstep(0.35, 1.0, v));
    float glow = (core * 1.2 + halo + motes * 0.9) * pulse;
    vec3 rgb = mix(vertexColor.rgb, vec3(1.0), core * 0.55);
    vec4 color = vec4(rgb * glow, clamp(vertexColor.a * glow * fade, 0.0, 1.0));
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
