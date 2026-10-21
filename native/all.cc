// Valkey's deps/lua/etc/all.c without lua.c (the standalone interpreter's main), included by luajava's
// luacustomamalg.h. C linkage, compiled as C++ together with the JNI glue.
extern "C" {
#define luaall_c
#define LUA_CORE
#include "luaconf.h"
// Under luaall_c, data declarations lose "extern", which C++ rejects. Infinispan ships the same fix in its luaconf.h.
#undef LUAI_DATA
#define LUAI_DATA extern

#include "lapi.c"
#include "lcode.c"
#include "ldebug.c"
#include "ldo.c"
#include "ldump.c"
#include "lfunc.c"
#include "lgc.c"
#include "llex.c"
#include "lmem.c"
#include "lobject.c"
#include "lopcodes.c"
#include "lparser.c"
#include "lstate.c"
#include "lstring.c"
#include "ltable.c"
#include "ltm.c"
#include "lundump.c"
#include "lvm.c"
#include "lzio.c"

#include "lauxlib.c"
#include "lbaselib.c"
#include "ldblib.c"
#include "liolib.c"
#include "linit.c"
#include "lmathlib.c"
#include "loadlib.c"
#include "loslib.c"
#include "lstrlib.c"
#include "ltablib.c"
}
