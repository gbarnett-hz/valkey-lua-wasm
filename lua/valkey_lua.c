#include <stdio.h>
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

/* Host operations, all through one import. req is always a RESP2 array of bulk strings;
 * keep the op codes in sync with ValkeyLua.java. */
enum { OP_CALL, OP_SETRESP, OP_SET_REPL, OP_LOG, OP_ACL_CHECK_CMD };

/* Host handles the request and writes back a RESP2 reply it allocated via the exported alloc. */
__attribute__((import_module("env"), import_name("host_call")))
extern void host_call(int op, const char *req, size_t req_len, char **reply, size_t *reply_len);

/* Returns the malloc'd RESP2 reply; the caller frees it. */
static char *hostCall(int op, const char *req, size_t req_len, size_t *reply_len) {
    char *reply;
    host_call(op, req, req_len, &reply, reply_len);
    return reply;
}

/* Pushes one RESP2 reply as Lua, using Redis' RESP2 -> Lua mapping.
 * ponytail: trusts the host to send well-formed RESP; add bounds checks if the reply source becomes untrusted. */
static const char *pushReply(lua_State *L, const char *p) {
    char type = *p++;
    long long n = strtoll(p, NULL, 10);
    const char *line = p;
    size_t line_len = strchr(p, '\r') - p;
    p += line_len + 2;
    switch (type) {
    case '+':
    case '-':
        lua_newtable(L);
        lua_pushlstring(L, line, line_len);
        lua_setfield(L, -2, type == '+' ? "ok" : "err");
        return p;
    case ':':
        lua_pushnumber(L, (lua_Number)n);
        return p;
    case '$':
        if (n < 0) {
            lua_pushboolean(L, 0);
            return p;
        }
        lua_pushlstring(L, p, n);
        return p + n + 2;
    case '*':
        if (n < 0) {
            lua_pushboolean(L, 0);
            return p;
        }
        lua_newtable(L);
        for (long long i = 1; i <= n; i++) {
            p = pushReply(L, p);
            lua_rawseti(L, -2, (int)i);
        }
        return p;
    }
    luaL_error(L, "unexpected RESP type '%c' from host", type);
    return p;
}

static int redisGenericCommand(lua_State *L, int raise_error) {
    int argc = lua_gettop(L);
    if (argc == 0) return luaL_error(L, "Please specify at least one argument for this redis lib call");

    luaL_Buffer b;
    luaL_buffinit(L, &b);
    char tmp[64];
    snprintf(tmp, sizeof(tmp), "*%d\r\n", argc);
    luaL_addstring(&b, tmp);
    for (int i = 1; i <= argc; i++) {
        char num[64];
        const char *s;
        size_t len;
        if (lua_type(L, i) == LUA_TNUMBER) {
            len = snprintf(num, sizeof(num), "%.17g", lua_tonumber(L, i));
            s = num;
        } else if (lua_type(L, i) == LUA_TSTRING) {
            s = lua_tolstring(L, i, &len);
        } else {
            return luaL_error(L, "Lua redis lib command arguments must be strings or integers");
        }
        snprintf(tmp, sizeof(tmp), "$%zu\r\n", len);
        luaL_addstring(&b, tmp);
        luaL_addlstring(&b, s, len);
        luaL_addlstring(&b, "\r\n", 2);
    }
    luaL_pushresult(&b);

    size_t req_len, reply_len;
    const char *req = lua_tolstring(L, -1, &req_len);
    char *reply = hostCall(OP_CALL, req, req_len, &reply_len);
    pushReply(L, reply);
    free(reply);

    if (raise_error && lua_istable(L, -1)) {
        lua_getfield(L, -1, "err");
        int is_err = lua_isstring(L, -1);
        lua_pop(L, 1);
        if (is_err) return lua_error(L);
    }
    return 1;
}

static int redisCall(lua_State *L) { return redisGenericCommand(L, 1); }
static int redisPCall(lua_State *L) { return redisGenericCommand(L, 0); }

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

    lua_newtable(L);
    lua_pushcfunction(L, redisCall);
    lua_setfield(L, -2, "call");
    lua_pushcfunction(L, redisPCall);
    lua_setfield(L, -2, "pcall");
    lua_setglobal(L, "redis");

    int status = luaL_loadbuffer(L, src, len, "@user_script") || lua_pcall(L, 0, 1, 0);

    /* Errors raised as {err=...} tables (e.g. by redis.call) report their message. */
    if (status && lua_istable(L, -1)) {
        lua_getfield(L, -1, "err");
        if (lua_isstring(L, -1)) lua_replace(L, -2);
        else lua_pop(L, 1);
    }

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
