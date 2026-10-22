/* Conversions of a double to an integer type, for lua.wasm. Out of range or NaN they are undefined in C, so Valkey
 * gets its CPU's result: arm64 saturates (NaN gives 0), as wasm's trunc_sat does, and x86-64 gives the "integer
 * indefinite" INT_MIN/LLONG_MIN. WasmValkeyLua sets x86 mode on an x86-64 host. patches/ route every conversion a
 * script can observe through these (luaconf.h declares them); the native build keeps plain casts. */
#include <limits.h>

static int x86;

__attribute__((export_name("set_x86_cast"))) void set_x86_cast(int on) { x86 = on; }

long long valkey_d2ll(double d) {
    return x86 && !(d >= -0x1p63 && d < 0x1p63) ? LLONG_MIN : (long long)d;
}

int valkey_d2i(double d) {
    return x86 && !(d > -0x1p31 - 1 && d < 0x1p31) ? INT_MIN : (int)d;
}

/* As gcc compiles it for x86-64: values from 2^63 up are converted less 2^63, everything else (NaN too) as signed. */
unsigned long long valkey_d2ull(double d) {
    if (!x86) return (unsigned long long)d;
    return d >= 0x1p63 ? (unsigned long long)valkey_d2ll(d - 0x1p63) ^ 1ULL << 63 : (unsigned long long)valkey_d2ll(d);
}
