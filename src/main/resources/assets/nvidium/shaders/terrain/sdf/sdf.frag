
void traceStep(uint sdfStep, inout uvec3 position, ivec3 dir, uvec3 invdir, ivec3 sign, ivec3 delta) {
    //((sdf-(mx*(x&((1<<10)-1))+bx))*idx)>>>10
    uvec3 blockDelta = (sign*(position) + delta);
    uvec3 stepTimes = ((sdfStep-blockDelta)*invdir) >> 10;
    uint stepTime = min(stepTimes.x, min(stepTimes.y, stepTimes.z));
    stepTime += 10;
    position += (dir*stepTime)>>10;
}

int trace(uint8_t* sdf, uint32_t* colours, vec3 start, vec3 dir) {

}