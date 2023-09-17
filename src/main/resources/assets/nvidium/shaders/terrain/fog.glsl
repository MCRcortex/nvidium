float getFragDistance(bool isCylindrical, vec3 position) {
    return isCylindrical?max(length(position.xz), abs(position.y)):length(position);
}

void computeFog(bool spherical, vec3 position, vec4 vertColour, vec4 fogColour, float fogStart, float fogEnd, out vec4 tint, out vec4 addin) {
    float f = smoothstep(fogEnd, fogStart, getFragDistance(spherical, position));
    //vec4 result = fogColor*(1-f)+ tint * f * colour;
    addin = fogColour*(1-f);
    tint = vertColour * f;
    tint.w = 1;
    addin.w = 0;
}
