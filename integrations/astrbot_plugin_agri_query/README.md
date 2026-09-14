# Rice Ultra AstrBot 农业查询插件

这是旧项目 `astrbot_plugin_agri_query` 的完整能力迁移版。五个原工具名和两个命令保持不变，但数据访问已改为 Rice Ultra Java API：插件不再直连 MySQL，也不再保存数据库账号密码。

## 配置

- `api_base`：通常为 `http://127.0.0.1:8185`。
- `api_token`：与后端 `app.astrbot-control.api-token` 一致，至少 32 位。
- `default_station`：默认 `S01`。

后端仍按 `UMO + sender ID` 精确映射平台账号；未配置、重复映射和停用账号全部拒绝。服务端 `max-query-range-days` 默认限制一次查询 31 天，最高 366 天。

## 已迁移能力

- `query_station_monitoring`：单站单日病虫害汇总。
- `query_monitoring_history`：单站日期范围汇总。
- `query_all_monitoring_by_date`：某日全部站点汇总。
- `query_red_alerts`：红色识别告警。
- `query_alert_status`：黄色/红色平台告警发布记录。
- `/agri_query_status`、`/agri_query_all 日期`。

站点采用当前项目的 `S01-S10`，同时兼容旧插件的 `ST-001-ST-010` 写法。当天数据来自 `inspection_diagnosis`，往期数据使用后端配置的 Hive 归档；返回值中的来源和局限说明以 Java API 为准。
新流程产生的识别记录以 `platform_published` 标明已写入平台通知；升级前的旧记录没有关联编号，诚实返回 `not_tracked`，不会伪装成已推送。
