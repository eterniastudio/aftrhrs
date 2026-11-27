#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Aftr Hrs claim walls. UV0.x: blocks along the wall (world-continuous), UV0.y: 0 at the foot .. 1 at the top.
// Color: the claim's color, alpha = intensity (distance fade, rising/falling animation).

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 wallCoord;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 worldPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    wallCoord = UV0;
    vertexColor = Color;
    worldPos = Position;
}
