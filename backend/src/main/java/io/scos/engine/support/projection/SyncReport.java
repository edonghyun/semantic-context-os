package io.scos.engine.support.projection;

import java.util.List;

/** sync 하나의 결과. deleted = prune·축소로 지운 유닛 수(문서 수 아님). */
public record SyncReport(
    int documents, int units, int deleted, List<DeadLetter> errors, EmbeddingCounts embeddings) {}
