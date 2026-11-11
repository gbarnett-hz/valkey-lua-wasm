/* EVAL's reply, shared by lua.wasm and the JNI natives; see valkey_reply.c. */
#ifndef VALKEY_REPLY_H
#define VALKEY_REPLY_H

#include <stddef.h>

#include "lua.h"

/* malloc/realloc-grown; the caller frees buf. L is set by luaEvalReply: a failed allocation raises on it. */
typedef struct {
    char *buf;
    size_t len, cap;
    lua_State *L;
} respBuf;

/* What the value on top of the stack is when luaEvalReply runs. */
enum { EVAL_OK = 0, EVAL_SETUP = 1, EVAL_COMPILE = 2, EVAL_RUN = 3 };

void luaInstallErrorHandler(lua_State *L);
void luaPushErrorHandler(lua_State *L);
void luaSetScriptResp(lua_State *L, int resp);
int luaEvalReply(lua_State *L, int kind, respBuf *out);

/* Compiles src as EVAL does, in a scratch state: returns 0 with its bytecode in out, 1 with EVAL's compile error
 * reply in out, or -1 if that ran out of memory. The caller frees out->buf. */
int luaCompileScript(const char *src, size_t len, respBuf *out);
/* Pushes the function undumped from luaCompileScript's bytecode and returns 0, or pushes the error and returns its
 * status (lua/valkey_undump.c). */
int luaLoadCompiled(lua_State *L, const char *bc, size_t len);

/* Scripts a state keeps compiled for luaLoadCached; least recently run is evicted beyond this. */
#define LUA_SCRIPT_CACHE_LIMIT 500

/* Pushes the function of a script (source, or luaCompileScript's bytecode if compiled), compiling it only the first
 * time this state sees it, as Valkey keeps a registry ref to it. Returns lua_load's status; on failure pushes its message and
 * caches nothing. */
int luaLoadCached(lua_State *L, const char *src, size_t len, int compiled);

/* Scripts the state holds compiled. */
int luaCachedScripts(lua_State *L);

/* Per-script state a long-lived state must reset before each script: an empty stack and RESP 2. KEYS/ARGV and the
 * host's RESP/replication state are set by the eval itself. */
void luaBeforeScript(lua_State *L);

/* After each script: empty stack, and a GC step every 50 scripts, as Valkey's luaCallFunction. */
void luaAfterScript(lua_State *L);

/* Writes "<prefix><v>\r\n" (no NUL) and returns its length; buf needs 24 bytes. snprintf's printf_core was most of a
 * server.call's cost in lua.wasm. */
int respHeader(char *buf, char prefix, long long v);

#endif
