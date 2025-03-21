float getFragDistance(bool isCylindrical, vec3 position) {
    return isCylindrical?max(length(position.xz), abs(position.y)):length(position);
}

float computeFogLerp(vec3 position, bool spherical, float fogStart, float fogEnd) {
    return smoothstep(fogStart, fogEnd, getFragDistance(spherical, position));
}
