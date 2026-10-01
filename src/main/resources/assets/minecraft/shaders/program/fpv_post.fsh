#version 150
// FPV Craft - MIT. Clean-room post shader modeled on the FPVEffect(MIT) structure.
// Optics (barrel / vignette / chromatic aberration) + LQ-driven signal glitch.
uniform sampler2D DiffuseSampler;
uniform float Time;
uniform float SignalStrength;
uniform float BarrelK1;
uniform float Vignette;
uniform float ChromaticAberration;
in vec2 texCoord;
out vec4 fragColor;

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    // --- module 1: radial barrel remap (uv' = c + (uv-c)*(1 + k1*r2)) ---
    vec2 c = vec2(0.5);
    vec2 d = texCoord - c;
    float r2 = dot(d, d);
    float scale = 1.0 + BarrelK1 * r2;
    vec2 uv = c + d * scale;

    float badness = 1.0 - clamp(SignalStrength, 0.0, 1.0);
    float b2 = badness * badness;

    // chromatic aberration grows slightly with badness.
    float ca = ChromaticAberration + b2 * 0.010;
    float r = texture(DiffuseSampler, clamp(vec2(uv.x - ca, uv.y), 0.0, 1.0)).r;
    float g = texture(DiffuseSampler, clamp(uv, 0.0, 1.0)).g;
    float b = texture(DiffuseSampler, clamp(vec2(uv.x + ca, uv.y), 0.0, 1.0)).b;
    vec3 col = vec3(r, g, b);

    // --- module 2: digital block corrupt (>=0.25) / rolling bar / grain ---
    if (badness >= 0.25) {
        vec2 blockUV = floor(uv * 32.0) / 32.0;
        float n = hash21(blockUV + floor(Time * 4.0));
        if (n < mix(1.0, 0.65, (badness - 0.25) / 0.75)) {
            vec2 off = (vec2(
                floor(hash21(blockUV + vec2(0.1, Time)) * 8.0) - 4.0,
                floor(hash21(blockUV + vec2(0.2, Time)) * 4.0) - 2.0)
            ) / 320.0;
            col = texture(DiffuseSampler, clamp(uv + off, 0.0, 1.0)).rgb;
        }
    }
    float barY = fract(uv.y + Time * 0.07 * (0.5 + badness));
    float bar = smoothstep(0.0, 0.04, barY) * (1.0 - smoothstep(0.04, 0.08, barY));
    col *= 1.0 - bar * 0.35 * b2;

    float grain = (hash21(uv + vec2(Time * 0.037, Time * 0.019)) - 0.5);
    col += grain * (0.02 + badness * 0.10);

    // --- module 1 vignette ---
    vec2 vig = d; vig.x *= 1.1;
    float vigF = 1.0 - dot(vig, vig) * (1.8 * Vignette + badness);
    col *= clamp(vigF, 0.0, 1.0);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
