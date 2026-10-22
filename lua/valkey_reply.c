/* EVAL's error handler and reply, from valkey src/modules/lua/engine_lua.c (luaStateInstallErrorHandler) and
 * script_lua.c (luaReplyToServerReply, luaExtractErrorInformation, luaCallFunction), shared by lua.wasm and the JNI
 * natives. Valkey writes the reply to a client with ValkeyModule_ReplyWith*; here it is appended to a respBuf as the
 * RESP2 bytes a RESP2 client would receive (src/networking.c). */
#include <float.h>
#include <limits.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fpconv_dtoa.h"
#include "lauxlib.h"
#include "valkey_reply.h"

#ifdef __wasm__
/* (long long) of an out-of-range or NaN double is undefined in C, so Valkey returns what its CPU's conversion gives.
 * wasm's saturates (NaN gives 0), as arm64's does; the host sets x86 cast mode on x86-64, where cvttsd2si gives
 * INT64_MIN. */
static int x86_cast;
__attribute__((export_name("set_x86_cast"))) void set_x86_cast(int on) { x86_cast = on; }
static long long number2ll(double d) {
    return x86_cast && !(d >= -0x1p63 && d < 0x1p63) ? LLONG_MIN : (long long)d;
}
#else
#define number2ll(d) ((long long)(d))
#endif

#define REGISTRY_ERROR_HANDLER_NAME "__ERROR_HANDLER__"
#define REGISTRY_SCRIPT_RESP_NAME "__SCRIPT_RESP__"

void luaInstallErrorHandler(lua_State *lua) {
    /* Add a helper function we use for pcall error reporting.
     * Note that when the error is in the C function we want to report the
     * information about the caller, that's what makes sense from the point
     * of view of the user debugging a script. */
    lua_pushstring(lua, REGISTRY_ERROR_HANDLER_NAME);
    char *errh_func = "local dbg = debug\n"
                      "debug = nil\n"
                      "local error_handler = function (err)\n"
                      "  local i = dbg.getinfo(2,'nSl')\n"
                      "  if i and i.what == 'C' then\n"
                      "    i = dbg.getinfo(3,'nSl')\n"
                      "  end\n"
                      "  if type(err) ~= 'table' then\n"
                      "    err = {err='ERR ' .. tostring(err)}"
                      "  end"
                      "  if i then\n"
                      "    err['source'] = i.source\n"
                      "    err['line'] = i.currentline\n"
                      "  end"
                      "  return err\n"
                      "end\n"
                      "return error_handler";
    luaL_loadbuffer(lua, errh_func, strlen(errh_func), "@err_handler_def");
    lua_pcall(lua, 0, 1, 0);
    lua_settable(lua, LUA_REGISTRYINDEX);

    /* Duplicate the function with __server__err__handler and
     * __redis__err_handler name for backwards compatibility. */
    lua_pushstring(lua, REGISTRY_ERROR_HANDLER_NAME);
    lua_gettable(lua, LUA_REGISTRYINDEX);
    lua_setglobal(lua, "__server__err__handler");
    lua_getglobal(lua, "__server__err__handler");
    lua_setglobal(lua, "__redis__err__handler");
}

void luaPushErrorHandler(lua_State *lua) {
    lua_pushstring(lua, REGISTRY_ERROR_HANDLER_NAME);
    lua_gettable(lua, LUA_REGISTRYINDEX);
}

/* Valkey keeps the version server.setresp accepted in call_ctx.resp, starting at 2 on every run. */
void luaSetScriptResp(lua_State *lua, int resp) {
    lua_pushinteger(lua, resp);
    lua_setfield(lua, LUA_REGISTRYINDEX, REGISTRY_SCRIPT_RESP_NAME);
}

/* ---------------------------------------------------------------------------------------------------------------
 * RESP2 output (src/networking.c)
 * ------------------------------------------------------------------------------------------------------------- */

/* Valkey aborts when an allocation fails; here it raises inside luaEvalReply's protected call, so eval poisons the
 * instance. */
static void *checkAlloc(lua_State *L, void *p) {
    if (p == NULL) luaL_error(L, "not enough memory");
    return p;
}

static void addReplyProto(respBuf *b, const char *s, size_t len) {
    if (b->len + len > b->cap) {
        size_t cap = (b->len + len) * 2;
        b->buf = checkAlloc(b->L, realloc(b->buf, cap));
        b->cap = cap;
    }
    memcpy(b->buf + b->len, s, len);
    b->len += len;
}

static void addReplyLongLong(respBuf *b, char prefix, long long ll) {
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%c%lld\r\n", prefix, ll);
    addReplyProto(b, buf, n);
}

static void addReplyBulkCBuffer(respBuf *b, const char *p, size_t len) {
    addReplyLongLong(b, '$', (long long)len);
    addReplyProto(b, p, len);
    addReplyProto(b, "\r\n", 2);
}

static void addReplyNull(respBuf *b) {
    addReplyProto(b, "$-1\r\n", 5);
}

/* addReplyErrorFormatInternal with "%s": trims CR/LF at both ends, maps the rest to spaces, then addReplyErrorLength
 * adds "-ERR " unless the text already starts with '-'. Takes ownership of s. */
static void addReplyError(respBuf *b, char *s) {
    checkAlloc(b->L, s);
    size_t len = strlen(s), start = 0;
    while (start < len && (s[start] == '\r' || s[start] == '\n')) start++;
    while (len > start && (s[len - 1] == '\r' || s[len - 1] == '\n')) len--;
    for (size_t i = start; i < len; i++) {
        if (s[i] == '\r' || s[i] == '\n') s[i] = ' ';
    }
    if (len == start || s[start] != '-') addReplyProto(b, "-ERR ", 5);
    addReplyProto(b, s + start, len - start);
    addReplyProto(b, "\r\n", 2);
    free(s);
}

static char *lm_asprintf(char const *fmt, ...) { /* NULL if the allocation failed */
    va_list args;
    va_start(args, fmt);
    size_t str_len = vsnprintf(NULL, 0, fmt, args) + 1;
    va_end(args);
    char *str = malloc(str_len);
    if (str == NULL) return NULL;
    va_start(args, fmt);
    vsnprintf(str, str_len, fmt, args);
    va_end(args);
    return str;
}

static char *lm_strcpy(lua_State *lua, const char *str) {
    size_t len = strlen(str);
    char *res = checkAlloc(lua, malloc(len + 1));
    memcpy(res, str, len + 1);
    return res;
}

/* ValkeyModule_ReplyWith(Custom)ErrorFormat prefix the format with "-". */
static void addReplyModuleError(respBuf *b, const char *msg) {
    addReplyError(b, lm_asprintf("-%s", msg));
}

/* src/util.c */
static int double2ll(double d, long long *out) {
    if (d < (double)(-LLONG_MAX / 2) || d > (double)(LLONG_MAX / 2)) return 0;
    long long ll = d;
    if (ll == d) {
        *out = ll;
        return 1;
    }
    return 0;
}

/* src/util.c d2string */
static int d2string(char *buf, size_t len, double value) {
    if (isnan(value)) {
        len = snprintf(buf, len, "nan");
    } else if (isinf(value)) {
        if (value < 0)
            len = snprintf(buf, len, "-inf");
        else
            len = snprintf(buf, len, "inf");
    } else if (value == 0) {
        if (1.0 / value < 0)
            len = snprintf(buf, len, "-0");
        else
            len = snprintf(buf, len, "0");
    } else {
        long long lvalue;
        if (double2ll(value, &lvalue))
            len = snprintf(buf, len, "%lld", lvalue);
        else {
            len = fpconv_dtoa(value, buf);
            buf[len] = '\0';
        }
    }
    return len;
}

/* addReplyDouble, RESP2 branch: a bulk string. */
static void addReplyDouble(respBuf *b, double d) {
    char dbuf[64];
    int dlen = d2string(dbuf, sizeof(dbuf), d);
    addReplyBulkCBuffer(b, dbuf, dlen);
}

/* ---------------------------------------------------------------------------------------------------------------
 * script_lua.c
 * ------------------------------------------------------------------------------------------------------------- */

typedef struct errorInfo {
    char *msg;
    char *source;
    char *line;
} errorInfo;

static void luaErrorInformationDiscard(errorInfo *err_info) {
    free(err_info->msg);
    free(err_info->source);
    free(err_info->line);
}

static void luaExtractErrorInformation(lua_State *lua, errorInfo *err_info) {
    if (lua_isstring(lua, -1)) {
        err_info->msg = checkAlloc(lua, lm_asprintf("ERR %s", lua_tostring(lua, -1)));
        err_info->line = NULL;
        err_info->source = NULL;
        return;
    }

    lua_getfield(lua, -1, "err");
    if (lua_isstring(lua, -1)) {
        err_info->msg = lm_strcpy(lua, lua_tostring(lua, -1));
    }
    lua_pop(lua, 1);

    lua_getfield(lua, -1, "source");
    if (lua_isstring(lua, -1)) {
        err_info->source = lm_strcpy(lua, lua_tostring(lua, -1));
    }
    lua_pop(lua, 1);

    lua_getfield(lua, -1, "line");
    if (lua_isstring(lua, -1)) {
        err_info->line = lm_strcpy(lua, lua_tostring(lua, -1));
    }
    lua_pop(lua, 1);

    /* ignore_error_stats_update only affects Valkey's error stats, not the reply. It is still read, because the
     * read can run an __index metamethod. */
    lua_getfield(lua, -1, "ignore_error_stats_update");
    lua_pop(lua, 1);

    if (err_info->msg == NULL) {
        /* Ensure we never return a NULL msg. */
        err_info->msg = lm_strcpy(lua, "ERR unknown error");
    }
}

/* copy_string_from_lua_stack: strncpy, so the copy ends at the first NUL. */
static char *copy_string_from_lua_stack(lua_State *lua) {
    const char *str = lua_tostring(lua, -1);
    size_t len = lua_strlen(lua, -1);
    char *res = checkAlloc(lua, malloc(len + 1));
    strncpy(res, str, len);
    res[len] = 0;
    return res;
}

static void strmapchars(char *s, const char *from, const char *to, size_t setlen) {
    for (; *s; s++) {
        for (size_t i = 0; i < setlen; i++) {
            if (*s == from[i]) {
                *s = to[i];
                break;
            }
        }
    }
}

/* Reply converting the top element in the Lua stack to a server reply. As a side effect the element is consumed
 * from the stack. The client is RESP2, so RESP3 types take their RESP2 forms. */
static void luaReplyToServerReply(respBuf *b, int resp_version, lua_State *lua) {
    int t = lua_type(lua, -1);

    if (!lua_checkstack(lua, 4)) {
        /* Increase the Lua stack if needed to make sure there is enough room
         * to push 4 elements to the stack. On failure, return error.
         * Notice that we need, in the worst case, 4 elements because returning a map might
         * require push 4 elements to the Lua stack.*/
        addReplyModuleError(b, "ERR reached lua stack limit");
        lua_pop(lua, 1); /* pop the element from the stack */
        return;
    }

    switch (t) {
    case LUA_TSTRING: addReplyBulkCBuffer(b, lua_tostring(lua, -1), lua_strlen(lua, -1)); break;
    case LUA_TBOOLEAN:
        if (resp_version == 2) {
            int bl = lua_toboolean(lua, -1);
            if (bl) {
                addReplyLongLong(b, ':', 1);
            } else {
                addReplyNull(b);
            }
        } else {
            /* addReplyBool to a RESP2 client */
            addReplyLongLong(b, ':', lua_toboolean(lua, -1) ? 1 : 0);
        }
        break;
    case LUA_TNUMBER: addReplyLongLong(b, ':', number2ll(lua_tonumber(lua, -1))); break;
    case LUA_TTABLE:
        /* We need to check if it is an array, an error, or a status reply.
         * Error are returned as a single element table with 'err' field.
         * Status replies are returned as single element table with 'ok'
         * field. */

        /* Handle error reply. */
        /* we took care of the stack size on function start */
        lua_pushstring(lua, "err");
        lua_rawget(lua, -2);
        t = lua_type(lua, -1);
        if (t == LUA_TSTRING) {
            lua_pop(lua,
                    1); /* pop the error message, we will use luaExtractErrorInformation to get error information */
            errorInfo err_info = {0};
            luaExtractErrorInformation(lua, &err_info);
            addReplyModuleError(b, err_info.msg);
            luaErrorInformationDiscard(&err_info);
            lua_pop(lua, 1); /* pop the result table */
            return;
        }
        lua_pop(lua, 1); /* Discard field name pushed before. */

        /* Handle status reply. */
        lua_pushstring(lua, "ok");
        lua_rawget(lua, -2);
        t = lua_type(lua, -1);
        if (t == LUA_TSTRING) {
            char *ok = copy_string_from_lua_stack(lua);
            strmapchars(ok, "\r\n", "  ", 2);
            addReplyProto(b, "+", 1);
            addReplyProto(b, ok, strlen(ok));
            addReplyProto(b, "\r\n", 2);
            free(ok);
            lua_pop(lua, 2);
            return;
        }
        lua_pop(lua, 1); /* Discard field name pushed before. */

        /* Handle double reply. */
        lua_pushstring(lua, "double");
        lua_rawget(lua, -2);
        t = lua_type(lua, -1);
        if (t == LUA_TNUMBER) {
            addReplyDouble(b, lua_tonumber(lua, -1));
            lua_pop(lua, 2);
            return;
        }
        lua_pop(lua, 1); /* Discard field name pushed before. */

        /* Handle big number reply. */
        lua_pushstring(lua, "big_number");
        lua_rawget(lua, -2);
        t = lua_type(lua, -1);
        if (t == LUA_TSTRING) {
            char *big_num = copy_string_from_lua_stack(lua);
            strmapchars(big_num, "\r\n", "  ", 2);
            addReplyBulkCBuffer(b, big_num, strlen(big_num));
            free(big_num);
            lua_pop(lua, 2);
            return;
        }
        lua_pop(lua, 1); /* Discard field name pushed before. */

        /* Handle verbatim reply. */
        lua_pushstring(lua, "verbatim_string");
        lua_rawget(lua, -2);
        t = lua_type(lua, -1);
        if (t == LUA_TTABLE) {
            lua_pushstring(lua, "format");
            lua_rawget(lua, -2);
            t = lua_type(lua, -1);
            if (t == LUA_TSTRING) {
                lua_pushstring(lua, "string");
                lua_rawget(lua, -3);
                t = lua_type(lua, -1);
                if (t == LUA_TSTRING) {
                    size_t len;
                    char *str = (char *)lua_tolstring(lua, -1, &len);
                    addReplyBulkCBuffer(b, str, len);
                    lua_pop(lua, 4);
                    return;
                }
                lua_pop(lua, 1);
            }
            lua_pop(lua, 1);
        }
        lua_pop(lua, 1); /* Discard field name pushed before. */

        /* Handle map reply: a flat array for a RESP2 client. Its length is known only after the walk, so the
         * elements go to a buffer of their own (VALKEYMODULE_POSTPONED_LEN). */
        lua_pushstring(lua, "map");
        lua_rawget(lua, -2);
        t = lua_type(lua, -1);
        if (t == LUA_TTABLE) {
            int maplen = 0;
            respBuf elems = {.L = lua};
            /* we took care of the stack size on function start */
            lua_pushnil(lua); /* Use nil to start iteration. */
            while (lua_next(lua, -2)) {
                /* Stack now: table, key, value */
                lua_pushvalue(lua, -2);                          /* Dup key before consuming. */
                luaReplyToServerReply(&elems, resp_version, lua); /* Return key. */
                luaReplyToServerReply(&elems, resp_version, lua); /* Return value. */
                /* Stack now: table, key. */
                maplen++;
            }
            addReplyLongLong(b, '*', maplen * 2);
            addReplyProto(b, elems.buf, elems.len);
            free(elems.buf);
            lua_pop(lua, 2);
            return;
        }
        lua_pop(lua, 1); /* Discard field name pushed before. */

        /* Handle set reply: an array for a RESP2 client. */
        lua_pushstring(lua, "set");
        lua_rawget(lua, -2);
        t = lua_type(lua, -1);
        if (t == LUA_TTABLE) {
            int setlen = 0;
            respBuf elems = {.L = lua};
            /* we took care of the stack size on function start */
            lua_pushnil(lua); /* Use nil to start iteration. */
            while (lua_next(lua, -2)) {
                /* Stack now: table, key, true */
                lua_pop(lua, 1);                                  /* Discard the boolean value. */
                lua_pushvalue(lua, -1);                           /* Dup key before consuming. */
                luaReplyToServerReply(&elems, resp_version, lua); /* Return key. */
                /* Stack now: table, key. */
                setlen++;
            }
            addReplyLongLong(b, '*', setlen);
            addReplyProto(b, elems.buf, elems.len);
            free(elems.buf);
            lua_pop(lua, 2);
            return;
        }
        lua_pop(lua, 1); /* Discard field name pushed before. */

        /* Handle the array reply. */
        respBuf elems = {.L = lua};
        int j = 1, mbulklen = 0;
        while (1) {
            /* we took care of the stack size on function start */
            lua_pushnumber(lua, j++);
            lua_rawget(lua, -2);
            t = lua_type(lua, -1);
            if (t == LUA_TNIL) {
                lua_pop(lua, 1);
                break;
            }
            luaReplyToServerReply(&elems, resp_version, lua);
            mbulklen++;
        }
        addReplyLongLong(b, '*', mbulklen);
        addReplyProto(b, elems.buf, elems.len);
        free(elems.buf);
        break;
    default: addReplyNull(b);
    }
    lua_pop(lua, 1);
}

/* luaCallFunction's error branch: the error object of a failed run. */
static void luaReplyToRunError(respBuf *b, lua_State *lua) {
    /* Error object is a table of the following format:
     * {err='<error msg>', source='<source file>', line=<line>}
     * We can construct the error message from this information */
    if (!lua_istable(lua, -1)) {
        const char *msg = "execution failure";
        if (lua_isstring(lua, -1)) {
            msg = lua_tostring(lua, -1);
        }
        addReplyError(b, lm_asprintf("-ERR Error running script, %.100s\n", msg));
    } else {
        errorInfo err_info = {0};
        luaExtractErrorInformation(lua, &err_info);
        if (err_info.line && err_info.source) {
            addReplyError(b, lm_asprintf("-%s script: on %s:%s.", err_info.msg, err_info.source, err_info.line));
        } else {
            addReplyModuleError(b, err_info.msg);
        }
        luaErrorInformationDiscard(&err_info);
    }
    lua_pop(lua, 1); /* Consume the Lua error */
}

typedef struct {
    int kind;
    respBuf *out;
} replyCtx;

/* Stack: value, ctx. */
static int protectedEvalReply(lua_State *lua) {
    replyCtx *ctx = lua_touserdata(lua, 2);
    lua_pop(lua, 1);
    switch (ctx->kind) {
    case EVAL_OK: {
        lua_getfield(lua, LUA_REGISTRYINDEX, REGISTRY_SCRIPT_RESP_NAME);
        int resp = lua_isnumber(lua, -1) ? (int)lua_tointeger(lua, -1) : 2;
        lua_pop(lua, 1);
        luaReplyToServerReply(ctx->out, resp, lua);
        break;
    }
    case EVAL_RUN: luaReplyToRunError(ctx->out, lua); break;
    case EVAL_COMPILE:
        /* engine_lua.c builds the message; eval.c sends it with addReplyErrorFormat(c, "%s", err). */
        addReplyError(ctx->out, lm_asprintf("Error compiling script (new function): %s", lua_tostring(lua, -1)));
        lua_pop(lua, 1);
        break;
    default: /* EVAL_SETUP: this port's own failures, an {err=...} table or a message, without a position */
        if (lua_istable(lua, -1)) {
            lua_pushstring(lua, "err");
            lua_rawget(lua, -2);
        }
        addReplyModuleError(ctx->out, lua_isstring(lua, -1) ? lua_tostring(lua, -1) : "ERR unknown error");
        break;
    }
    return 0;
}

/* Consumes the top of the stack and appends its RESP2 reply. The conversion can raise (an __index metamethod during
 * error extraction, a memory error), so it runs protected; returns -1, with nothing appended, if it raised. */
int luaEvalReply(lua_State *lua, int kind, respBuf *out) {
    replyCtx ctx = {kind, out};
    size_t start = out->len;
    out->L = lua;
    lua_pushcfunction(lua, protectedEvalReply);
    lua_insert(lua, -2);
    lua_pushlightuserdata(lua, &ctx);
    if (lua_pcall(lua, 2, 0, 0)) {
        lua_pop(lua, 1);
        out->len = start;
        return -1;
    }
    return 0;
}
