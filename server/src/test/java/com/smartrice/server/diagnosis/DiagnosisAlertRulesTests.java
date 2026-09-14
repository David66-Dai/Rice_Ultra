package com.smartrice.server.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiagnosisAlertRulesTests {

	@Test
	void leafDiseasesAreRedAndHealthyStaysGreen() {
		assertThat(AlertLevel.fromLeaf("Bacterial Leaf Blight", "细菌性叶枯病", true)).isEqualTo(AlertLevel.RED);
		assertThat(AlertLevel.fromLeaf("Brown Spot", "褐斑病", true)).isEqualTo(AlertLevel.RED);
		assertThat(AlertLevel.fromLeaf("Tungro Virus", "东格鲁病毒", true)).isEqualTo(AlertLevel.RED);
		assertThat(AlertLevel.fromLeaf("Healthy Leaf", "健康叶片", false)).isEqualTo(AlertLevel.GREEN);
		assertThat(AlertLevel.fromLeaf("Healthy Leaf", "健康叶片", null)).isEqualTo(AlertLevel.GREEN);
	}

	@Test
	void hyperspectralDiseasesAreYellowOnly() {
		assertThat(AlertLevel.fromLeafHsi("Brown Spot", "褐斑病", true)).isEqualTo(AlertLevel.YELLOW);
		assertThat(AlertLevel.fromLeafHsi("Bacterial Leaf Blight", "细菌性叶枯病", true)).isEqualTo(AlertLevel.YELLOW);
		assertThat(AlertLevel.fromLeafHsi("Healthy Leaf", "健康叶片", false)).isEqualTo(AlertLevel.GREEN);
	}

	@Test
	void pestCountMapsToGreenYellowRed() {
		assertThat(AlertLevel.fromPest(0)).isEqualTo(AlertLevel.GREEN);
		assertThat(AlertLevel.fromPest(1)).isEqualTo(AlertLevel.YELLOW);
		assertThat(AlertLevel.fromPest(2)).isEqualTo(AlertLevel.RED);
		assertThat(AlertLevel.fromPest(5)).isEqualTo(AlertLevel.RED);
	}

	@Test
	void combinedAlertKeepsTheMoreSevereChannel() {
		assertThat(AlertLevel.max(AlertLevel.GREEN, AlertLevel.GREEN)).isEqualTo(AlertLevel.GREEN);
		assertThat(AlertLevel.max(AlertLevel.GREEN, AlertLevel.YELLOW)).isEqualTo(AlertLevel.YELLOW);
		assertThat(AlertLevel.max(AlertLevel.RED, AlertLevel.YELLOW)).isEqualTo(AlertLevel.RED);
		assertThat(AlertLevel.max(AlertLevel.GREEN, AlertLevel.RED)).isEqualTo(AlertLevel.RED);
	}
}
