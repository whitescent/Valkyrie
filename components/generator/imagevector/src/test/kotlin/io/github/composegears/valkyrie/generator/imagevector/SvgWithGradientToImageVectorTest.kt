package io.github.composegears.valkyrie.generator.imagevector

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.composegears.valkyrie.extensions.ResourceUtils.getResourcePath
import io.github.composegears.valkyrie.generator.imagevector.common.createConfig
import io.github.composegears.valkyrie.generator.imagevector.common.toResourceText
import io.github.composegears.valkyrie.parser.svgxml.SvgXmlParser
import io.github.composegears.valkyrie.parser.svgxml.util.IconType.SVG
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class SvgWithGradientToImageVectorTest {

    @ParameterizedTest
    @EnumSource(value = OutputFormat::class)
    fun `svg linear gradient parsing`(outputFormat: OutputFormat) {
        val icon = getResourcePath("svg/ic_linear_gradient.svg")
        val parserOutput = SvgXmlParser.toIrImageVector(icon)
        val output = ImageVectorGenerator.convert(
            vector = parserOutput.irImageVector,
            iconName = parserOutput.iconName,
            config = createConfig(outputFormat = outputFormat),
        ).content

        val expected = outputFormat.toResourceText(
            pathToBackingProperty = "kt/backing/LinearGradient.kt",
            pathToLazyProperty = "kt/lazy/LinearGradient.kt",
        )
        assertThat(parserOutput.iconType).isEqualTo(SVG)
        assertThat(output).isEqualTo(expected)
    }

    @ParameterizedTest
    @EnumSource(value = OutputFormat::class)
    fun `svg radial gradient parsing`(outputFormat: OutputFormat) {
        val icon = getResourcePath("svg/ic_radial_gradient.svg")
        val parserOutput = SvgXmlParser.toIrImageVector(icon)
        val output = ImageVectorGenerator.convert(
            vector = parserOutput.irImageVector,
            iconName = parserOutput.iconName,
            config = createConfig(outputFormat = outputFormat),
        ).content

        val expected = outputFormat.toResourceText(
            pathToBackingProperty = "kt/backing/RadialGradient.kt",
            pathToLazyProperty = "kt/lazy/RadialGradient.kt",
        )
        assertThat(parserOutput.iconType).isEqualTo(SVG)
        assertThat(output).isEqualTo(expected)
    }

    @ParameterizedTest
    @EnumSource(value = OutputFormat::class)
    fun `svg linear gradient with stroke parsing`(outputFormat: OutputFormat) {
        val icon = getResourcePath("svg/ic_linear_gradient_with_stroke.svg")
        val parserOutput = SvgXmlParser.toIrImageVector(icon)
        val output = ImageVectorGenerator.convert(
            vector = parserOutput.irImageVector,
            iconName = parserOutput.iconName,
            config = createConfig(outputFormat = outputFormat),
        ).content

        val expected = outputFormat.toResourceText(
            pathToBackingProperty = "kt/backing/LinearGradientWithStroke.kt",
            pathToLazyProperty = "kt/lazy/LinearGradientWithStroke.kt",
        )
        assertThat(parserOutput.iconType).isEqualTo(SVG)
        assertThat(output).isEqualTo(expected)
    }
}
