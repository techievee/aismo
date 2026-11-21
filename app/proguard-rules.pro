# R8 rules for aismo

# ONNX Runtime resolves its Java classes and methods by name from native code.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# The llama.cpp bridge: its native methods are looked up by name from the C++ library.
-keep class aismo.sms.data.llm.LlamaNative { *; }

# LiteRT-LM calls into its own classes by name from native code and reads its model configuration with reflection.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
