package com.magnetplatform.infrastructure.mapper;

import com.magnetplatform.domain.parse.ParseJob;
import com.magnetplatform.domain.parse.ParseStatus;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ParseJobMapper {

    void insert(ParseJob job);

    ParseJob findById(@Param("id") UUID id);

    List<ParseJob> findByUser(@Param("userId") UUID userId,
                              @Param("status") ParseStatus status,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    long countByUser(@Param("userId") UUID userId, @Param("status") ParseStatus status);

    void markStatus(@Param("id") UUID id,
                    @Param("status") ParseStatus status,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("now") LocalDateTime now);

    long countByUserAndResource(@Param("userId") UUID userId, @Param("resourceId") UUID resourceId);

    int markTimeoutResolving(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);

    List<ParseJob> findByStatus(@Param("status") ParseStatus status, @Param("limit") int limit);
}
