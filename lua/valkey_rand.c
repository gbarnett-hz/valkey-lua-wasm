/* Valkey's rand.c, plus exports to carry its state across an instance rebuild (WasmValkeyLua.rebuild). */
#include "rand.c"

/* a and c only ever hold their initial values, so x is the whole state: 48 bits. */
__attribute__((export_name("rand_state"))) uint64_t rand_state(void) {
    return (uint64_t)x[2] << 32 | (uint64_t)x[1] << 16 | x[0];
}

__attribute__((export_name("set_rand_state"))) void set_rand_state(uint64_t s) {
    SET3(x, LOW(s), LOW(s >> 16), LOW(s >> 32));
}
