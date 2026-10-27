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

#endif
