#version 150
in vec4 Position;
uniform mat4 ProjMat;
out vec2 texCoord;
void main() {
    gl_Position = ProjMat * Position;
    texCoord = Position.xy * 0.5 + 0.5;
}
