#include <stdlib.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

LUALIB_API int(luaopen_cjson)(lua_State *L);
LUALIB_API int(luaopen_struct)(lua_State *L);
LUALIB_API int(luaopen_cmsgpack)(lua_State *L);
LUALIB_API int(luaopen_bit)(lua_State *L);

/* Mirrors luaLoadLib/luaLoadLibraries in valkey src/modules/lua/script_lua.c. */
static void loadLib(lua_State *L, const char *name, lua_CFunction f) {
    lua_pushcfunction(L, f);
    lua_pushstring(L, name);
    lua_call(L, 1, 0);
}

/* WASI has no temp dirs, so wasi-libc omits mkstemp; os.tmpname errors instead. */
int mkstemp(char *template) {
    (void)template;
    return -1;
}

static char *result;
static size_t result_size;

__attribute__((export_name("alloc"))) void *wasm_alloc(size_t n) { return malloc(n); }
__attribute__((export_name("dealloc"))) void wasm_dealloc(void *p) { free(p); }
__attribute__((export_name("result_ptr"))) char *result_ptr(void) { return result; }
__attribute__((export_name("result_len"))) size_t result_len(void) { return result_size; }

/* Runs a script in a fresh state. Returns 0 on success, 1 on Lua error.
 * The first return value (or the error message) is converted with tostring()
 * and left in result_ptr/result_len until the next call. */
__attribute__((export_name("eval"))) int eval(const char *src, size_t len) {
    lua_State *L = luaL_newstate();
    loadLib(L, "", luaopen_base);
    loadLib(L, LUA_TABLIBNAME, luaopen_table);
    loadLib(L, LUA_STRLIBNAME, luaopen_string);
    loadLib(L, LUA_MATHLIBNAME, luaopen_math);
    loadLib(L, LUA_DBLIBNAME, luaopen_debug);
    loadLib(L, LUA_OSLIBNAME, luaopen_os);
    loadLib(L, "cjson", luaopen_cjson);
    loadLib(L, "struct", luaopen_struct);
    loadLib(L, "cmsgpack", luaopen_cmsgpack);
    loadLib(L, "bit", luaopen_bit);

    int status = luaL_loadbuffer(L, src, len, "@user_script") || lua_pcall(L, 0, 1, 0);

    lua_getglobal(L, "tostring");
    lua_insert(L, -2);
    lua_call(L, 1, 1);
    size_t n;
    const char *s = lua_tolstring(L, -1, &n);
    free(result);
    result = malloc(n);
    memcpy(result, s, n);
    result_size = n;

    lua_close(L);
    return status ? 1 : 0;
}
