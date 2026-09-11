package com.smartrice.server.ai;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiEvidenceService {
	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");
	private static final Set<Integer> WINDOWS = Set.of(7, 14, 30);
	private static final Set<String> STAGES = Set.of("unknown", "seedling", "tillering", "jointing",
		"booting", "heading", "filling", "mature");
	private final HiveAiRepository repository;
	private final HiveLegacyAiRepository legacyRepository;
	private final Clock clock;

	@Autowired
	public AiEvidenceService(HiveAiRepository repository, HiveLegacyAiRepository legacyRepository) {
		this(repository, legacyRepository, Clock.system(FIELD_ZONE));
	}

	AiEvidenceService(HiveAiRepository repository, Clock clock) {
		this(repository, null, clock);
	}

	AiEvidenceService(HiveAiRepository repository, HiveLegacyAiRepository legacyRepository, Clock clock) {
		this.repository = repository;
		this.legacyRepository = legacyRepository;
		this.clock = clock;
	}

	public AiEvidence evidence(String stationId, LocalDate date, int windowDays, String growthStage) {
		String station = stationId == null ? "" : stationId.trim().toUpperCase(Locale.ROOT);
		if (!station.matches("S(?:0[1-9]|10)")) throw invalid("stationId 必须为 S01-S10");
		if (!WINDOWS.contains(windowDays)) throw invalid("分析窗口只能为 7、14 或 30 天");
		if (date == null || date.getYear() < 1 || date.getYear() > 9999
				|| date.isAfter(LocalDate.now(clock.withZone(FIELD_ZONE)))
				|| date.isBefore(LocalDate.of(1, 1, 1).plusDays(windowDays - 1L))) {
			throw invalid("目标日期必须有效且不能晚于北京时间当天，窗口起始日期不得早于公元 1 年");
		}
		String stage = growthStage == null ? "unknown" : growthStage.trim().toLowerCase(Locale.ROOT);
		if (!STAGES.contains(stage)) throw invalid("生育期必须为支持的枚举值，未知时使用 unknown");
		String hiveStation = "point_" + Integer.parseInt(station.substring(1));
		LocalDate start = date.minusDays(windowDays - 1L);
		HiveAiRepository.WindowRows rows;
		try {
			rows = repository.window(hiveStation, start, date);
		} catch (HiveAiRepository.RowLimitException ex) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
				"当前分析窗口的数据量达到处理上限，请缩短窗口或检查源数据重复记录");
		} catch (SQLException ex) {
			// Do not return/log exception messages or causes: JDBC errors can contain credentials.
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				"Hive 环境数据暂不可用，请稍后重试或检查服务端连接配置");
		}
		Map<String, Double> target = rows.daily().get(date);
		if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
			station + " 在目标日期没有 Hive 环境记录，请选择实际有记录的日期");
		if (HiveAiRepository.FIELDS.stream().noneMatch(field -> valid(target.get(field.field())))) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
				"目标日期的 11 项环境指标均缺失或无效，无法生成环境分析");
		}

		List<LocalDate> missingDates = new ArrayList<>();
		List<AiEvidence.Day> days = new ArrayList<>();
		for (int offset = 0; offset < windowDays; offset++) {
			LocalDate day = start.plusDays(offset);
			Map<String, Double> observed = rows.daily().get(day);
			if (observed == null) missingDates.add(day);
			Map<String, Double> values = new LinkedHashMap<>();
			for (var field : HiveAiRepository.FIELDS) {
				Double value = observed == null ? null : observed.get(field.field());
				values.put(field.field(), valid(value) ? HiveAiRepository.rounded(BigDecimal.valueOf(value)) : null);
			}
			days.add(new AiEvidence.Day(day, values));
		}
		List<AiEvidence.Metric> metrics = new ArrayList<>();
		for (var field : HiveAiRepository.FIELDS) metrics.add(summarize(field, days));
		List<String> limitations = new ArrayList<>(List.of(
			"数据仅来自 Hive 环境观测；同日重复记录按各指标有效值求日均值，窗口统计按日等权，不按原始行数加权。",
			"first 和 last 分别指窗口起始日和目标日的日均值；端点缺失时保留空值，change 仅在两端均有效时计算。",
			"缺失值及非有限数值不按 0 处理；有限异常值未自动裁剪或做农艺校准，解释前应核查传感器与采样方法。",
			"保留原始单位 lux、dS/m、ppm；照度不等于日照时长，氮磷钾浓度不直接换算施肥用量。",
			"窗口仅含截至目标日期的观测，无未来天气预报、降雨或日照时长数据。",
			"11 项环境指标本身不含实测病虫数量、发病率或受害面积；旧表病虫参考信息另列，环境风险线索不能单独作确诊。",
			"11 项环境指标本身不含产量基线和实收数据；旧表产量基线另列，不能将基线直接视为本次预测或实际收获产量。",
			"分析建议仅供人工研判，不是设备命令，不自动执行喷药、开灯或其他硬件操作。"
		));
		if (!missingDates.isEmpty()) limitations.add("窗口有 " + missingDates.size() + " 天没有 Hive 记录，历史覆盖不完整。");
		long incompleteMetrics = metrics.stream().filter(metric -> metric.missingCount() > 0).count();
		if (incompleteMetrics > 0) limitations.add(incompleteMetrics + " 项指标存在缺测日，各项统计有效日数可能不同。");
		limitations.add(stage.equals("unknown") ? "生育期未知，不能按特定生育期作确定性农艺判断。"
			: "生育期由用户选择提供，未由 Hive 环境数据验证。");
		if (Thread.currentThread().isInterrupted()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
			"分析任务已中断，未继续读取旧版参考数据");
		AiLegacyContext legacy = legacyRepository == null ? null : legacyRepository.context(station, hiveStation, date);
		return new AiEvidence(station, hiveStation, start, date, windowDays, windowDays - missingDates.size(),
			missingDates, rows.rawRowCount(), metrics, days, stage, limitations, legacy);
	}

	private static AiEvidence.Metric summarize(HiveAiRepository.MetricDefinition field, List<AiEvidence.Day> days) {
		var average = new HiveAiRepository.Average();
		int count = 0;
		Double min = null;
		Double max = null;
		for (var day : days) {
			Double value = day.values().get(field.field());
			if (value == null) continue;
			average.add(value);
			count++;
			min = min == null ? value : Math.min(min, value);
			max = max == null ? value : Math.max(max, value);
		}
		Double first = days.getFirst().values().get(field.field());
		Double last = days.getLast().values().get(field.field());
		Double change = first == null || last == null ? null
			: HiveAiRepository.rounded(BigDecimal.valueOf(last).subtract(BigDecimal.valueOf(first)));
		return new AiEvidence.Metric(field.field(), field.label(), field.unit(), count, days.size() - count,
			average.mean(), min, max, first, last, change);
	}

	private static boolean valid(Double value) {
		return value != null && Double.isFinite(value);
	}

	private static ResponseStatusException invalid(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
