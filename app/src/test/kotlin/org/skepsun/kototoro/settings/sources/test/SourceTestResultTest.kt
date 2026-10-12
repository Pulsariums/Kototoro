package org.skepsun.kototoro.settings.sources.test

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceTestResultTest {

	private val pass = StepResult(StepStatus.PASS)
	private val skip = StepResult(StepStatus.SKIP, "desteklenmiyor")
	private val fail = StepResult(StepStatus.FAIL, "HTTP 403")

	@Test
	fun `a single failed step fails the whole source`() {
		val steps = mapOf(
			SourceTestStep.LIST to pass,
			SourceTestStep.COVERS to fail,
			SourceTestStep.DETAILS to pass,
		)
		overallStatusOf(steps) shouldBe StepStatus.FAIL
	}

	@Test
	fun `passed and skipped steps pass the source`() {
		val steps = mapOf(
			SourceTestStep.LIST to pass,
			SourceTestStep.RANDOM to skip,
			SourceTestStep.SEARCH to pass,
		)
		overallStatusOf(steps) shouldBe StepStatus.PASS
	}

	@Test
	fun `result computes overall from steps by default`() {
		val result = SourceTestResult(
			sourceKey = "src",
			title = "Source",
			steps = mapOf(SourceTestStep.LIST to pass, SourceTestStep.PAGES to fail),
		)
		result.overall shouldBe StepStatus.FAIL
	}

	@Test
	fun `empty step map passes`() {
		overallStatusOf(emptyMap()) shouldBe StepStatus.PASS
	}
}
