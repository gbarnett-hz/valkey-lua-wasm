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

/* Port of luaPushErrorBuff in valkey src/modules/lua/script_lua.c (minus the ldb logging). */
static char *trimCRLF(const char *s, size_t *len) {
    while (*s == '\r' || *s == '\n') s++;
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\r' || s[n - 1] == '\n')) n--;
    *len = n;
    return (char *)s;
}

/* Pushes an error table in the format server.pcall uses, {err="CODE msg"}.
 * There are two possible formats for the received `error` string:
 * 1) "-CODE msg": we remove the leading '-' since we don't store it as part of the lua error format.
 * 2) "msg":       we prepend a generic 'ERR' code since all error statuses need some error code.
 * Callers must not embed an error code in the message; pass "-CODE msg" if they want a specific code. */
static void luaPushErrorBuff(lua_State *lua, const char *err_buffer) {
    const char *code = "ERR";
    size_t code_len = 3, msg_len;
    const char *msg;
    if (err_buffer[0] == '-') {
        const char *sp = strchr(err_buffer, ' ');
        if (!sp) {
            msg = trimCRLF(err_buffer + 1, &msg_len);
        } else {
            code = err_buffer + 1;
            code_len = sp - code;
            msg = trimCRLF(sp + 1, &msg_len);
        }
    } else {
        msg = trimCRLF(err_buffer, &msg_len);
    }
    char *final_msg = malloc(code_len + 1 + msg_len + 1);
    memcpy(final_msg, code, code_len);
    final_msg[code_len] = ' ';
    memcpy(final_msg + code_len + 1, msg, msg_len);
    final_msg[code_len + 1 + msg_len] = '\0';

    lua_newtable(lua);
    lua_pushstring(lua, "err");
    lua_pushstring(lua, final_msg);
    lua_settable(lua, -3);
    free(final_msg);
}

void luaPushError(lua_State *lua, const char *error) {
    luaPushErrorBuff(lua, error);
}

/* Port of luaProcessReplyError; the rewrites match on the message prefix only (no errno across the Wasm boundary). */
static void luaProcessReplyError(const char *err, lua_State *lua) {
    int push_error = 1;
    /* The following error messages rewrites are required to keep the backward compatibility
     * with the previous Lua engine that was implemented in Valkey core. */
    if (strncmp(err, "ERR command ", strlen("ERR command ")) == 0) {
        luaPushError(lua, "This Valkey command is not allowed from script");
        push_error = 0;
    } else if (strncmp(err, "ERR wrong number of arguments for ", strlen("ERR wrong number of arguments for ")) == 0) {
        luaPushError(lua, "Wrong number of args calling command from script");
        push_error = 0;
    } else if (strncmp(err, "ERR unknown command '", strlen("ERR unknown command '")) == 0) {
        luaPushError(lua, "Unknown command called from script");
        push_error = 0;
    } else if (strncmp(err, "NOPERM ", strlen("NOPERM ")) == 0) {
        const char *err_prefix = "ACL failure in script: ";
        size_t err_len = strlen(err_prefix) + strlen(err + strlen("NOPERM ")) + 1;
        char *err_msg = malloc(err_len);
        strcpy(err_msg, err_prefix);
        strcat(err_msg, err + strlen("NOPERM "));
        luaPushError(lua, err_msg);
        free(err_msg);
        push_error = 0;
    }

    if (push_error) {
        /* `err` is of the form "CODE msg"; re-add the '-' so luaPushErrorBuff() treats the
         * leading word as the error code and doesn't prepend another "ERR" code. */
        size_t n = strlen(err);
        char *err_with_dash = malloc(n + 2);
        err_with_dash[0] = '-';
        memcpy(err_with_dash + 1, err, n + 1);
        luaPushError(lua, err_with_dash);
        free(err_with_dash);
    }
    /* push a field indicate to ignore updating the stats on this error
     * because it was already updated when executing the command. */
    lua_pushstring(lua, "ignore_error_stats_update");
    lua_pushboolean(lua, 1);
    lua_settable(lua, -3);
}

/* Parses the integer in [p, line_end); returns 0 if it is not one. */
static int parseInt(const char *p, const char *line_end, long long *out) {
    int neg = 0;
    if (p < line_end && *p == '-') neg = 1, p++;
    if (p == line_end || line_end - p > 18) return 0;
    long long n = 0;
    for (; p < line_end; p++) {
        if (*p < '0' || *p > '9') return 0;
        n = n * 10 + (*p - '0');
    }
    *out = neg ? -n : n;
    return 1;
}

/* Pushes one RESP2 reply as Lua, using Valkey's RESP2 -> Lua mapping. Returns the position after the reply,
 * or NULL if it is malformed or truncated; never reads past end. Sets *error if an error reply appears at any depth. */
static const char *pushReply(lua_State *L, const char *p, const char *end, int *error) {
    /* Also bounds the recursion depth: Lua's C stack limit makes this fail on absurdly nested arrays. */
    if (p >= end || !lua_checkstack(L, 3)) return NULL;
    char type = *p++;
    const char *line = p;
    const char *eol = NULL;
    for (const char *q = p; q + 1 < end; q++) {
        if (q[0] == '\r' && q[1] == '\n') {
            eol = q;
            break;
        }
    }
    if (!eol) return NULL;
    size_t line_len = eol - line;
    p = eol + 2;
    long long n = 0;
    switch (type) {
    case '+':
        lua_newtable(L);
        lua_pushlstring(L, line, line_len);
        lua_setfield(L, -2, "ok");
        return p;
    case '-': {
        char *err = malloc(line_len + 1);
        memcpy(err, line, line_len);
        err[line_len] = '\0';
        luaProcessReplyError(err, L);
        free(err);
        *error = 1;
        return p;
    }
    case ':':
        if (!parseInt(line, eol, &n)) return NULL;
        lua_pushnumber(L, (lua_Number)n);
        return p;
    case '$':
        if (!parseInt(line, eol, &n) || n < -1) return NULL;
        if (n < 0) {
            lua_pushboolean(L, 0);
            return p;
        }
        if (n > end - p || end - p - n < 2 || p[n] != '\r' || p[n + 1] != '\n') return NULL;
        lua_pushlstring(L, p, n);
        return p + n + 2;
    case '*':
        if (!parseInt(line, eol, &n) || n < -1) return NULL;
        if (n < 0) {
            lua_pushboolean(L, 0);
            return p;
        }
        if (n > end - p) return NULL; /* every element takes at least one byte */
        lua_newtable(L);
        for (long long i = 1; i <= n; i++) {
            p = pushReply(L, p, end, error);
            if (!p) return NULL;
            lua_rawseti(L, -2, (int)i);
        }
        return p;
    }
    return NULL;
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
    int error = 0;
    const char *end = pushReply(L, reply, reply + reply_len, &error) ? reply + reply_len : NULL;
    free(reply);
    if (!end) {
        luaPushError(L, "unexpected reply from handler for server.call");
        return lua_error(L);
    }
    if (error && raise_error) return lua_error(L);
    return 1;
}

/* Port of luaRedisPcall: server.pcall-style error tables are turned into their message string. */
static int luaRedisPcall(lua_State *lua) {
    int argc = lua_gettop(lua);
    lua_pushboolean(lua, 1); /* result place holder */
    lua_insert(lua, 1);
    if (lua_pcall(lua, argc - 1, LUA_MULTRET, 0)) {
        /* Error */
        lua_remove(lua, 1); /* remove the result place holder, now we have room for at least one element */
        if (lua_istable(lua, -1)) {
            lua_getfield(lua, -1, "err");
            if (lua_isstring(lua, -1)) {
                lua_replace(lua, -2); /* replace the error message with the table */
            }
        }
        lua_pushboolean(lua, 0); /* push result */
        lua_insert(lua, 1);
    }
    return lua_gettop(lua);
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
    lua_pushvalue(L, -1);
    lua_setglobal(L, "server");
    lua_setglobal(L, "redis");
    lua_pushcfunction(L, luaRedisPcall);
    lua_setglobal(L, "pcall");

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
