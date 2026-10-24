package cc.ivera.magnet.infrastructure.mapper;

import cc.ivera.magnet.domain.task.TaskEvent;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

public interface TaskEventMapper {

    void insert(TaskEvent event);

    /** 取当前最大 seq，用于分配下一个（单写者下安全） */
    Long maxSeq(@Param("taskId") UUID taskId);

    List<TaskEvent> findAfter(@Param("taskId") UUID taskId,
                              @Param("afterSeq") long afterSeq,
                              @Param("limit") int limit);
}
