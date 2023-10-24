#ifndef SORTING_INDEX_TYPE
#define SORTING_INDEX_TYPE uint8_t
#endif

shared float threadBufferFloat[SORTING_NETWORK_SIZE];
shared SORTING_INDEX_TYPE threadBufferIndex[SORTING_NETWORK_SIZE];

void localSortA(const uint scaleBits) {
    uint base = (gl_LocalInvocationID.x>>scaleBits)*(1<<(scaleBits+1));
    uint offsetA = (gl_LocalInvocationID.x&((1<<scaleBits)-1));
    uint offsetB = (1<<(scaleBits+1))-1-offsetA;
    float a = threadBufferFloat[base + offsetA];
    float b = threadBufferFloat[base + offsetB];
    if (a < b) {
        threadBufferFloat[base + offsetA] = b;
        threadBufferFloat[base + offsetB] = a;

        SORTING_INDEX_TYPE tmp = threadBufferIndex[base + offsetA];
        threadBufferIndex[base + offsetA] = threadBufferIndex[base + offsetB];
        threadBufferIndex[base + offsetB] = tmp;
    }

    barrier();
    memoryBarrierShared();
}

void localSortB(const uint scaleBits) {
    uint base = (gl_LocalInvocationID.x>>scaleBits)*(1<<(scaleBits+1));
    base += (gl_LocalInvocationID.x&((1<<scaleBits)-1));
    uint offset = 1<<scaleBits;
    float a = threadBufferFloat[base];
    float b = threadBufferFloat[base + offset];
    if (a < b) {
        threadBufferFloat[base] = b;
        threadBufferFloat[base + offset] = a;

        SORTING_INDEX_TYPE tmp = threadBufferIndex[base];
        threadBufferIndex[base] = threadBufferIndex[base + offset];
        threadBufferIndex[base + offset] = tmp;
    }

    barrier();
    memoryBarrierShared();
}

void putSortingData(SORTING_INDEX_TYPE index, float data) {
    threadBufferIndex[uint(index)] = index;
    threadBufferFloat[uint(index)] = data;
}