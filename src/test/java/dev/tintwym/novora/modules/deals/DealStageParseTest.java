package dev.tintwym.novora.modules.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.domain.enums.DealStage;

class DealStageParseTest {

	@Test
	void acceptsEnumNamesAndExportLabels() {
		assertEquals(DealStage.LEAD, DealsService.parseStage("LEAD"));
		assertEquals(DealStage.QUALIFIED, DealsService.parseStage("Qualified"));
		assertEquals(DealStage.NEGOTIATION, DealsService.parseStage("negotiation"));
		assertEquals(DealStage.WON, DealsService.parseStage("Won"));
	}

	@Test
	void rejectsUnknownStages() {
		assertThrows(AppException.class, () -> DealsService.parseStage("Maybe"));
	}
}
