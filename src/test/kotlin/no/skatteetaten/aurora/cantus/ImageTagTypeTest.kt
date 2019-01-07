package no.skatteetaten.aurora.cantus

import assertk.assert
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.cantus.service.ImageTagType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

data class imageTagTypeTestData(val inputStrig: String, val expectedTag: ImageTagType)

class ImageTagTypeTest {

    @ParameterizedTest
    @MethodSource("arguments")
    fun `Verify image name is converted to correct image tag`(imageTagTypeInput: imageTagTypeTestData) {
        imageTagTypeInput.apply {
            val imageTagCalculated = ImageTagType.typeOf(imageTagTypeInput.inputStrig)
            assert(imageTagCalculated).isEqualTo(imageTagTypeInput.expectedTag)
        }
    }

    fun arguments() = listOf<imageTagTypeTestData>(
        imageTagTypeTestData(
            "SNAPSHOT-feature-AOS-2287-20180102.092832-15-b1.5.5-flange-8.152.18",
            ImageTagType.AURORA_SNAPSHOT_VERSION
        ),
        imageTagTypeTestData("feature-AOS-2287-SNAPSHOT", ImageTagType.SNAPSHOT),
        imageTagTypeTestData("4.2.4", ImageTagType.BUGFIX),
        imageTagTypeTestData("4.2", ImageTagType.MINOR),
        imageTagTypeTestData("4", ImageTagType.MAJOR),
        imageTagTypeTestData("latest", ImageTagType.LATEST),
        imageTagTypeTestData("4.1.3-b1.6.0-flange-8.152.18", ImageTagType.AURORA_VERSION),
        imageTagTypeTestData("4b071d3", ImageTagType.COMMIT_HASH),
        imageTagTypeTestData("4b07103", ImageTagType.COMMIT_HASH),
        imageTagTypeTestData("4007103", ImageTagType.COMMIT_HASH),
        // This isn't ideal but the fallback type is AURORA_VERSION, so even tags that are strictly not
        // AuroraVersions will get the AURORA_VERSION type
        imageTagTypeTestData("weirdness", ImageTagType.AURORA_VERSION)
    )
}