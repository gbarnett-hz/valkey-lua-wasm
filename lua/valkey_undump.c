/* Loads bytecode made by luaCompileScript. Valkey's Lua dropped lua_load's binary-chunk path (f_parser only parses
 * source, so scripts can't load bytecode), hence this copy of f_parser with luaU_undump in place of luaY_parser.
 * Undump trusts its input: never give it bytes a script or client supplied. Shared by lua.wasm and, included from
 * native/all.cc (where Lua's internals are static), the JNI natives; so it must also compile as C++. */
#include "ldo.h"
#include "lfunc.h"
#include "lgc.h"
#include "lstate.h"
#include "lundump.h"
#include "lzio.h"
#include "valkey_reply.h"

typedef struct {
    const char *s;
    size_t len;
} bytesReader;

static const char *readBytes(lua_State *L, void *ud, size_t *size) {
    bytesReader *r = (bytesReader *)ud;
    (void)L;
    *size = r->len;
    r->len = 0;
    return *size ? r->s : NULL;
}

typedef struct {
    ZIO z;
    Mbuffer buff;
} undumpParser;

static void f_undump(lua_State *L, void *ud) {
    undumpParser *p = (undumpParser *)ud;
    int i;
    Proto *tf;
    Closure *cl;
    luaC_checkGC(L);
    tf = luaU_undump(L, &p->z, &p->buff, "=user_script");
    cl = luaF_newLclosure(L, tf->nups, hvalue(gt(L)));
    cl->l.p = tf;
    for (i = 0; i < tf->nups; i++) /* initialize eventual upvalues */
        cl->l.upvals[i] = luaF_newupval(L);
    setclvalue(L, L->top, cl);
    incr_top(L);
}

int luaLoadCompiled(lua_State *L, const char *bc, size_t len) {
    bytesReader r = {bc, len};
    undumpParser p;
    int status;
    luaZ_init(L, &p.z, readBytes, &r);
    luaZ_initbuffer(L, &p.buff);
    status = luaD_pcall(L, f_undump, &p, savestack(L, L->top), L->errfunc);
    luaZ_freebuffer(L, &p.buff);
    return status;
}
