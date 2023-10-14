float getFragDistance(bool isCylindrical, vec3 position) {
    return isCylindrical?max(length(position.xz), abs(position.y)):length(position);
}

void computeFog(bool spherical, vec3 position, vec4 vertColour, vec4 fogColour, float fogStart, float fogEnd, out vec3 tint, out vec3 addin) {
    float f = smoothstep(fogStart, fogEnd, getFragDistance(spherical, position));
    f *= fogColour.a;
    //vec4 result = fogColor*(1-f)+ tint * f * colour;
    addin = fogColour.xyz * f;
    tint = vertColour.xyz * (1 - f);
}
