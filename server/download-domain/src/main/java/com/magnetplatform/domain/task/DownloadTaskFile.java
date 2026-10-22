package com.magnetplatform.domain.task;

import java.util.UUID;

/** 任务内单文件的选择与验证进度（规格 §8 download_task_file） */
public final class DownloadTaskFile {

    private final UUID id;
    private final UUID taskId;
    private final int fileIndex;
    private final boolean selected;
    private final long verifiedBytes;
    private final String exportState;

    public DownloadTaskFile(UUID id, UUID taskId, int fileIndex, boolean selected,
                            long verifiedBytes, String exportState) {
        this.id = id;
        this.taskId = taskId;
        this.fileIndex = fileIndex;
        this.selected = selected;
        this.verifiedBytes = verifiedBytes;
        this.exportState = exportState;
    }

    /** MyBatis 反射专用：包装类型入参 */
    public DownloadTaskFile(UUID id, UUID taskId, Integer fileIndex, Boolean selected,
                            Long verifiedBytes, String exportState) {
        this(id, taskId, fileIndex == null ? 0 : fileIndex,
                selected != null && selected,
                verifiedBytes == null ? 0L : verifiedBytes, exportState);
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public int getFileIndex() { return fileIndex; }
    public boolean isSelected() { return selected; }
    public long getVerifiedBytes() { return verifiedBytes; }
    public String getExportState() { return exportState; }
}
