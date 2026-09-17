package com.example.gateway.domain;

/** 演示数据字典；完整点位仍会原样保存，以下地址用于生成网页摘要。 */
public final class ChargingPointAddresses {
    public static final int STATUS = 1001;
    public static final int VOLTAGE = 1002;
    public static final int CURRENT = 1003;
    public static final int FAULT_CODE = 1004;
    public static final int POWER = 1005;
    public static final int GUN_TEMPERATURE = 1006;
    public static final int CHARGER_TEMPERATURE = 1007;
    public static final int REMAINING_SECONDS = 1008;
    public static final int CHARGED_SECONDS = 1009;
    public static final int TOTAL_ENERGY = 1010;
    public static final int PHASE_A_VOLTAGE = 1101;
    public static final int PHASE_B_VOLTAGE = 1102;
    public static final int PHASE_C_VOLTAGE = 1103;
    public static final int PHASE_A_CURRENT = 1111;
    public static final int PHASE_B_CURRENT = 1112;
    public static final int PHASE_C_CURRENT = 1113;
    public static final int METER_ENERGY = 1120;
    public static final int SOFTWARE_VERSION = 1201;
    public static final int HARDWARE_VERSION = 1202;
    public static final int QR_CODE = 1203;
    public static final int BMS_DATA = 1301;

    private ChargingPointAddresses() {
    }
}
