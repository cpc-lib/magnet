package cc.ivera.magnet.application;

import java.util.List;

/** 通用分页结果（page 从 1 开始） */
public record Page<T>(List<T> items, long total, int page, int size) {
}
