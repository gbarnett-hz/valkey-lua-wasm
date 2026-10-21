/* EVAL's reply, shared by lua.wasm and the JNI natives; see valkey_reply.c. */
#ifndef VALKEY_REPLY_H
#define VALKEY_REPLY_H

#include <stddef.h>

#include "lua.h"

/* malloc/realloc-grown; the caller frees buf. */
typedef struct {
    char *buf;
    size_t len, cap;
} respBuf;

/* What the value on top of the stack is when luaEvalReply runs. */
enum { EVAL_OK = 0, EVAL_SETUP = 1, EVAL_COMPILE = 2, EVAL_RUN = 3 };

void luaInstallErrorHandler(lua_State *L);
void luaPushErrorHandler(lua_State *L);
void luaSetScriptResp(lua_State *L, int resp);
int luaEvalReply(lua_State *L, int kind, respBuf *out);

#endif
