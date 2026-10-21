VALKEY_SHA := 7db2df1f79f45e2fb0f0d4e16514f2010b8c15b9
WASI_SDK_VERSION := 34
WASI_SDK_ARCH := $(shell uname -m | sed 's/aarch64/arm64/')-$(shell uname -s | tr A-Z a-z | sed 's/darwin/macos/')

DEPS := .deps
WASI_SDK := $(DEPS)/wasi-sdk
LUA_SRC := $(DEPS)/valkey/deps/lua/src
PATCHES := $(sort $(wildcard patches/*.patch))
# Marks a tree extracted at VALKEY_SHA with the current patches applied; a new SHA or a changed patch re-extracts.
STAMP := $(DEPS)/valkey/.patched-$(VALKEY_SHA)
CC := $(WASI_SDK)/bin/clang

# Same set Valkey loads in luaLoadLibraries; liolib/loadlib/linit are deliberately left out.
LUA_FILES := lapi lauxlib lbaselib lcode ldblib ldebug ldo ldump lfunc lgc llex lmathlib lmem \
	lobject lopcodes loslib lparser lstate lstring lstrlib ltable ltablib ltm lundump lvm lzio \
	lua_cjson lua_cmsgpack lua_struct lua_bit strbuf fpconv
SRCS := $(addprefix $(LUA_SRC)/,$(addsuffix .c,$(LUA_FILES))) valkey_lua.c valkey_globals.c $(DEPS)/valkey/deps/fpconv/fpconv_dtoa.c $(DEPS)/valkey/src/sha1.c $(DEPS)/valkey/src/rand.c

# Valkey's LUA_CFLAGS, plus setjmp/longjmp lowered to standard (non-legacy) Wasm EH for Chicory.
# LUA_USELONGLONG and STRUCT_INT, with patches/, make every value derived from a Lua integer 64-bit, as on Valkey's
# 64-bit hosts (see wasm-difference.md).
# -Wno-implicit-function-declaration: wasi-libc doesn't declare mkstemp; valkey_lua.c stubs it.
CFLAGS := -O2 -DLUA_ANSI -DENABLE_CJSON_GLOBAL -DLUA_USE_MKSTEMP -DLUA_USELONGLONG -DSTRUCT_INT='long long' -I$(LUA_SRC) -I$(DEPS)/valkey/deps/fpconv -I$(DEPS)/valkey/src -Icompat \
	-mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false \
	-D_WASI_EMULATED_PROCESS_CLOCKS -Wno-implicit-function-declaration
# 8 MiB C stack (wasm-ld defaults to 64 KiB), so Valkey's nesting limits fire before it runs out; stack-first layout
# (the default) keeps an overflow a trap. See wasm-difference.md section 7.
LDFLAGS := -mexec-model=reactor -lsetjmp -lwasi-emulated-process-clocks -Wl,-z,stack-size=8388608

lua.wasm: $(SRCS) $(STAMP) Makefile
	$(CC) $(CFLAGS) $(SRCS) $(LDFLAGS) -o $@

$(SRCS): | $(STAMP)

$(STAMP): $(PATCHES) | $(WASI_SDK)
	rm -rf $(DEPS)/valkey
	mkdir -p $(DEPS)/valkey
	curl -sSfL https://github.com/valkey-io/valkey/archive/$(VALKEY_SHA).tar.gz \
		| tar xz -C $(DEPS)/valkey --strip-components=1 valkey-$(VALKEY_SHA)/deps/lua/src valkey-$(VALKEY_SHA)/deps/fpconv valkey-$(VALKEY_SHA)/src/solarisfixes.h valkey-$(VALKEY_SHA)/src/sha1.c valkey-$(VALKEY_SHA)/src/sha1.h valkey-$(VALKEY_SHA)/src/rand.c valkey-$(VALKEY_SHA)/src/rand.h
	for p in $(PATCHES); do patch -p1 -F 0 --forward --batch -d $(DEPS)/valkey < $$p || exit 1; done
	touch $@

$(WASI_SDK):
	mkdir -p $@
	curl -sSfL https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-$(WASI_SDK_VERSION)/wasi-sdk-$(WASI_SDK_VERSION).0-$(WASI_SDK_ARCH).tar.gz \
		| tar xz -C $@ --strip-components=1

clean:
	rm -f lua.wasm

.PHONY: clean
