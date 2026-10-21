#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

#include "fpconv_dtoa.h"
#include "rand.h"
#include "sha1.h"
#include "valkey_reply.h"

LUALIB_API int(luaopen_cjson)(lua_State *L);
LUALIB_API int(luaopen_struct)(lua_State *L);
LUALIB_API int(luaopen_cmsgpack)(lua_State *L);
LUALIB_API int(luaopen_bit)(lua_State *L);
void luaLockGlobals(lua_State *L); /* valkey_globals.c */

/* Mirrors luaLoadLib/luaLoadLibraries in valkey src/modules/lua/script_lua.c. */
static void loadLib(lua_State *L, const char *name, lua_CFunction f) {
    lua_pushcfunction(L, f);
    lua_pushstring(L, name);
    lua_call(L, 1, 0);
}

/* wasi-libc omits mkstemp, which loslib.c's os_tmpname references. Valkey only registers os.clock, so it never runs. */
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

/* Stand-ins for the lm_* string helpers valkey's Lua module uses. */
static char *lm_strcpy(const char *s) {
    size_t n = strlen(s) + 1;
    return memcpy(malloc(n), s, n);
}

/* Trims, in place, any characters in cset from both ends. */
static char *lm_strtrim(char *s, const char *cset) {
    char *start = s, *end = s + strlen(s);
    while (start < end && strchr(cset, *start)) start++;
    while (end > start && strchr(cset, end[-1])) end--;
    memmove(s, start, end - start);
    s[end - start] = '\0';
    return s;
}

static char *lm_asprintf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    char *out = malloc(n + 1);
    va_start(ap, fmt);
    vsnprintf(out, n + 1, fmt, ap);
    va_end(ap);
    return out;
}

/* This function is used in order to push an error on the Lua stack in the
 * format used by server.pcall to return errors, which is a lua table
 * with an "err" field set to the error string including the error code.
 * Note that this table is never a valid reply by proper commands,
 * since the returned tables are otherwise always indexed by integers, never by strings.
 *
 * The function takes ownership on the given err_buffer. */
static void luaPushErrorBuff(lua_State *lua, const char *err_buffer) {
    char *msg;

    char *final_msg = NULL;
    /* There are two possible formats for the received `error` string:
     * 1) "-CODE msg": we remove the leading '-' since we don't store it as part of the lua error format.
     * 2) "msg":       we prepend a generic 'ERR' code since all error statuses need some error code.
     * We support format (1) so this function can reuse the error messages used in other places.
     * We support format (2) so it'll be easy to pass descriptive errors to this function without worrying about format.
     * Callers must not embed an error code in the message — pass "-CODE msg" if they want a specific code.
     */
    if (err_buffer[0] == '-') {
        /* derive error code from the message */
        char *err_msg = strstr(err_buffer, " ");
        if (!err_msg) {
            msg = lm_strcpy(err_buffer + 1);
            final_msg = lm_asprintf("ERR %s", msg);
        } else {
            *err_msg = '\0';
            msg = lm_strcpy(err_msg + 1);
            msg = lm_strtrim(msg, "\r\n");
            final_msg = lm_asprintf("%s %s", err_buffer + 1, msg);
        }
    } else {
        msg = lm_strcpy(err_buffer);
        msg = lm_strtrim(msg, "\r\n");
        final_msg = lm_asprintf("ERR %s", msg);
    }
    /* Trim newline at end of string. If we reuse the ready-made error objects (case 1 above) then we might
     * have a newline that needs to be trimmed. In any case the lua server error table shouldn't end with a newline. */

    lua_newtable(lua);
    lua_pushstring(lua, "err");
    lua_pushstring(lua, final_msg);
    lua_settable(lua, -3);

    free(msg);
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
    /* strtoll() would also skip leading whitespace and accept '+'. */
    if (p == line_end || (*p != '-' && (*p < '0' || *p > '9'))) return 0;
    char *e;
    errno = 0;
    *out = strtoll(p, &e, 10);
    return e == line_end && !errno;
}

#define MAX_NESTED_COLLECTIONS_DEPTH 256

/* Pushes one RESP2 reply as Lua, using Valkey's RESP2 -> Lua mapping. Returns the position after the reply,
 * or NULL if it is malformed, truncated or nested deeper than MAX_NESTED_COLLECTIONS_DEPTH; never reads past end.
 * Sets *error if an error reply appears at any depth. */
static const char *pushReply(lua_State *L, const char *p, const char *end, int *error, int depth) {
    if (p >= end || depth > MAX_NESTED_COLLECTIONS_DEPTH || !lua_checkstack(L, 3)) return NULL;
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
            p = pushReply(L, p, end, error, depth + 1);
            if (!p) return NULL;
            lua_rawseti(L, -2, (int)i);
        }
        return p;
    }
    return NULL;
}

/* Return the number of digits of 'v' when converted to string in radix 10.
 * See ll2string() for more information. */
static uint32_t digits10(uint64_t v) {
    if (v < 10) return 1;
    if (v < 100) return 2;
    if (v < 1000) return 3;
    if (v < 1000000000000UL) {
        if (v < 100000000UL) {
            if (v < 1000000) {
                if (v < 10000) return 4;
                return 5 + (v >= 100000);
            }
            return 7 + (v >= 10000000UL);
        }
        if (v < 10000000000UL) {
            return 9 + (v >= 1000000000UL);
        }
        return 11 + (v >= 100000000000UL);
    }
    return 12 + digits10(v / 1000000000000UL);
}

/* Convert a unsigned long long into a string. Returns the number of
 * characters needed to represent the number.
 * If the buffer is not big enough to store the string, 0 is returned.
 *
 * Based on the following article (that apparently does not provide a
 * novel approach but only publicizes an already used technique):
 *
 * https://web.archive.org/web/20150427221229/https://www.facebook.com/notes/facebook-engineering/three-optimization-tips-for-c/10151361643253920 */
static int ull2string(char *dst, size_t dstlen, unsigned long long value) {
    static const char digits[201] = "0001020304050607080910111213141516171819"
                                    "2021222324252627282930313233343536373839"
                                    "4041424344454647484950515253545556575859"
                                    "6061626364656667686970717273747576777879"
                                    "8081828384858687888990919293949596979899";

    /* Check length. */
    uint32_t length = digits10(value);
    if (length >= dstlen) goto err;
    ;

    /* Null term. */
    uint32_t next = length - 1;
    dst[next + 1] = '\0';
    while (value >= 100) {
        int const i = (value % 100) * 2;
        value /= 100;
        dst[next] = digits[i + 1];
        dst[next - 1] = digits[i];
        next -= 2;
    }

    /* Handle last 1-2 digits. */
    if (value < 10) {
        dst[next] = '0' + (uint32_t)value;
    } else {
        int i = (uint32_t)value * 2;
        dst[next] = digits[i + 1];
        dst[next - 1] = digits[i];
    }
    return length;
err:
    /* force add Null termination */
    if (dstlen > 0) dst[0] = '\0';
    return 0;
}

/* Convert a long long into a string. Returns the number of
 * characters needed to represent the number.
 * If the buffer is not big enough to store the string, 0 is returned. */
static int ll2string(char *dst, size_t dstlen, long long svalue) {
    unsigned long long value;
    int negative = 0;

    /* The ull2string function with 64bit unsigned integers for simplicity, so
     * we convert the number here and remember if it is negative. */
    if (svalue < 0) {
        if (svalue != LLONG_MIN) {
            value = -svalue;
        } else {
            value = ((unsigned long long)LLONG_MAX) + 1;
        }
        if (dstlen < 2) goto err;
        negative = 1;
        dst[0] = '-';
        dst++;
        dstlen--;
    } else {
        value = svalue;
    }

    /* Converts the unsigned long long value to string*/
    int length = ull2string(dst, dstlen, value);
    if (length == 0) return 0;
    return length + negative;

err:
    /* force add Null termination */
    if (dstlen > 0) dst[0] = '\0';
    return 0;
}

/* Returns 1 if the double value can safely be represented in long long without
 * precision loss, in which case the corresponding long long is stored in the out variable. */
static int double2ll(double d, long long *out) {
#if (__DBL_MANT_DIG__ >= 52) && (__DBL_MANT_DIG__ <= 63) && (LLONG_MAX == 0x7fffffffffffffffLL)
    /* Check if the float is in a safe range to be casted into a
     * long long. We are assuming that long long is 64 bit here.
     * Also we are assuming that there are no implementations around where
     * double has precision < 52 bit.
     *
     * Under this assumptions we test if a double is inside a range
     * where casting to long long is safe. Then using two castings we
     * make sure the decimal part is zero. If all this is true we can use
     * integer without precision loss.
     *
     * Note that numbers above 2^52 and below 2^63 use all the fraction bits as real part,
     * and the exponent bits are positive, which means the "decimal" part must be 0.
     * i.e. all double values in that range are representable as a long without precision loss,
     * but not all long values in that range can be represented as a double.
     * we only care about the first part here. */
    if (d < (double)(-LLONG_MAX / 2) || d > (double)(LLONG_MAX / 2)) return 0;
    long long ll = d;
    if (ll == d) {
        *out = ll;
        return 1;
    }
#else
#endif
    return 0;
}

/* Encodes the Lua call arguments as a RESP2 array of bulk strings into b. On failure pushes the Valkey
 * error table and returns 0. Port of luaArgsToServerArgv, which builds an argv instead of a request. */
static int luaArgsToRequest(lua_State *lua, luaL_Buffer *b) {
    int j;
    /* Require at least one argument */
    int argc = lua_gettop(lua);
    if (argc == 0) {
        luaPushError(lua, "Please specify at least one argument for this call");
        return 0;
    }

    char hdr[32];
    luaL_addlstring(b, hdr, snprintf(hdr, sizeof(hdr), "*%d\r\n", argc));

    for (j = 0; j < argc; j++) {
        char *obj_s;
        size_t obj_len;
        char dbuf[64];

        if (lua_type(lua, j + 1) == LUA_TNUMBER) {
            /* We can't use lua_tolstring() for number -> string conversion
             * since Lua uses a format specifier that loses precision. */
            lua_Number num = lua_tonumber(lua, j + 1);
            /* Integer printing function is much faster, check if we can safely use it.
             * Since lua_Number is not explicitly an integer or a double, we need to make an effort
             * to convert it as an integer when that's possible, since the string could later be used
             * in a context that doesn't support scientific notation (e.g. 1e9 instead of 100000000). */
            long long lvalue;
            if (double2ll((double)num, &lvalue)) {
                obj_len = ll2string(dbuf, sizeof(dbuf), lvalue);
            } else {
                obj_len = fpconv_dtoa((double)num, dbuf);
                dbuf[obj_len] = '\0';
            }
            obj_s = dbuf;
        } else {
            obj_s = (char *)lua_tolstring(lua, j + 1, &obj_len);
            if (obj_s == NULL) break; /* Not a string. */
        }

        luaL_addlstring(b, hdr, snprintf(hdr, sizeof(hdr), "$%zu\r\n", obj_len));
        luaL_addlstring(b, obj_s, obj_len);
        luaL_addlstring(b, "\r\n", 2);
    }

    /* Check if one of the arguments passed by the Lua script
     * is not a string or an integer (lua_isstring() return true for
     * integers as well). */
    if (j != argc) {
        luaPushError(lua, "Command arguments must be strings or integers");
        return 0;
    }
    return 1;
}

static int redisGenericCommand(lua_State *L, int raise_error) {
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    if (!luaArgsToRequest(L, &b)) return raise_error ? lua_error(L) : 1;
    luaL_pushresult(&b);

    size_t req_len, reply_len;
    const char *req = lua_tolstring(L, -1, &req_len);
    char *reply = hostCall(OP_CALL, req, req_len, &reply_len);
    int error = 0;
    /* Trailing bytes after the reply make it malformed too. */
    int malformed = !reply_len || pushReply(L, reply, reply + reply_len, &error, 0) != reply + reply_len;
    free(reply);
    if (malformed) {
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

/* Port of sha1hex from script_lua.c. digest must hold 41 bytes. */
static void sha1hex(char *digest, char *script, size_t len) {
    SHA1_CTX ctx;
    unsigned char hash[20];
    char *cset = "0123456789abcdef";
    int j;

    SHA1Init(&ctx);
    SHA1Update(&ctx, (unsigned char *)script, len);
    SHA1Final(hash, &ctx);

    for (j = 0; j < 20; j++) {
        digest[j * 2] = cset[((hash[j] & 0xF0) >> 4)];
        digest[j * 2 + 1] = cset[(hash[j] & 0xF)];
    }
    digest[40] = '\0';
}

static int luaRedisSha1hexCommand(lua_State *lua) {
    int argc = lua_gettop(lua);
    char digest[41];
    size_t len;
    char *s;

    if (argc != 1) {
        luaPushError(lua, "wrong number of arguments");
        return lua_error(lua);
    }

    s = (char *)lua_tolstring(lua, 1, &len);
    sha1hex(digest, s, len);
    lua_pushstring(lua, digest);
    return 1;
}

static int luaRedisReturnSingleFieldTable(lua_State *lua, char *field) {
    if (lua_gettop(lua) != 1 || lua_type(lua, -1) != LUA_TSTRING) {
        luaPushError(lua, "wrong number or type of arguments");
        return 1;
    }

    lua_newtable(lua);
    lua_pushstring(lua, field);
    lua_pushvalue(lua, -3);
    lua_settable(lua, -3);
    return 1;
}

static int luaRedisErrorReplyCommand(lua_State *lua) {
    if (lua_gettop(lua) != 1 || lua_type(lua, -1) != LUA_TSTRING) {
        luaPushError(lua, "wrong number or type of arguments");
        return 1;
    }

    /* add '-' if not exists */
    const char *err = lua_tostring(lua, -1);
    char *err_buff = NULL;
    if (err[0] != '-') {
        err_buff = lm_asprintf("-%s", err);
    } else {
        err_buff = lm_strcpy(err);
    }
    luaPushErrorBuff(lua, err_buff);
    free(err_buff);
    return 1;
}

static int luaRedisStatusReplyCommand(lua_State *lua) {
    return luaRedisReturnSingleFieldTable(lua, "ok");
}

/* No debugger here, so these take the "not active" branches of the upstream functions. */
static int luaServerDebugCommand(lua_State *lua) {
    (void)lua;
    return 0;
}

static int luaServerBreakpointCommand(lua_State *lua) {
    lua_pushboolean(lua, 0);
    return 1;
}

static int luaServerReplicateCommandsCommand(lua_State *lua) {
    lua_pushboolean(lua, 1);
    return 1;
}

/* Validates and consumes (frees) a non-call reply. An error reply is raised as a Lua error; with want '+' the
 * reply must be exactly +OK, with ':' exactly :0 or :1 (returned). Anything else raises "unexpected reply". */
static int expectReply(lua_State *L, char *reply, size_t len, const char *fn, char want) {
    int value = -1;
    if (len > 0 && reply[0] == '-') {
        char *err = lm_asprintf("%.*s", (int)len, reply);
        free(reply);
        luaPushErrorBuff(L, err);
        free(err);
        return lua_error(L);
    }
    if (want == '+' && len == 5 && memcmp(reply, "+OK\r\n", 5) == 0) value = 1;
    else if (want == ':' && len == 4 && memcmp(reply, ":", 1) == 0 && (reply[1] == '0' || reply[1] == '1') &&
             memcmp(reply + 2, "\r\n", 2) == 0) value = reply[1] - '0';
    free(reply);
    if (value < 0) {
        char *msg = lm_asprintf("unexpected reply from handler for %s", fn);
        luaPushError(L, msg);
        free(msg);
        return lua_error(L);
    }
    return value;
}

/* Sends [v] to the handler and expects +OK. */
static void hostCallInt(lua_State *L, int op, int v, const char *fn) {
    char num[16], req[48];
    int n = snprintf(num, sizeof(num), "%d", v);
    int req_len = snprintf(req, sizeof(req), "*1\r\n$%d\r\n%s\r\n", n, num);
    size_t reply_len;
    char *reply = hostCall(op, req, req_len, &reply_len);
    expectReply(L, reply, reply_len, fn, '+');
}

static int luaSetResp(lua_State *lua) {
    int argc = lua_gettop(lua);
    if (argc != 1) {
        luaPushError(lua, "server.setresp() requires one argument.");
        return lua_error(lua);
    }
    int resp = lua_tonumber(lua, -argc);
    if (resp != 2 && resp != 3) {
        luaPushError(lua, "RESP version must be 2 or 3.");
        return lua_error(lua);
    }
    hostCallInt(lua, OP_SETRESP, resp, "server.setresp");
    luaSetScriptResp(lua, resp);
    return 0;
}

static int luaRedisSetReplCommand(lua_State *lua) {
    int flags, argc = lua_gettop(lua);
    if (argc != 1) {
        luaPushError(lua, "server.set_repl() requires one argument.");
        return lua_error(lua);
    }
    flags = lua_tonumber(lua, -1);
    if ((flags & ~3) != 0) {
        luaPushError(lua, "Invalid replication flags. Use REPL_AOF, REPL_REPLICA, REPL_ALL or REPL_NONE.");
        return lua_error(lua);
    }
    hostCallInt(lua, OP_SET_REPL, flags, "server.set_repl");
    return 0;
}

static int luaLogCommand(lua_State *lua) {
    int j, argc = lua_gettop(lua);
    int level;

    if (argc < 2) {
        luaPushError(lua, "server.log() requires two arguments or more.");
        return lua_error(lua);
    } else if (!lua_isnumber(lua, -argc)) {
        luaPushError(lua, "First argument must be a number (log level).");
        return lua_error(lua);
    }
    level = lua_tonumber(lua, -argc);
    if (level < 0 || level > 3) {
        luaPushError(lua, "Invalid log level.");
        return lua_error(lua);
    }

    /* Glue together all the arguments, as Valkey does, except that a non-string first argument is simply skipped
     * and NULs are kept (Valkey formats with %s, which truncates at a NUL). */
    luaL_Buffer msg;
    luaL_buffinit(lua, &msg);
    for (j = 1; j < argc; j++) {
        size_t len;
        const char *s = lua_tolstring(lua, j + 1, &len);
        if (s) {
            if (j != 1) luaL_addchar(&msg, ' ');
            luaL_addlstring(&msg, s, len);
        }
    }
    luaL_pushresult(&msg);
    size_t msg_len;
    const char *m = lua_tolstring(lua, -1, &msg_len);

    /* [level, message] */
    char hdr[48];
    luaL_Buffer b;
    luaL_buffinit(lua, &b);
    luaL_addlstring(&b, hdr, snprintf(hdr, sizeof(hdr), "*2\r\n$1\r\n%d\r\n$%zu\r\n", level, msg_len));
    luaL_addlstring(&b, m, msg_len);
    luaL_addlstring(&b, "\r\n", 2);
    luaL_pushresult(&b);

    size_t req_len, reply_len;
    const char *req = lua_tolstring(lua, -1, &req_len);
    char *reply = hostCall(OP_LOG, req, req_len, &reply_len);
    expectReply(lua, reply, reply_len, "server.log", '+');
    return 0;
}

static int luaRedisAclCheckCmdPermissionsCommand(lua_State *lua) {
    luaL_Buffer b;
    luaL_buffinit(lua, &b);
    if (!luaArgsToRequest(lua, &b)) return lua_error(lua);
    luaL_pushresult(&b);

    size_t req_len, reply_len;
    const char *req = lua_tolstring(lua, -1, &req_len);
    char *reply = hostCall(OP_ACL_CHECK_CMD, req, req_len, &reply_len);
    lua_pushboolean(lua, expectReply(lua, reply, reply_len, "server.acl_check_cmd", ':'));
    return 1;
}

/* Per-eval reset: RESP2 and replicate to AOF and replicas. Run protected so a failure becomes the eval error. */
static int resetHostState(lua_State *L) {
    hostCallInt(L, OP_SETRESP, 2, "server.setresp");
    hostCallInt(L, OP_SET_REPL, 3, "server.set_repl");
    return 0;
}

static uint32_t parse_semver(const char *version) {
    unsigned int major = 0, minor = 0, patch = 0;
    sscanf(version, "%u.%u.%u", &major, &minor, &patch);
    return ((major & 0xFF) << 16) | ((minor & 0xFF) << 8) | (patch & 0xFF);
}

/* Fixed per instance; set once by set_server_info. */
static char *redis_version = "", *valkey_version = "", *server_name = "";

/* req is a RESP2 array of bulk strings: [redisVersion, valkeyVersion, serverName]. */
__attribute__((export_name("set_server_info"))) void set_server_info(const char *req, size_t len) {
    const char *p = req, *end = req + len;
    char **fields[3] = {&redis_version, &valkey_version, &server_name};
    while (p < end && *p++ != '\n'); /* skip "*3\r\n" */
    for (int i = 0; i < 3 && p < end; i++) {
        long n = strtol(p + 1, NULL, 10); /* "$<n>\r\n" */
        while (p < end && *p++ != '\n');
        *fields[i] = memcpy(calloc(n + 1, 1), p, n);
        p += n + 2;
    }
}

static void setField(lua_State *L, const char *name, lua_CFunction f) {
    lua_pushcfunction(L, f);
    lua_setfield(L, -2, name);
}

static void setNumber(lua_State *L, const char *name, lua_Number n) {
    lua_pushnumber(L, n);
    lua_setfield(L, -2, name);
}

static void setString(lua_State *L, const char *name, const char *s) {
    lua_pushstring(L, s);
    lua_setfield(L, -2, name);
}

static char *result;
static size_t result_size;

__attribute__((export_name("alloc"))) void *wasm_alloc(size_t n) { return malloc(n); }
__attribute__((export_name("dealloc"))) void wasm_dealloc(void *p) { free(p); }
__attribute__((export_name("result_ptr"))) char *result_ptr(void) { return result; }
__attribute__((export_name("result_len"))) size_t result_len(void) { return result_size; }

/* ---------------------------------------------------------------------------
 * Custom provided math.random (script_lua.c)
 * ------------------------------------------------------------------------- */

/* We replace math.random() with our implementation that is not affected
 * by specific libc random() implementations and will output the same sequence
 * (for the same seed) in every arch. */

/* The following implementation is the one shipped with Lua itself but with
 * rand() replaced by serverLrand48(). */
static int server_math_random(lua_State *L) {
    /* the `%' avoids the (rare) case of r==1, and is needed also because on
       some systems (SunOS!) `rand()' may return a value larger than RAND_MAX */
    lua_Number r = (lua_Number)(serverLrand48() % SERVER_LRAND48_MAX) /
                   (lua_Number)SERVER_LRAND48_MAX;
    switch (lua_gettop(L)) {  /* check number of arguments */
    case 0: {                 /* no arguments */
        lua_pushnumber(L, r); /* Number between 0 and 1 */
        break;
    }
    case 1: { /* only upper limit */
        int u = luaL_checkint(L, 1);
        luaL_argcheck(L, 1 <= u, 1, "interval is empty");
        lua_pushnumber(L, floor(r * u) + 1); /* int between 1 and `u' */
        break;
    }
    case 2: { /* lower and upper limits */
        int l = luaL_checkint(L, 1);
        int u = luaL_checkint(L, 2);
        luaL_argcheck(L, l <= u, 2, "interval is empty");
        lua_pushnumber(L, floor(r * (u - l + 1)) + l); /* int between `l' and `u' */
        break;
    }
    default: return luaL_error(L, "wrong number of arguments");
    }
    return 1;
}

static int server_math_randomseed(lua_State *L) {
    serverSrand48(luaL_checkint(L, 1));
    return 0;
}

/* kv is the RESP2 reply *2 [keys array, args array]. Sets globals KEYS and ARGV; on malformed input pushes an
 * error message and returns 1 (status convention of lua_pcall). */
static int setKeysArgv(lua_State *L, const char *kv, size_t kv_len) {
    int error = 0;
    if (!kv_len || kv[0] != '*' || pushReply(L, kv, kv + kv_len, &error, 0) != kv + kv_len || error ||
        !lua_istable(L, -1) || lua_objlen(L, -1) != 2) {
        lua_settop(L, 0);
        lua_pushstring(L, "ERR malformed KEYS/ARGV buffer");
        return 1;
    }
    /* open global protection to set KEYS and ARGV */
    lua_enablereadonlytable(L, LUA_GLOBALSINDEX, 0);
    lua_rawgeti(L, -1, 1);
    lua_setglobal(L, "KEYS");
    lua_rawgeti(L, -1, 2);
    lua_setglobal(L, "ARGV");
    lua_enablereadonlytable(L, LUA_GLOBALSINDEX, 1);
    lua_pop(L, 1);
    return 0;
}

/* Runs a script in a fresh state and leaves Valkey's RESP2 reply in result_ptr/result_len until the next call.
 * Returns 0 if the script ran, 1 if it failed (the reply is an error), -1 if converting the reply raised. */
__attribute__((export_name("eval"))) int eval(const char *src, size_t len, const char *kv, size_t kv_len) {
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
    setField(L, "sha1hex", luaRedisSha1hexCommand);
    setField(L, "error_reply", luaRedisErrorReplyCommand);
    setField(L, "status_reply", luaRedisStatusReplyCommand);
    setField(L, "replicate_commands", luaServerReplicateCommandsCommand);
    setField(L, "breakpoint", luaServerBreakpointCommand);
    setField(L, "debug", luaServerDebugCommand);
    setField(L, "setresp", luaSetResp);
    setField(L, "set_repl", luaRedisSetReplCommand);
    setField(L, "log", luaLogCommand);
    setField(L, "acl_check_cmd", luaRedisAclCheckCmdPermissionsCommand);
    setNumber(L, "REPL_NONE", 0);
    setNumber(L, "REPL_AOF", 1);
    setNumber(L, "REPL_SLAVE", 2);
    setNumber(L, "REPL_REPLICA", 2);
    setNumber(L, "REPL_ALL", 3);
    setNumber(L, "LOG_DEBUG", 0);
    setNumber(L, "LOG_VERBOSE", 1);
    setNumber(L, "LOG_NOTICE", 2);
    setNumber(L, "LOG_WARNING", 3);
    setNumber(L, "REDIS_VERSION_NUM", parse_semver(redis_version));
    setString(L, "REDIS_VERSION", redis_version);
    setNumber(L, "VALKEY_VERSION_NUM", parse_semver(valkey_version));
    setString(L, "VALKEY_VERSION", valkey_version);
    setString(L, "SERVER_NAME", server_name);
    lua_pushvalue(L, -1);
    lua_setglobal(L, "server");
    lua_setglobal(L, "redis");
    lua_pushcfunction(L, luaRedisPcall);
    lua_setglobal(L, "pcall");

    /* Replace math.random and math.randomseed with custom implementations. */
    lua_getglobal(L, "math");
    lua_pushstring(L, "random");
    lua_pushcfunction(L, server_math_random);
    lua_settable(L, -3);
    lua_pushstring(L, "randomseed");
    lua_pushcfunction(L, server_math_randomseed);
    lua_settable(L, -3);
    /* overwrite value of global variable 'math' with this modified math table present on top of stack. */
    lua_setglobal(L, "math");
    luaInstallErrorHandler(L);
    luaLockGlobals(L);

    int kind = EVAL_SETUP;
    lua_pushcfunction(L, resetHostState);
    if (!lua_pcall(L, 0, 0, 0) && !setKeysArgv(L, kv, kv_len)) {
        kind = EVAL_COMPILE;
        if (!luaL_loadbuffer(L, src, len, "@user_script")) {
            luaPushErrorHandler(L);
            lua_insert(L, -2);
            kind = lua_pcall(L, 0, 1, -2) ? EVAL_RUN : EVAL_OK;
            lua_remove(L, -2); /* the error handler */
        }
    }

    respBuf out = {0};
    int status = luaEvalReply(L, kind, &out) ? -1 : kind != EVAL_OK;
    free(result);
    result = out.buf;
    result_size = out.len;

    lua_close(L);
    return status;
}
