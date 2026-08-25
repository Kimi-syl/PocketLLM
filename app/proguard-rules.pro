-keepclasseswithmembernames class com.pocketllm.llm.LlamaBridge {
    native <methods>;
}
-keepclassmembers class * implements com.pocketllm.llm.TokenSink {
    public boolean onToken(byte[]);
}
