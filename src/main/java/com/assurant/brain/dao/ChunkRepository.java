package com.assurant.brain.dao;

import com.assurant.brain.domain.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM chunks WHERE metadata->>'projectId' = :projectId", nativeQuery = true)
    void deleteByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT count(*) FROM chunks WHERE metadata->>'projectId' = :projectId", nativeQuery = true)
    long countByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT metadata->>'filePath' as path, metadata->>'contentHash' as hash " +
            "FROM chunks WHERE metadata->>'projectId' = :projectId " +
            "GROUP BY metadata->>'filePath', metadata->>'contentHash'", nativeQuery = true)
    List<Object[]> findContentHashesByProjectId(@Param("projectId") String projectId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM chunks WHERE metadata->>'projectId' = :projectId " +
            "AND metadata->>'filePath' IN (:filePaths)", nativeQuery = true)
    void deleteByProjectIdAndFilePaths(@Param("projectId") String projectId,
                                       @Param("filePaths") List<String> filePaths);

    @Query(value = "SELECT DISTINCT metadata->>'chunkName' FROM chunks WHERE metadata->>'projectId' = :projectId", nativeQuery = true)
    Set<String> findChunkNamesByProjectId(@Param("projectId") String projectId);

    @Query(value = """
            SELECT content, metadata, similarity(content, cast(:query as text)) AS score
            FROM chunks
            WHERE metadata->>'projectId' = :projectId
              AND content % cast(:query as text)
            ORDER BY score DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<Object[]> lexicalSearch(@Param("projectId") String projectId,
                                  @Param("query") String query,
                                  @Param("topK") int topK);
}
