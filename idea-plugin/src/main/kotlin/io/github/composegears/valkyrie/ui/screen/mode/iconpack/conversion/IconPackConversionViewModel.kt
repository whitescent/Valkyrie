package io.github.composegears.valkyrie.ui.screen.mode.iconpack.conversion

import com.composegears.tiamat.Saveable
import com.composegears.tiamat.SavedState
import com.composegears.tiamat.TiamatViewModel
import io.github.composegears.valkyrie.extensions.cast
import io.github.composegears.valkyrie.generator.imagevector.ImageVectorGenerator
import io.github.composegears.valkyrie.generator.imagevector.ImageVectorGeneratorConfig
import io.github.composegears.valkyrie.generator.imagevector.ImageVectorSpecOutput
import io.github.composegears.valkyrie.parser.svgxml.IconNameFormatter
import io.github.composegears.valkyrie.parser.svgxml.SvgXmlParser
import io.github.composegears.valkyrie.parser.svgxml.util.isSvg
import io.github.composegears.valkyrie.parser.svgxml.util.isXml
import io.github.composegears.valkyrie.processing.writter.FileWriter
import io.github.composegears.valkyrie.settings.ValkyriesSettings
import io.github.composegears.valkyrie.ui.di.DI
import io.github.composegears.valkyrie.ui.extension.updateState
import io.github.composegears.valkyrie.ui.screen.mode.iconpack.conversion.ConversionEvent.OpenPreview
import io.github.composegears.valkyrie.ui.screen.mode.iconpack.conversion.IconPackConversionState.BatchProcessing
import io.github.composegears.valkyrie.ui.screen.mode.iconpack.conversion.IconPackConversionState.IconsPickering
import io.github.composegears.valkyrie.util.PainterConverter
import io.github.composegears.valkyrie.util.getOrNull
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IconPackConversionViewModel(
    savedState: SavedState?,
    paths: List<Path>,
) : TiamatViewModel(),
    Saveable {

    private val inMemorySettings by DI.core.inMemorySettings

    private val _state = MutableStateFlow<IconPackConversionState>(IconsPickering)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConversionEvent>()
    val events = _events.asSharedFlow()

    init {
        val restoredState = savedState?.getOrNull<List<BatchIcon>>(key = "icons")

        when {
            restoredState != null -> {
                if (restoredState.isEmpty()) {
                    _state.updateState { IconsPickering }
                } else {
                    _state.updateState {
                        BatchProcessing.IconPackCreationState(
                            icons = restoredState,
                            exportEnabled = restoredState.isAllIconsValid(),
                        )
                    }
                }
            }
            paths.isNotEmpty() -> {
                if (paths.size == 1 && paths.first().isDirectory()) {
                    pickerEvent(PickerEvent.PickDirectory(paths.first()))
                } else {
                    pickerEvent(PickerEvent.PickFiles(paths))
                }
            }
        }
    }

    override fun saveToSaveState(): SavedState {
        return when (val state = _state.value) {
            is BatchProcessing.IconPackCreationState -> mapOf("icons" to state.icons)
            else -> mapOf("icons" to emptyList<List<BatchIcon>>())
        }
    }

    fun pickerEvent(events: PickerEvent) {
        when (events) {
            is PickerEvent.PickDirectory -> events.path.listDirectoryEntries().processFiles()
            is PickerEvent.PickFiles -> events.paths.processFiles()
        }
    }

    fun deleteIcon(iconName: IconName) = viewModelScope.launch {
        withContext(Dispatchers.Default) {
            _state.updateState {
                when (this) {
                    is BatchProcessing.IconPackCreationState -> {
                        val iconsToProcess = icons.filter { it.iconName != iconName }

                        if (iconsToProcess.isEmpty()) {
                            _state.updateState { IconsPickering }
                            this
                        } else {
                            copy(
                                icons = iconsToProcess,
                                exportEnabled = iconsToProcess.isAllIconsValid(),
                            )
                        }
                    }
                    else -> this
                }
            }
        }
    }

    fun updateIconPack(batchIcon: BatchIcon, nestedPack: String) = viewModelScope.launch {
        withContext(Dispatchers.Default) {
            _state.updateState {
                when (this) {
                    is BatchProcessing.IconPackCreationState -> {
                        copy(
                            icons = icons.map { icon ->
                                if (icon.iconName == batchIcon.iconName && icon is BatchIcon.Valid) {
                                    icon.copy(
                                        iconPack = when (icon.iconPack) {
                                            is IconPack.Nested -> icon.iconPack.copy(currentNestedPack = nestedPack)
                                            is IconPack.Single -> icon.iconPack
                                        },
                                    )
                                } else {
                                    icon
                                }
                            },
                        )
                    }
                    else -> this
                }
            }
        }
    }

    fun showPreview(iconName: IconName) = viewModelScope.launch {
        withContext(Dispatchers.Default) {
            val icons = when (val state = _state.value) {
                is BatchProcessing.IconPackCreationState -> state.icons
                else -> return@withContext
            }

            val icon = icons.first { it.iconName == iconName }.cast<BatchIcon.Valid>()

            val settings = inMemorySettings.current
            val iconResult = runCatching {
                val parserOutput = SvgXmlParser.toIrImageVector(icon.path)

                ImageVectorGenerator.convert(
                    vector = parserOutput.irImageVector,
                    iconName = iconName.value,
                    config = ImageVectorGeneratorConfig(
                        packageName = icon.iconPack.iconPackage,
                        iconPackPackage = settings.iconPackPackage,
                        packName = settings.iconPackName,
                        nestedPackName = icon.iconPack.currentNestedPack,
                        outputFormat = settings.outputFormat,
                        generatePreview = settings.generatePreview,
                        useFlatPackage = settings.flatPackage,
                        useExplicitMode = settings.useExplicitMode,
                        addTrailingComma = settings.addTrailingComma,
                    ),
                )
            }.getOrDefault(ImageVectorSpecOutput.empty)

            _events.emit(OpenPreview(iconResult.content))
        }
    }

    fun export() = viewModelScope.launch {
        withContext(Dispatchers.Default) {
            val icons = when (val state = _state.value) {
                is BatchProcessing.IconPackCreationState -> state.icons
                else -> return@withContext
            }

            _state.updateState { BatchProcessing.ExportingState }

            val settings = inMemorySettings.current

            icons
                .filterIsInstance<BatchIcon.Valid>()
                .forEach { icon ->
                    when (val iconPack = icon.iconPack) {
                        is IconPack.Nested -> {
                            val parserOutput = SvgXmlParser.toIrImageVector(icon.path)
                            val vectorSpecOutput = ImageVectorGenerator.convert(
                                vector = parserOutput.irImageVector,
                                iconName = icon.iconName.value,
                                config = ImageVectorGeneratorConfig(
                                    packageName = icon.iconPack.iconPackage,
                                    iconPackPackage = settings.iconPackPackage,
                                    packName = settings.iconPackName,
                                    nestedPackName = iconPack.currentNestedPack,
                                    outputFormat = settings.outputFormat,
                                    generatePreview = settings.generatePreview,
                                    useFlatPackage = settings.flatPackage,
                                    useExplicitMode = settings.useExplicitMode,
                                    addTrailingComma = settings.addTrailingComma,
                                ),
                            )

                            FileWriter.writeToFile(
                                content = vectorSpecOutput.content,
                                outDirectory = when {
                                    settings.flatPackage -> settings.iconPackDestination
                                    else -> "${settings.iconPackDestination}/${iconPack.currentNestedPack.lowercase()}"
                                },
                                fileName = vectorSpecOutput.name,
                            )
                        }
                        is IconPack.Single -> {
                            val parserOutput = SvgXmlParser.toIrImageVector(icon.path)
                            val vectorSpecOutput = ImageVectorGenerator.convert(
                                vector = parserOutput.irImageVector,
                                iconName = icon.iconName.value,
                                config = ImageVectorGeneratorConfig(
                                    packageName = icon.iconPack.iconPackage,
                                    iconPackPackage = settings.iconPackPackage,
                                    packName = settings.iconPackName,
                                    nestedPackName = "",
                                    outputFormat = settings.outputFormat,
                                    generatePreview = settings.generatePreview,
                                    useFlatPackage = settings.flatPackage,
                                    useExplicitMode = settings.useExplicitMode,
                                    addTrailingComma = settings.addTrailingComma,
                                ),
                            )

                            FileWriter.writeToFile(
                                content = vectorSpecOutput.content,
                                outDirectory = settings.iconPackDestination,
                                fileName = vectorSpecOutput.name,
                            )
                        }
                    }
                }

            _events.emit(ConversionEvent.ExportCompleted)
            reset()
        }
    }

    fun renameIcon(batchIcon: BatchIcon, newName: IconName) = viewModelScope.launch {
        withContext(Dispatchers.Default) {
            _state.updateState {
                when (this) {
                    is BatchProcessing.IconPackCreationState -> {
                        val icons = icons.map { icon ->
                            if (icon.iconName == batchIcon.iconName) {
                                when (icon) {
                                    is BatchIcon.Broken -> icon
                                    is BatchIcon.Valid -> icon.copy(iconName = newName)
                                }
                            } else {
                                icon
                            }
                        }
                        copy(
                            icons = icons,
                            exportEnabled = icons.isAllIconsValid(),
                        )
                    }
                    else -> this
                }
            }
        }
    }

    fun reset() {
        _state.updateState { IconsPickering }
    }

    private fun List<Path>.processFiles() = viewModelScope.launch {
        withContext(Dispatchers.Default) {
            val paths = filter { it.isRegularFile() && (it.isXml || it.isSvg) }

            if (paths.isNotEmpty()) {
                _state.updateState { BatchProcessing.ImportValidationState }
                _state.updateState {
                    val icons = paths
                        .sortedBy { it.name }
                        .map { path ->
                            when (PainterConverter.from(path, imageScale = 0.1)) {
                                null -> BatchIcon.Broken(
                                    iconName = IconName(path.nameWithoutExtension),
                                    extension = path.extension,
                                )
                                else -> BatchIcon.Valid(
                                    iconName = IconName(IconNameFormatter.format(path.name)),
                                    extension = path.extension,
                                    iconPack = inMemorySettings.current.buildDefaultIconPack(),
                                    path = path,
                                )
                            }
                        }
                    BatchProcessing.IconPackCreationState(
                        icons = icons,
                        exportEnabled = icons.isAllIconsValid(),
                    )
                }
            }
        }
    }

    private fun ValkyriesSettings.buildDefaultIconPack(): IconPack {
        return when {
            nestedPacks.isEmpty() -> {
                IconPack.Single(
                    iconPackName = iconPackName,
                    iconPackage = packageName,
                )
            }
            else -> IconPack.Nested(
                iconPackName = iconPackName,
                iconPackage = packageName,
                currentNestedPack = nestedPacks.first(),
                nestedPacks = nestedPacks,
            )
        }
    }

    private fun List<BatchIcon>.isAllIconsValid() = isNotEmpty() &&
        all { it is BatchIcon.Valid } &&
        all { it.iconName.value.isNotEmpty() && !it.iconName.value.contains(" ") }
}

sealed interface ConversionEvent {
    data class OpenPreview(val iconContent: String) : ConversionEvent
    data object ExportCompleted : ConversionEvent
}

sealed interface PickerEvent {
    data class PickDirectory(val path: Path) : PickerEvent
    data class PickFiles(val paths: List<Path>) : PickerEvent
}
