package aismo.sms.data.ai

/** The bundled model that search by meaning runs on: BGE-small-en-v1.5, int8, see THIRD_PARTY_NOTICES.md. */
object EmbeddingModel {
    /** Bump when the model file, the tokenization or the pooling changes; stored vectors from other versions are rebuilt. */
    const val VERSION = 1
    const val DIMENSION = 384
    const val MAX_TOKENS = 128

    const val MODEL_ASSET = "models/bge-small-en-v1.5-int8.onnx"
    const val VOCABULARY_ASSET = "models/vocab.txt"
    const val MODEL_BYTES = 34_014_426L
}
