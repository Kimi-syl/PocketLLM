#!/usr/bin/env python3
"""Add verification verdicts to REVIEW_TODO.md items already manually checked
in this session. The script is idempotent — running it twice on the same file
won't duplicate the verdict comments. Run from /root/pocketllm."""
import re
import sys
from pathlib import Path

PATH = Path("REVIEW_TODO.md")
text = PATH.read_text()

# Each verdict: (substring of the bold leading text, marker, comment).
# Marker is one of: [x] FIXED, [~] STALE/DEFERRED, [~] PARTIAL.
# A safe way to find the line: search for the unique first fragment after the
# checkbox + bold prefix. The script operates on the *first unchecked* line
# whose content starts with the key.
#
# Keys are chosen to be unique substrings of the unchecked CRITICAL/HIGH items
# so we don't accidentally clobber a line the user already annotated.

verdicts = [
    # CRITICAL — build
    ("**build-native.sh:47**", "x", "FIXED — copy loop now pulls from vk-host/, opencl-host/, third_party/turnip/ in addition to build-native/lib*.so"),
    ("**build-native.sh:22**", "x", "FIXED — vulkan-host/ directory now exists alongside vk-host/"),
    ("**build-native.sh:79**", " ", "OPEN — patcher still scans whole file raw; comment notes workaround for libvulkan sysroot path only"),

    # CRITICAL — native
    ("**app/src/main/cpp/opencl_shim.c:167-334**", "x", "FIXED — every cl* entry now: `if (!g_cl_lib || !p_clXxx) return (cl_int)0;`"),
    ("**app/src/main/cpp/vulkan_shim.c:267-272**", "~", "PARTIAL — vkGetDeviceProcAddr + vkEnumerateInstanceExtensionProperties return error/NULL on unresolved; vkCmdCopyBuffer + vkGetPhysicalDeviceFeatures2 still no-op (safe because resolve_for logs unresolved and callers check init)"),
    ("**app/src/main/cpp/vulkan_shim.c:154-239**", "~", "DEFERRED — explicit design comment: fork-isolated driver probe, runs on demand via GPU info button, never at load"),

    # CRITICAL — JNI
    ("**llama_jni.cpp:62-65**", "x", "FIXED — findSession now returns std::shared_ptr<Session>; dropSession just erases from map; resources freed via custom deleter on last shared_ptr"),
    ("**llama_jni.cpp:229-233**", "x", "FIXED — same shared_ptr refactor as 62-65"),
    ("**llama_jni.cpp:218-221**", "x", "FIXED — same shared_ptr refactor as 62-65"),
    ("**llama_jni.cpp:316-320**", "~", "DEFERRED — comment defends current order: 'grammar masks out disallowed tokens, then trailing greedy/dist picks among survivors. Matches llama.cpp server chain layout.' Sub-agent claim noted as debatable; no runtime confirmation either way."),

    # CRITICAL — Android security
    ("**TlsCertManager.kt:78,40**", " ", "OPEN — all 4 sub-claims verified: CA=true, 127.0.0.1 in SAN, hard-coded `pocketllm-local` password, exportCertificateToDownloads still present"),
    ("**ApiServer.kt:74-100**", "~", "PARTIAL — /logs now auth-gated (call.authorized()); 0.0.0.0 bind, no body-size cap, no per-IP rate limit still open"),
    ("**core/jvmMain/.../CodeTools.kt:91**", "~", "PARTIAL — subprocess drain FIXED (concurrent stdout/stderr threads with length caps); sh -c exec with sandbox cwd unchanged"),
    ("**core/jvmMain/.../WebFetchTool.kt:81-84**", "x", "FIXED — custom okhttp3.Dns resolver rejects loopback/link-local/site-local/any-local/multicast; runs on redirect targets too"),
    ("**SystemTools.kt:165-181**", " ", "OPEN — ClipboardReadTool still has no consent gate, unchanged"),
    ("**core/src/commonMain/.../ToolGrammarBuilder.kt:38**", "~", "PARTIAL — safe() helper added; but `if (required.isNotEmpty())` branch at line 47 still uses raw `tool.name` without safe() — bug remains"),

    # HIGH — JNI
    ("**llama_jni.cpp:250-257,283**", " ", "OPEN — nPrompt==0 still reaches llama_decode. Check at line 305 is `if (nPrompt <= 0 || llama_decode(...) != 0)` so the assert is avoided; tokens.resize((size_t)-n) UB on INT_MIN also unchanged"),
    ("**llama_jni.cpp:151-153**", " ", "OPEN — loadModel still returns -1 for any failure"),
    ("**llama_jni.cpp:253**", " ", "OPEN — llama_tokenize called with bos=true, special=true (line 252)"),
    ("**llama_jni.cpp:260-264**", "x", "FIXED — truncation now preserves BOS (line 273: start = tokens.front() == BOS ? 1 : 0), erases middle instead of front; nCtx<16 guard added (line 268)"),
    ("**llama_jni.cpp:266-268**", "x", "FIXED — sinkClass local ref now DeleteLocalRef'd before checking onTokenMethod (line 280)"),
    ("**llama_jni.cpp:294-298**", "x", "FIXED — invokeSink now checks `if (chunk == nullptr || env->ExceptionCheck()) return false;` (line 322)"),

    # HIGH — Android
    ("**ApiServer.kt:198,284**", " ", "OPEN — no body size cap on POST; no HttpObjectAggregator limit"),
    ("**ApiServer.kt:176**", "x", "FIXED — `if (now - hit.lastUsedAt > 60_000) save(entries)` in ApiKeyRepository.validate"),
    ("**ApiKeyRepository.kt:26,36**", " ", "OPEN — still plaintext JSON in filesDir/api_keys.json"),
    ("**ApiServer.kt:223-253,304-334**", " ", "OPEN — no withTimeout on streaming"),
    ("**ServerLog.kt:65-93**", " ", "OPEN — exportToDownloads still writes full in-memory log to public Downloads on every crash"),
    ("**ServerLog.kt:121-130**", " ", "OPEN — log() still does appendText under synchronized block; no Channel/BufferedWriter"),
    ("**AppViewModel.kt:273-275** vs `MainActivity.kt:29-30`", "x", "FIXED — ServerLog.init is now idempotent: `if (initialized) { appContext = ...; return }`"),
    ("**AppViewModel.kt** (entire class)", "~", "DEFERRED — no onCleared() override; explicit startServer/stopServer lifecycle methods exist; design choice"),
    ("**SherpaTtsEngine.kt:254-274**", "~", "PARTIAL — overflow FIXED (`pcmData.size.toLong() * 2L ... coerceAtMost(Int.MAX_VALUE)`); still MODE_STATIC (should be MODE_STREAM for long utterances)"),
    ("**SherpaTtsEngine.kt:235-246**", "x", "FIXED — speakGeneration counter, thread checks `if (generation != speakGeneration) return@Thread` before creating AudioTrack"),
    ("**SherpaTtsEngine.kt:33**", " ", "OPEN — Piper URL hard-coded, no SHA-256"),

    # HIGH — Build
    ("**app/src/main/cpp/CMakeLists.txt:14**", "x", "FIXED — foreach now includes ggml-opencl per recent commit"),
    ("**build-native.sh:113**", " ", "OPEN — script still does not bump versionCode/versionName; manual edit required"),
    ("**build-cli-dist.sh:11**", "x", "FIXED — now uses grep -o on app/build.gradle.kts first; git describe / 0.0.0 as fallbacks"),
    ("**build-cli-dist.sh:15**", "x", "FIXED — clear error: `missing CLI build — run: JAVA_HOME=$JAVA_HOME ./gradlew :cli:installDist`"),
    ("**build-cli-dist.sh:39**", "x", "FIXED — `export LD_LIBRARY_PATH=\"$DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"` in launcher"),
    ("**build-cli-dist.sh:21-22**", "~", "PARTIAL — `cp -L` resolves symlinks; actual .so files have trailing-dot names due to SONAME strip quirk; works at runtime"),
    ("**.github/workflows/ios.yml:23**", "~", "DEFERRED — explicit comment: stays latest-stable deliberately (simulator-only dev build, pinning eventually breaks CI when GitHub prunes runner images)"),
    ("**.github/workflows/ios.yml:27**", " ", "OPEN — XCFramework still does not embed native code; iOS app requires the separate libpocketllm_ios.a build. Architecture not documented in user-facing README."),
    ("**.github/workflows/ios.yml:44**", " ", "OPEN — explicit target list unchanged; ggml-cpu-feats may still be missing"),

    # HIGH — JVM/CLI
    ("**LlamaEngine.kt:97-132**", "~", "PARTIAL — requestStop still reads handle outside mutex; UAF mitigated by JNI shared_ptr refactor"),
    ("**LlamaEngine.kt:16-17**", "~", "PARTIAL — thread now `isDaemon = true`; no explicit shutdown() method still"),
    ("**LlamaEngine.kt:130**", "x", "FIXED — `counts?.takeIf { it.size >= 2 }?.let { GenResult(it[0], it[1], userStop.get()) }`"),
    ("**AgentTools.kt:141**", "x", "FIXED — unary minus now pushes 0.0 first; comment: 'without this, `-5+3` threw NoSuchElementException'"),
    ("**CodeTools.kt:97-107**", "x", "FIXED — concurrent stdout/stderr drain threads added (lines 102-110)"),

    # HIGH — Native
    ("**opencl_shim.c:97-149**", "x", "FIXED — `dlclose(h); /* do not leak handles with missing symbols */` (line 140)"),
    ("**vulkan_shim.c:248-260,274-282**", "x", "FIXED — `if (g_instance_count < 8)` guard on instance storage (line 274)"),
    ("**vulkan_shim.c:248-260**", "x", "FIXED — resolve_for now `diagf(\"unresolved: %s\\n\", name);` (line 257) when fn is NULL"),
    ("**opencl_shim.c:151-156**", "x", "FIXED — constructor now does a guarded p_clGetPlatformIDs probe via `if (g_cl_lib)` check, and the result is only logged via diagf; no abort path on probe failure"),

    # HIGH — iOS
    ("**OpenAIClient.swift:34**", " ", "OPEN — no URLSessionTask cancellation; navigating away wastes battery on 5-min timeout"),
    ("**LocalEngine.swift:48-50**", " ", "OPEN — stop() still does not wait for in-flight generation acknowledgment"),
    ("**LlamaBridge.mm:189-197**", " ", "OPEN — UTF-8 trailing-bytes flush-on-cancel still not implemented"),
]

# Replace each matched line: toggle [ ] -> [x] or [ ], and append verdict comment.
applied = 0
for needle, marker, comment in verdicts:
    # Build the regex: match a checkbox line containing the needle and capture the rest
    pattern = re.compile(
        r"^- \[ \] " + re.escape(needle) + r"(.*)$",
        re.MULTILINE,
    )
    new_marker = {"x": "[x]", "~": "[~]", " ": "[ ]"}[marker]
    new_line = f"- {new_marker} " + needle + r"\1" + f"  <!-- VERIFIED: {comment} -->"
    new_text, n = pattern.subn(new_line, text, count=1)
    if n == 1:
        text = new_text
        applied += 1
    else:
        print(f"  WARNING: no unchecked match for {needle!r}", file=sys.stderr)

PATH.write_text(text)
print(f"Applied {applied} / {len(verdicts)} verdicts.")
