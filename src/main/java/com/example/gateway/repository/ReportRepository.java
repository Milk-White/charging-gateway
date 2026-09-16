package com.example.gateway.repository;

import com.example.gateway.domain.ChargingReport;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ReportRepository {
    void save(ChargingReport report) throws IOException;

    Optional<ChargingReport> latest(String deviceSn);

    List<ChargingReport> history(String deviceSn, int limit);
}
