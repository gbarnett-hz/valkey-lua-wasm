# Wasm 64-bit Fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `lua.wasm` give 64-bit Valkey's results by patching Valkey's bundled Lua and enlarging the C stack. Pin every case with shared tests, and document the deviations that remain.

**Architecture:**
- `lua/Makefile` applies `.patch` files from `lua/patches/` to the extracted Valkey sources and links an 8 MiB C stack.
- The shared JUnit suite (`ValkeyLuaTest`) holds Valkey's expected values. Both `WasmValkeyLuaTest` and `JniValkeyLuaTest` run it.
- The JNI backend is the 64-bit reference and is not changed.

**Tech Stack:** wasi-sdk 34 (clang), GNU make, `patch`, Chicory 1.7.5, luajava 4.0.2, JUnit 6, Maven.

**Spec:** `docs/superpowers/specs/2026-10-21-wasm-64bit-fidelity-design.md`

## Global Constraints

- Valkey stays pinned at `VALKEY_SHA := 7db2df1f79f45e2fb0f0d4e16514f2010b8c15b9` in both `lua/Makefile` and `native/Makefile`. `native/` gets no patches.
- Toolchain stays wasi-sdk 34 and Chicory 1.7.5.
- No code changes to `JniValkeyLua`, `WasmValkeyLua`, `ValkeyLua` or `Handler`.
- Expected values in shared tests are the JNI backend's (= 64-bit Valkey's). Every value below was probed on JNI on 2026-10-21.
- A widened type keeps the signedness of Valkey's original type (`ptrdiff_t` → signed 64-bit, `size_t`/`unsigned long` → unsigned 64-bit).
- Full verification is `make -C lua && make -C native && (cd java && mvn test)`. On macOS 27, `make -C native` needs `CXXFLAGS="-isystem $(xcrun --show-sdk-path)/usr/include/c++/v1"`.

## Review Focus

1. **A developer whose `lua/.deps/valkey` was extracted before this change.** Running `make -C lua` must re-extract and patch. It must not build an unpatched module from the stale tree. Pinned in Task 1, Step 6.
2. **A Valkey bump that breaks a patch.** The build must fail at the patch step with `patch`'s error, not produce a module. Pinned in Task 1, Step 7.
3. **Negative and zero `struct.unpack` positions.** These must keep Valkey's errors after `pos` becomes 64-bit unsigned. Pinned by `structUnpackPositionIs64Bit` (Task 1).
4. **An embedder running evals on a thread with a 512 KiB stack** (the documented minimum). The nesting cases must still raise Valkey's errors. Pinned by `nestingErrorsAtMinimumThreadStack` (Task 2).
5. **Reusing an instance after a nesting error or an out-of-memory error.** The next `eval` must work. Pinned inside the Task 2 and Task 3 tests.

---

### Task 1: 64-bit integers in `lua.wasm`

**Files:**
- Create:
  - `lua/patches/luaconf.h.patch`
  - `lua/patches/lua_struct.c.patch`
  - `lua/patches/lbaselib.c.patch`
  - `lua/patches/lstrlib.c.patch`
  - `lua/patches/lua_cmsgpack.c.patch`
- Modify: `lua/Makefile`
- Test:
  - `java/src/test/java/valkeylua/ValkeyLuaTest.java` (the 64-bit block starts at the comment at `:443`)
  - `java/src/test/java/valkeylua/WasmValkeyLuaTest.java`
- Docs:
  - `wasm-difference.md` sections 1–4 and 6, and its "Tests" section
  - `README.md` "Where the backends differ", backend table, "Wasm backend"

**Interfaces:**
- Produces: `lua/Makefile` that extracts, patches and builds `lua/lua.wasm`, and a `lua/patches/` directory that later tasks don't touch.
- Produces: the existing helpers `ok(String)` and `lua` in `ValkeyLuaTest`, reused by Task 2.

- [ ] **Step 1: Delete the three overrides in `WasmValkeyLuaTest`.** Remove `stringFormatIntegersAre64Bit`, `structLongAndSizeTAre64Bit` and `intArgumentsKeepLow32BitsOf64BitInteger`, plus their now-unused imports and the comment above them. The class keeps `WASM` and `create`.

- [ ] **Step 2: Add the failing shared tests to `ValkeyLuaTest`**, after `stringIndicesBeyond32BitsAreNotTruncated`, under the existing "Valkey runs on 64-bit hosts" comment. Update that comment: lua.wasm is patched to 64-bit integers, and these tests pin Valkey's values on both backends.

```java
@Test
void largeIntegerArgumentsKeepLow32Bits() {
    assertEquals("true", ok("math.randomseed(2^32 + 5) local a = math.random(1000) math.randomseed(5) return tostring(a == math.random(1000))"));
    assertEquals("xxx", ok("return string.rep('x', 2^32 + 3)"));
    assertEquals("A", ok("return string.char(65 + 2^32)"));
    assertEquals("x", ok("local t = {} table.insert(t, 2^32 + 1, 'x') return tostring(t[1])"));
    assertEquals("b", ok("return select(2^32 + 2, 'a', 'b')"));
    assertEquals("user_script:1: Start offset 1 greater than input length 2.",
        lua.eval("return cmsgpack.unpack_one(cmsgpack.pack(1, 2), 2^32 + 1)").value());
}

@Test
void tonumberWithBaseIs64Bit() {
    assertEquals("1099511627775 78364164095", ok("return tonumber('ffffffffff', 16) .. ' ' .. tonumber('zzzzzzz', 36)"));
    // Already matched before the patches (strtod parses hex); guards the coercion path.
    assertEquals("549755813887 1099511627776", ok("return 0x7fffffffff .. ' ' .. ('0x10000000000' + 0)"));
}

@Test
void structUnpackPositionIs64Bit() {
    // Matched before the patches only because wasm32 saturated; a 32-bit size_t pos would truncate 2^32 + 1 to 1.
    assertEquals("user_script:1: bad argument #2 to 'unpack' (data string too short)",
        lua.eval("return struct.unpack('b', 'abc', 2^32 + 1)").value());
    assertEquals("user_script:1: bad argument #2 to 'unpack' (data string too short)",
        lua.eval("return struct.unpack('b', 'abc', -1)").value());
    assertEquals("user_script:1: bad argument #3 to 'unpack' (offset must be 1 or greater)",
        lua.eval("return struct.unpack('b', 'abc', 0)").value());
    assertEquals("98", ok("return struct.unpack('b', 'abc', 2)"));
}

@Test
void cmsgpackUint64AndInt64() {
    assertEquals("-1", ok("return tostring(cmsgpack.unpack('\\207' .. string.rep('\\255', 8)))"));
    // Encoding already matched; guards the path BITS_32=0 switches to lua_tointeger.
    assertEquals("cf0000010000000000 d3ffffff0000000000 -1099511627776", ok(
        "local function hex(b) local h = '' for i=1,#b do h = h .. string.format('%02x', b:byte(i)) end return h end "
        + "return hex(cmsgpack.pack(2^40)) .. ' ' .. hex(cmsgpack.pack(-2^40)) .. ' ' .. tostring(cmsgpack.unpack(cmsgpack.pack(-2^40)))"));
}
```

- [ ] **Step 3: Run the tests to see them fail on Wasm.**

Run: `cd java && mvn test`
Expected:
- `JniValkeyLuaTest` passes.
- `WasmValkeyLuaTest` fails in `stringFormatIntegersAre64Bit`, `structLongAndSizeTAre64Bit`, `intArgumentsKeepLow32BitsOf64BitInteger`, `largeIntegerArgumentsKeepLow32Bits`, `tonumberWithBaseIs64Bit` and `cmsgpackUint64AndInt64`. `structUnpackPositionIs64Bit` and `stringIndicesBeyond32BitsAreNotTruncated` pass on Wasm today.
- `largeIntegerArgumentsKeepLow32Bits` is slow on Wasm. Unpatched, `string.rep` asks for about 2 GiB and fails with `not enough memory`.

- [ ] **Step 4: Write the five patches and the Makefile changes.**
  - Each patch is a unified diff with paths relative to the Valkey root (`a/deps/lua/src/<file>`, `b/deps/lua/src/<file>`), applied with `patch -p1`. Make each one by copying the pristine file from a fresh extract, editing it, and running `diff -u`. The changes, from the spec's table:
    - `luaconf.h:143`: `#define LUA_INTEGER long long`.
    - `lua_struct.c:115-116`: `'l'`, `'L'` and `'T'` return `sizeof(long long)`. At `:298`, `b_unpack`'s `pos` becomes `unsigned long long`. Keep every use of `pos` correct, including the `gettoalign` call and the `lua_pushinteger(L, pos + 1)` at `:361`.
    - `lbaselib.c:65-67`: `unsigned long long n`, `strtoull`.
    - `lstrlib.c`: `posrelat` (`:36-40`) and the positions in `str_sub` (`:46-49`), `str_byte` (`:109-110`) and `str_find_aux` (`:499-501`) become a signed 64-bit type. Leave the `int n` in `str_byte` as `int`; Valkey has it so.
    - `lua_cmsgpack.c:27-31`: `BITS_32` defined as 0 unconditionally.
  - `lua/Makefile`:
    - Add `-DLUA_USELONGLONG -DSTRUCT_INT='long long'` to `CFLAGS`.
    - After the tar extract, apply `patches/*.patch` in name order with `patch -p1 --forward --batch -d $(DEPS)/valkey`. A failing patch fails the build.
    - Arrange it so a change to any patch, or to `VALKEY_SHA`, deletes `$(DEPS)/valkey` and re-extracts before patching (e.g. a stamp file named after the SHA that depends on `patches/*.patch`). The mechanism is the builder's choice.
    - `make clean` keeps removing only `lua.wasm`.

- [ ] **Step 5: Rebuild over the existing unpatched extract, and run the suite** (also covers Review Focus 1).

Do not delete `lua/.deps/valkey` first. It is the unpatched tree every existing checkout has, and the new Makefile must replace it on its own.

Run: `make -C lua && grep -n "define LUA_INTEGER" lua/.deps/valkey/deps/lua/src/luaconf.h && cd java && mvn test`
Expected:
- the grep shows `long long`;
- `Tests run: 108, Failures: 0, Errors: 0` (54 per backend).

- [ ] **Step 6: Check that a second `make` is a no-op.**

Run: `make -C lua`
Expected: no download and no re-extract ("Nothing to be done" or equivalent), proving the stamp doesn't force a fresh extract every build.

- [ ] **Step 7: Check that a broken patch fails the build** (Review Focus 2).

Run: corrupt one context line in `lua/patches/lbaselib.c.patch`, then `make -C lua`.
Expected: non-zero exit, with `patch`'s "Hunk #1 FAILED" message and no new `lua.wasm`. Restore the patch, run `make -C lua`, and confirm it succeeds.

- [ ] **Step 8: Update the docs.**
  - `wasm-difference.md`:
    - Each row of sections 1–4 notes that it is fixed and names its test.
    - Section 6 becomes the patch list as built: file, change, why.
    - Delete the "What that build breaks" and "Not yet tried" subsections, which this task resolves.
    - Keep the "Only wasm64…" subsection.
    - "Tests" lists the new test names.
  - `README.md`:
    - Backend table "Integer width" for Wasm: "64-bit, as Valkey (patched)".
    - "Wasm backend" lists the five patches and the two `CFLAGS` switches.
    - "Where the backends differ" drops the six 32-bit rows and the "Rebuilding `lua.wasm`…" passage, keeps the escape row, and points to `wasm-difference.md`.
    - Add a build note: changing a patch or `VALKEY_SHA` re-extracts automatically. This replaces the `rm -rf lua/.deps/valkey` advice for `lua/`; keep that advice for `native/`.

- [ ] **Step 9: Commit.**

```bash
git add lua/patches lua/Makefile java/src/test/java/valkeylua wasm-difference.md README.md
git commit -m "Patch lua.wasm to 64-bit lua_Integer, long and size_t-derived positions, as Valkey"
```

---

### Task 2: 8 MiB C stack, so deep nesting raises Valkey's errors

**Files:**
- Modify: `lua/Makefile` (`LDFLAGS`)
- Test: `java/src/test/java/valkeylua/ValkeyLuaTest.java`
- Docs:
  - `wasm-difference.md` section 7
  - `README.md` "Wasm backend"

**Interfaces:**
- Consumes: Task 1's `lua/Makefile` and the `ok`, `lua` and `create(Handler)` helpers in `ValkeyLuaTest`.

- [ ] **Step 1: Add the failing shared tests to `ValkeyLuaTest`**, in a new block after the 64-bit tests. Comment: Valkey's own nesting limits (cjson 1000, `LUAI_MAXCCALLS` 200) must fire before any stack runs out.

```java
static final String[][] NESTING = {
    {"local t = {} local c = t for i=1,1000 do c[1] = {} c = c[1] end return #cjson.encode(t)",
        "user_script:1: Cannot serialise, excessive nesting (1001)"},
    {"return #cjson.decode(string.rep('[',2000)..string.rep(']',2000))",
        "user_script:1: Found too many nested data structures (1001) at character 1001"},
    {"local function f(n) if n==0 then return 'y' end return (string.gsub('x','x',function() return f(n-1) end)) end return f(250)",
        "C stack overflow"},
};

@Test
void nestingRaisesValkeysErrors() {
    for (String[] c : NESTING) {
        var r = lua.eval(c[0]);
        assertEquals(new ValkeyLua.Result(false, c[1]), r, c[0]);
    }
    assertEquals("y", ok("local function f(n) if n==0 then return 'y' end return (string.gsub('x','x',function() return f(n-1) end)) end return f(195)"));
    assertEquals("1", ok("return 1")); // same instance still usable
}

@Test
void nestingErrorsAtMinimumThreadStack() throws Exception {
    // The documented minimum JVM thread stack for evals is 512 KiB.
    var results = new ArrayList<ValkeyLua.Result>();
    var t = new Thread(null, () -> {
        var l = create(args -> "+OK\r\n".getBytes(UTF_8));
        for (String[] c : NESTING) results.add(l.eval(c[0]));
        results.add(l.eval("return 1"));
    }, "eval-512k", 512 * 1024);
    t.start();
    t.join();
    for (int i = 0; i < NESTING.length; i++) assertEquals(new ValkeyLua.Result(false, NESTING[i][1]), results.get(i));
    assertEquals(new ValkeyLua.Result(true, "1"), results.get(NESTING.length));
}
```

An exception thrown inside the thread must fail the test, not vanish. Capture it (e.g. into an `AtomicReference<Throwable>`) and rethrow it after `join`.

- [ ] **Step 2: Run the tests to see them fail on Wasm.**

Run: `cd java && mvn test`
Expected: JNI passes. In `WasmValkeyLuaTest`, `nestingRaisesValkeysErrors` errors with a `WasmRuntimeException` ("out of bounds memory access") or an `IllegalStateException` from the poisoned instance. `nestingErrorsAtMinimumThreadStack` fails.

- [ ] **Step 3: Add `-Wl,-z,stack-size=8388608` to `LDFLAGS` in `lua/Makefile`.** Keep wasm-ld's stack-first layout, the default for this target; don't add `--no-stack-first`.

- [ ] **Step 4: Rebuild and run the suite.**

Run: `make -C lua && cd java && mvn test`
Expected: `Tests run: 112, Failures: 0, Errors: 0`. If a nesting case still traps, raise the stack size, record the size needed in the Makefile comment and in `wasm-difference.md`, and rerun.

- [ ] **Step 5: Update the docs.**
  - `wasm-difference.md` section 7: mark it fixed, and name the flag, the tests and the scratch-build JVM-stack results from the spec. Fixed at JVM stacks of 512 KiB, 1 MiB and 2 MiB, on platform and virtual threads; at 256 KiB the JVM stack runs out first and the instance is poisoned.
  - `README.md` "Wasm backend":
    - The C stack is 8 MiB.
    - Evals must run on a thread with at least 512 KiB of stack. Every JVM default meets this: 1 MiB on Linux x86_64, 2 MiB on macOS arm64. Below that, deep nesting can overflow the JVM stack and poison the instance.

- [ ] **Step 6: Commit.**

```bash
git add lua/Makefile java/src/test/java/valkeylua/ValkeyLuaTest.java wasm-difference.md README.md
git commit -m "Link lua.wasm with an 8 MiB C stack so Valkey's nesting limits fire first"
```

---

### Task 3: Pin and document the remaining deviations

**Files:**
- Modify: `java/pom.xml` (surefire `argLine`)
- Test: `java/src/test/java/valkeylua/WasmValkeyLuaTest.java`
- Docs:
  - `wasm-difference.md` sections 5, 8, a new "What remains" section, and "Tests"
  - `README.md` "Where the backends differ"

**Interfaces:**
- Consumes: `WasmValkeyLuaTest.create(Handler, ServerInfo)` and the `lua` field inherited from `ValkeyLuaTest`.

- [ ] **Step 1: Add the Wasm-only test to `WasmValkeyLuaTest`.**

```java
@Test
void memoryCapIsAnOrdinaryLuaError() {
    // Chicory caps linear memory at 32767 pages; JNI succeeds here, so this is Wasm-only (wasm-difference.md section 8).
    assertEquals(new ValkeyLua.Result(false, "not enough memory"), lua.eval("return #string.rep('x', 2^30)"));
    assertEquals(new ValkeyLua.Result(true, "1"), lua.eval("return 1"));
}
```

- [ ] **Step 2: Run it.**

Run: `cd java && mvn test -Dtest=WasmValkeyLuaTest#memoryCapIsAnOrdinaryLuaError`
Expected: PASS. If the JVM heap is too small it fails with `OutOfMemoryError` instead; that is what Step 3 fixes. Record which you saw.

- [ ] **Step 3: Set surefire's `argLine` to `-Xmx4g`** in `java/pom.xml`, inside the existing `maven-surefire-plugin` block. Do not add `-Xss`; the shared tests run at the JVM's default thread stack.

- [ ] **Step 4: Run the full suite.**

Run: `cd java && mvn test`
Expected: `Tests run: 113, Failures: 0, Errors: 0`.

- [ ] **Step 5: Update the docs.**
  - `wasm-difference.md`:
    - Section 8: the cap is 32767 pages less the 8 MiB stack. Name the test.
    - Section 5: keep the `tostring` address-width note.
    - New section "Memory accounting and GC timing". Measured: 10000 empty tables are 647 KiB by `collectgarbage('count')` on wasm and 959 KiB on JNI. What a script observes: the `collectgarbage('count')` figures, when the incremental collector runs, and therefore when weak tables lose entries. Not fixable on wasm32.
    - New "What remains" section near the top that lists every deviation with what a script observes:
      - the memory cap;
      - memory accounting and GC timing;
      - `tostring` address width;
      - the 512 KiB minimum thread stack;
      - the escape exception type (`WasiExitException` vs `IllegalStateException`).
    - Retitle the "Tests" section's closing paragraph so it describes the current suite.
  - `README.md` "Where the backends differ": a short table of exactly those five remaining items, with a link to `wasm-difference.md`.

- [ ] **Step 6: Commit.**

```bash
git add java/pom.xml java/src/test/java/valkeylua/WasmValkeyLuaTest.java wasm-difference.md README.md
git commit -m "Pin the Wasm memory cap and document the remaining deviations from Valkey"
```
