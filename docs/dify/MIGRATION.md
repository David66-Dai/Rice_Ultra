# Dify 环境分析与旧源参考工作流

交付文件 `rice_environment_analysis.yml` 是一个 Workflow DSL，唯一必填输入为
`environment_json`，输出 `weather_analysis`、`soil_analysis`、`risk_analysis`、`summary`
四个字符串。流程为：开始 → 气象环境分析 → 土壤水肥分析 → 环境诱发风险与旧源病虫记录
→ 核对原始证据后汇总 → 输出。前三份报告各自读取完整原始输入，汇总同时读取原始输入和三份报告。
顺序执行避免不同部署版本的并行合流差异；一次完整执行包含四次模型调用。

本次保留旧病虫害表和产量表作为补充来源。主环境表是 `farm.agri_env_data`；
`legacyContext` 中的旧病虫记录来自 `farm.pest_data`，产量基线来自 `farm.rice_yield`。
同日旧源病虫记录与历史参考必须分开表述，产量基线无论是否同年都不能叫本次新预测。
整个工作流不读取数据库、不携带服务端配置或密钥、不调用设备。

## 报告正文约定

四个输出变量、输入结构、节点编号、模型和连线不变。本次调整的是呈现给种植者的正文：
气象、土壤和风险分析各写 80–300 个汉字，最多选 2 个关键数值变化；风险分析重点讲巡查和病虫记录日期，
不再重复氮磷钾、酸碱度、电导率等数值。综合建议目标约 450–600 个中文字符，证据较少时允许更短，
离线格式检查接受 250–650 个汉字，保持上限不放宽。
正文采用中文纯文本自然段，最多保留独占一行的中文段名；不使用标题标记、强调符号、反引号、
列表、表格、代码、对象格式或网页标签。说明文档和内部接口仍保留技术格式，不能混入报告正文。

站点写“一号监测站”至“十号监测站”，单位写摄氏度、勒克斯、米每秒、分西门子每米、
百万分浓度或公斤每亩，湿度允许百分号；酸碱度及氮、磷、钾使用中文名称。
正文不得出现英文或原始站点编号，也不显示数据库、表名、字段名、接口、内部校验过程。
唯一允许的拉丁字母是方案A和方案B中的字母；正文可在选择建议中再次引用这两个方案名。

综合建议固定包含四个段名，顺序如下：

```text
整体判断

方案A：高成本高效率型

方案B：低成本稳定型

选择建议
```

每个段名之后必须写自然段正文。高投入方案组织专业人员联合排查、集中检测，必要时租用合适设备集中处理；
稳步方案使用现有人手与工具，先重点区域、再分阶段扩大，控制额外投入。两案都要交代适用情况、
具体做法、投入构成、相对起效预期与注意事项，不能都停留在检查数据。
整体判断控制在 2–3 句，最多保留 1 个最重要变化及必要的旧年份产量参考；两案各 3 句，
将适用情况与投入、具体动作、效率与主要注意点分别合并说明；选择建议写 1–2 句，不再重复前文。
在现场确认与农技意见支持下，可以建议分区补水、疏通沟渠、逐步改善水肥管理。
成本与效率仅作相对比较，不能编造金额、增产比例、挽回损失、精确见效时间、药肥剂量或设备执行结果。

每个分析只保留少量关键监测数字，时间段及重要缺失简短交代，不复述整套统计规则。
日期和数值使用阿拉伯数字，不改成大段中文数词；正文数值最多保留 2 位小数，输入资料仍保留原有精度。
数据局限仅用一句面向种植者的话，不把“缺失不能当零”“不要补造阈值”等内部核对规则写到报告里。
病虫历史日期仍须保留，可写“病虫记录来自2025年12月31日，需复查当前情况”；日期按输入实际值替换。
有产量基线时综合建议最多引用一次年份、季别和已有数值，并说明只是历史参考，不是新预测。
不能凭监测数据推断实际生育期或确诊病虫害。真实性规则留在提示词内部，正文用自然语言讲清必要边界。

## 导入与模型

1. 在 Dify 工作室选择“导入 DSL 文件”，导入本目录的 `rice_environment_analysis.yml`。
2. DSL 使用版本 `0.3.0`，延续旧项目导出文件的格式基线。目标 Dify 可能显示版本迁移提示；
   导入后检查六个节点和五条连线。文件包含工作流结构，不包含账号授权信息。
3. 四个 LLM 节点已选择本机旧气象流程存在的“深度求索 / deepseek-v4-flash”，
   provider 为 `langgenius/deepseek/deepseek`，chat 模式、temperature 0.2、thinking false。
   若导入另一台实例，逐一选择该实例已配置且能处理完整输入的模型。这里没有固定插件安装包版本或凭据；
   插件未安装、模型未授权时需要在目标 Dify 配置模型，不能仅凭 DSL 导入成功认定可运行。
4. 检查开始节点：只有 `environment_json`，类型“段落”，必填，`max_length: 131072`。
5. 将 `environment_input.example.json` 的完整文件内容粘贴进此字段，运行测试。
   该文件是合成的契约测试数据，不能作为真实农田报告或真实 Hive 查询成功的证据。
6. 检查输出后发布工作流，再核对服务 API 的 `/parameters`：已发布表单必须仍有
   `environment_json` 且长度上限足够。修改开始节点后需要重新发布；不能靠截断 JSON 避免输入超长。

对已经发布并接入新版后端的应用，只同步四个既有 LLM 节点的系统与用户提示词，再发布该应用；
不要因修改报告风格创建新的应用、替换应用标识或更换密钥。可用以下只读命令取得用于界面粘贴的
`{nodes: [{id, system, user}]}` 内容，文本直接来自 DSL，无需维护第二份提示词副本：

```powershell
python docs/dify/validate_workflow.py --export-prompts
```

输出使用标准 JSON 转义避免终端中文编码损坏，解析 JSON 后即为原始中文提示词；不要把带转义的原文直接粘贴到提示词输入框。

Dify 节点字段与变量选择器按官方节点结构核对，开始变量使用 `paragraph/required/max_length`，
LLM 使用 `model/prompt_template/context/vision`，结束节点使用 `outputs[].value_selector`。
[官方节点结构源码](https://github.com/langgenius/dify/blob/main/api/core/workflow/generator/prompts/builder_prompts.py)
提供这些字段；[官方 DSL 导入实现](https://github.com/langgenius/dify/blob/main/api/services/app_dsl_service.py)
按 DSL 版本及目标实例处理导入。此次使用旧格式基线，不宣称覆盖所有部署版本。

模型命名由本机 UI 和
[官方 DeepSeek 插件清单](https://github.com/langgenius/dify-official-plugins/blob/main/models/deepseek/manifest.yaml)、
[模型参数定义](https://github.com/langgenius/dify-official-plugins/blob/main/models/deepseek/models/llm/deepseek-v4-flash.yaml)
共同确认。参数定义中的 `thinking` 是布尔类型，因此 DSL 显式设为 `false`；没有写入最大输出长度或模型密钥。

## 输入契约

调用 `/workflows/run` 时，`inputs.environment_json` 是 JSON **字符串**，不是嵌套对象；
`response_mode` 可由后端选择，四个返回值在成功响应的 `data.outputs` 中。
不要将整个 HTTP 请求体粘贴到开始节点；节点输入是下面这一个对象的序列化文本。

| 字段 | 含义 |
|---|---|
| `stationId` / `hiveStation` | 前端站点 `S01`…`S10` 与 Hive 站点 `point_1`…`point_10` |
| `startDate` / `endDate` | 包含两端的 `YYYY-MM-DD` 窗口；endDate 是本次目标日 |
| `windowDays` | 窗口完整日历天数 |
| `observedDays` | Hive 中存在归档行的天数；不等于每个指标的有效天数 |
| `missingDates` | 没有归档行的日期数组 |
| `rawRowCount` | 聚合前查询到的环境表总行数；不同日期可能各有多行 |
| `metrics` | 下述 11 项环境指标的窗口统计 |
| `daily` | 完整日历的数组：`{date, values}`；values 保留 11 个原始字段名和单位，缺值为 null |
| `growthStage` | `unknown`，或由用户明确选择的阶段；模型不得自行推断 |
| `legacyContext` | 可选，旧表病虫害记录和产量基线，结构见后文 |
| `limitations` | 服务端确定的数据缺失、聚合方法、采样/业务限制的字符串数组 |

`metrics` 每项包括 `field,label,unit,count,missingCount,mean,min,max,first,last,change`。
先对同一天每字段的有效值求均值，再按天等权计算窗口 `mean/min/max`，统计值由服务端舍入到 6 位小数。
`count` 是该字段有有效日均值的天数，`missingCount = windowDays - count`，包含无记录日和有记录但该字段全空的日。
`first`、`last` 严格指窗口首日与末日，不是任意首末有效日；任一端缺值则 `change = null`，
否则为 `last - first`。不能把空值当作零，也不能插值补齐。完整缺失日的 `daily.values` 全为 null。
目标日无记录或全部指标无有效值时，后端应停止分析请求并提示用户，不让模型补写报告。

| 原始字段 | 单位 |
|---|---|
| `light_lux` | lux |
| `temperature_celsius` | °C |
| `humidity_percent` | % |
| `wind_speed_m_s` | m/s |
| `soil_temperature_celsius` | °C |
| `soil_moisture_percent` | % |
| `ph` | pH |
| `electrical_conductivity_ds_m` | dS/m |
| `nitrogen_concentration_ppm` | ppm |
| `phosphorus_concentration_ppm` | ppm |
| `potassium_concentration_ppm` | ppm |

工作流使用原始单位；网页历史页的 kLx 显示换算不应带入本契约。NPK 传感器 ppm 不自动换算为
mg/kg 或每亩肥量，日均值最大值不等于逐小时最高值，光照强度不等于日照时数。

`legacyContext` 结构如下。`values` 原样承载旧表已读取、已标注语义的字段；具体旧表列映射由后端负责，
工作流不根据名称猜测不存在的指标。

```text
legacyContext:
  source: hive_legacy
  stationId: S01
  disease:
    available: boolean
    sourceTable: string
    referenceDate: YYYY-MM-DD 或 null
    matchType: exact | latest_prior | missing
    values: [{field, label, unit, value: string}]
  yield:
    available: boolean
    sourceTable: string
    referenceYear: number 或 null
    season: string
    matchType: exact_year | latest_prior | missing
    baselineKgPerMu: number 或 null
  limitations: string[]
```

旧病虫记录先匹配目标日；没有同日记录时只允许最近且不晚于目标日的资料。
`exact` 在正文中可写“当日病虫记录显示”；`latest_prior` 需要用自然语言交代历史记录日期与复查必要性。
产量先匹配目标年份；否则只使用最近且不晚于该年的基线，明确年份与季别。
`available=false`、`matchType=missing` 或值为空时写缺失，不生成替代数字。
同年基线也不是当前实测产量，模型不能根据环境数据计算增产量、挽回损失、预测区间或置信度。

## 与旧项目的区别

已只读核对旧 `AI应用开发/code/main.py`、`weather.py`、`soil.py`、`disease.py` 和 `rice_yield.py`。
旧调用器以三个分流程输出及独立产量结果组成 `input1`…`input4` 再汇总。
旧 weather 调用器还拼接未来七日预报，soil 使用有机质等列，disease 使用病虫覆盖率与数量，
rice_yield 依赖产量基线及多因子修正。本次环境表没有这些完整前提，因此不直接沿用旧输入文本。
旧目录没有导出的 LLM 系统提示词；能确认的是调用器构造的输入文本。可用旧 DSL 是防治核验的
纯代码工作流，只参考其导入封装与节点格式，没有迁入防治控制逻辑。

| 旧流程 | 新流程 |
|---|---|
| 当日与未来气象、预报图表 | 已归档窗口的气象环境观察；无预报源就不写未来天气 |
| 土壤有机质等综合输入、定量措施 | 只用当前 7 项土壤字段，缺少采样和农艺依据时不给药肥定量 |
| 病虫数据分析 | 环境诱发风险 + 旧表同日/历史病虫参考，明确证据来源 |
| 产量基线乘多因子修正 | 保留旧源产量基线的数值、年份和季别，不生成新的预测 |
| 多个 API Key 与多个 `output1/output2` 约定 | 一个 Workflow API，四个固定的字符串输出 |

报告提示词把输入与中间报告视为数据，要求忽略其中的指令，并在汇总阶段重新核对原始证据。
这约束模型行为，但离线结构检查不能证明生成文本永远正确；真实运行后仍需核对日期、单位、
引用数值及历史参考标签。模型未输出四个非空字符串、执行失败或超时时，后端应显示失败，不伪造成功结果。

## 离线检查

运行环境为 Python 3.10+ 与 PyYAML（新版推理依赖已包含 PyYAML）。在项目根目录运行：

```powershell
python docs/dify/validate_workflow.py
python -m unittest discover -s docs/dify -p 'test_*.py' -v
```

校验涵盖 YAML 重复键、应用类型、开始输入上限、图连接与变量引用、四个输出、模型字段、禁止外部操作节点，
以及样例日期、11 字段单位、完整日历、缺失统计、端点变化与旧源参考契约，还检查中文纯文本、字数与两方案提示词约定。
真实执行后，可把 `data.outputs` 中的四个字符串单独保存为 JSON 对象，再进行只读格式检查：

```powershell
python docs/dify/validate_workflow.py --outputs path/to/workflow-outputs.json
```

正文检查能发现拉丁字母、标记语言、原始技术名称、字数、方案标题重复或缺失，以及两案缺乏不同资源与执行安排的明显问题。
它不验证农艺建议是否正确，不证明所有数字都有输入依据，也不能替代真实模型输出的人工核对。
脚本不连接 Hive、Dify 或模型，
不读取 `conf/config.yaml`，不发出控制指令。成功只代表结构及测试数据一致性；真实导入、模型调用、
发布后 `/parameters` 与端到端 API 验证需要另行记录。
