package com.smartrice.server.astrbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AstrBotAlertSenderTests {

	@Test
	void confirmationAlertIsSplitIntoIndependentWechatBubbles() {
		String id = "00000000-0000-4000-8000-000000000099";
		List<String> blocks = AstrBotAlertSender.alertBlocks(String.join("\n",
			"🔴 Rice Ultra 病虫害防治确认",
			"站点：S01",
			"识别到细菌性叶枯病，置信度 98.0%",
			"拟开启：智能喷药",
			"确认编号：" + id,
			"请在 10 分钟内由收到告警的授权用户发送：/agri_confirm_alert " + id,
			"确认时后端会再次核验设备版本与安全条件；未确认不会开启设备。"));

		assertThat(blocks).hasSize(4).allSatisfy(block -> assertThat(block).doesNotContain("\n"));
		assertThat(blocks.get(0)).contains("病虫害防治确认", "站点：S01");
		assertThat(blocks.get(1)).contains("识别到细菌性叶枯病", "拟开启：智能喷药");
		assertThat(blocks.get(2)).isEqualTo("确认编号：" + id);
		assertThat(blocks.get(3)).contains("/agri_confirm_alert", id, "未确认不会开启设备");
	}

	@Test
	void automaticFeedbackIsOneCompactWechatMessage() {
		assertThat(AstrBotAlertSender.feedbackMessage("S01", "pump", "风速联锁关闭",
			"当前风速 7.00 m/s 超过上限 3.00 m/s，已提交关闭喷药指令。"))
			.contains("站点：S01", "智能喷药", "风速联锁关闭")
			.doesNotContain("\n");
	}
}
