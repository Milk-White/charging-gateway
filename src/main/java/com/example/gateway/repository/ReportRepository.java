package com.example.gateway.repository;

import com.example.gateway.domain.ChargingReport;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 上报数据存储接口。
 *
 * <p>用途：把业务层与具体存储方式隔离。当前实现写入 TSV 文件，后续可增加数据库实现，
 * 而不需要改动 {@code ReportService} 的核心业务流程。</p>
 */
public interface ReportRepository {
    /** 保存一条已经通过协议校验和业务校验的上报。 */
    void save(ChargingReport report) throws IOException;

    /** 查询指定设备最近一次上报。 */
    Optional<ChargingReport> latest(String deviceSn);

    /** 按时间倒序查询指定设备的历史上报。 */
    List<ChargingReport> history(String deviceSn, int limit);
}
