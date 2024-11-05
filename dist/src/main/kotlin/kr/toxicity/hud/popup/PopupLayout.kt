package kr.toxicity.hud.popup

import com.google.gson.JsonArray
import kr.toxicity.hud.api.component.PixelComponent
import kr.toxicity.hud.api.component.WidthComponent
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.component.LayoutComponentContainer
import kr.toxicity.hud.hud.HudImpl
import kr.toxicity.hud.image.LocationGroup
import kr.toxicity.hud.layout.LayoutGroup
import kr.toxicity.hud.location.AnimationType
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.manager.*
import kr.toxicity.hud.pack.PackGenerator
import kr.toxicity.hud.player.head.HeadKey
import kr.toxicity.hud.player.head.HeadRenderType.FANCY
import kr.toxicity.hud.player.head.HeadRenderType.STANDARD
import kr.toxicity.hud.renderer.HeadRenderer
import kr.toxicity.hud.renderer.ImageRenderer
import kr.toxicity.hud.renderer.TextRenderer
import kr.toxicity.hud.resource.GlobalResource
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.ShaderGroup
import kr.toxicity.hud.text.HudTextData
import kr.toxicity.hud.util.*
import net.kyori.adventure.text.Component
import kotlin.math.roundToInt

class PopupLayout(
    private val resource: GlobalResource,
    private val json: JsonArray,
    private val layout: LayoutGroup,
    private val parent: PopupImpl,
    private val globalLocation: GuiLocation,
    private val globalPixel: PixelLocation,
    private val file: List<String>
) {
    private var textIndex = 0

    private val groups = parent.move.locations.map { location ->
        PopupLayoutGroup(location, json)
    }

    fun getComponent(reason: UpdateEvent): (HudPlayer, Int, Int) -> WidthComponent {
        val build = layout.conditions.build(reason)
        val map = groups.map {
            it.getComponent(reason)
        }
        return { hudPlayer, index, frame ->
            if (build(hudPlayer)) {
                if (index > map.lastIndex) {
                    EMPTY_WIDTH_COMPONENT
                } else {
                    val get = map[index](hudPlayer, frame)
                    get[when (layout.animation.type) {
                        AnimationType.LOOP -> frame % get.size
                        AnimationType.PLAY_ONCE -> frame.coerceAtMost(get.lastIndex)
                    }]
                }
            } else EMPTY_WIDTH_COMPONENT
        }
    }

    private inner class PopupLayoutGroup(pair: LocationGroup, val array: JsonArray) {
        val elements = layout.animation.location.map { location ->
            PopupElement(pair, array, location)
        }
        fun getComponent(reason: UpdateEvent): (HudPlayer, Int) -> List<WidthComponent> {
            val map = elements.map {
                it.getComponent(reason)
            }
            return { p, f ->
                map.map {
                    it(p, f)
                }
            }
        }
    }
    private inner class PopupElement(pair: LocationGroup, val array: JsonArray, location: PixelLocation) {
        private val elementGui = pair.gui + parent.gui + globalLocation
        private val elementPixel = globalPixel + location

        fun getComponent(reason: UpdateEvent): (HudPlayer, Int) -> WidthComponent {
            val imageProcessing = image.map {
                it.getComponent(reason)
            }
            val textProcessing = texts.map {
                it.getText(reason)
            }
            val headProcessing = heads.map {
                it.getHead(reason)
            }
            return { hudPlayer, frame ->
                LayoutComponentContainer(layout.offset, layout.align, max)
                    .append(imageProcessing.map {
                        it(hudPlayer, frame)
                    })
                    .append(textProcessing.map {
                        it(hudPlayer)
                    })
                    .append(headProcessing.map {
                        it(hudPlayer)
                    })
                    .build()
            }
        }

        val image = layout.image.map { target ->
            val hudImage = target.image
            val pixel = elementPixel + pair.pixel + target.location
            val imageShader = HudShader(
                elementGui,
                target.renderScale,
                target.layer,
                target.outline,
                pixel.opacity,
                target.property
            )
            val list = ArrayList<PixelComponent>()

            if (hudImage.listener != null) list.add(EMPTY_PIXEL_COMPONENT)
            if (hudImage.image.size > 1) hudImage.image.forEach {
                val fileName = "$NAME_SPACE_ENCODED:${it.name}"

                val height = Math.round(it.image.image.height * target.scale).toInt()
                val scale = height.toDouble() / it.image.image.height
                val xOffset = Math.round(it.image.xOffset * scale).toInt()
                val ascent = pixel.y
                val shaderGroup = ShaderGroup(imageShader, fileName, target.scale, ascent)

                val component = ImageManager.getImage(shaderGroup) ?: run {
                    val char = parent.newChar()
                    HudImpl.createBit(imageShader, ascent) { y ->
                        array.add(jsonObjectOf(
                            "type" to "bitmap",
                            "file" to fileName,
                            "ascent" to y,
                            "height" to height,
                            "chars" to jsonArrayOf(char)
                        ))
                    }
                    val xWidth = (it.image.image.width.toDouble() * scale).roundToInt()
                    val comp = WidthComponent(Component.text().content(char).font(parent.imageKey), xWidth) + NEGATIVE_ONE_SPACE_COMPONENT
                    ImageManager.setImage(shaderGroup, comp)
                    comp
                }

                list.add(component.toPixelComponent(pixel.x + xOffset))
            } else hudImage.image[0].let {
                val char = parent.newChar()
                HudImpl.createBit(imageShader, pixel.y) { y ->
                    array.add(jsonObjectOf(
                        "type" to "bitmap",
                        "file" to "$NAME_SPACE_ENCODED:${it.name}",
                        "ascent" to y,
                        "height" to (it.image.image.height * target.scale).roundToInt(),
                        "chars" to jsonArrayOf(char)
                    ))
                }
                val comp = WidthComponent(Component.text().content(char).font(parent.imageKey), Math.round(it.image.image.width.toDouble() * target.scale).toInt()) + NEGATIVE_ONE_SPACE_COMPONENT
                list.add(comp.toPixelComponent(pixel.x))
            }

            ImageRenderer(
                hudImage,
                target.color,
                target.space,
                target.stack,
                target.maxStack,
                list,
                target.follow,
                target.cancelIfFollowerNotExists,
                hudImage.conditions.and(target.conditions)
            )
        }

        private val max = image.maxOfOrNull {
            it.max()
        } ?: 0

        val texts = layout.text.map { textLayout ->
            val pixel = elementPixel + pair.pixel + textLayout.location
            val textShader = HudShader(
                elementGui,
                textLayout.renderScale,
                textLayout.layer,
                textLayout.outline,
                pixel.opacity,
                textLayout.property
            )
            val group = ShaderGroup(textShader, textLayout.text.name, textLayout.scale, pixel.y)
            val imageCodepointMap = textLayout.text.imageCharWidth.map {
                it.value.name to it.key
            }.toMap()
            val index = ++textIndex
            val keys = (0..<textLayout.line).map { lineIndex ->
                TextManagerImpl.getKey(group) ?: run {
                    val array = textLayout.startJson()
                    HudImpl.createBit(textShader, pixel.y + lineIndex * textLayout.lineWidth) { y ->
                        textLayout.text.array.forEach {
                            array.add(
                                jsonObjectOf(
                                    "type" to "bitmap",
                                    "file" to "$NAME_SPACE_ENCODED:${it.file}",
                                    "ascent" to y,
                                    "height" to (it.height * textLayout.scale).roundToInt(),
                                    "chars" to it.chars
                                )
                            )
                        }
                    }
                    val imageMap = HashMap<String, WidthComponent>()
                    val textEncoded = "popup_${parent.name}_text_${index}".encodeKey()
                    val key = createAdventureKey(textEncoded)
                    var imageTextIndex = TEXT_IMAGE_START_CODEPOINT + textLayout.text.imageCharWidth.size
                    textLayout.text.imageCharWidth.forEach {
                        val height = (it.value.height.toDouble() * textLayout.scale).roundToInt()
                        HudImpl.createBit(textShader, pixel.y + it.value.location.y + lineIndex * textLayout.lineWidth) { y ->
                            array.add(
                                jsonObjectOf(
                                    "type" to "bitmap",
                                    "file" to "$NAME_SPACE_ENCODED:${"glyph_${it.value.name}".encodeKey()}.png",
                                    "ascent" to y,
                                    "height" to height,
                                    "chars" to jsonArrayOf(it.key.parseChar())
                                )
                            )
                        }
                    }
                    if (ConfigManagerImpl.loadMinecraftDefaultTextures) {
                        HudImpl.createBit(textShader, pixel.y + textLayout.emojiLocation.y + lineIndex * textLayout.lineWidth) { y ->
                            MinecraftManager.applyAll(array, y, textLayout.emojiScale, key) {
                                ++imageTextIndex
                            }.forEach {
                                imageMap[it.key] = textLayout.emojiLocation.x.toSpaceComponent() + it.value
                            }
                        }
                    }
                    PackGenerator.addTask(file + "$textEncoded.json") {
                        jsonObjectOf("providers" to array).toByteArray()
                    }
                    key.apply {
                        TextManagerImpl.setKey(group, this)
                    }
                }
            }
            TextRenderer(
                textLayout.text.charWidth,
                textLayout.text.imageCharWidth,
                textLayout.color,
                HudTextData(
                    keys,
                    imageCodepointMap,
                    textLayout.splitWidth
                ),
                textLayout.pattern,
                textLayout.align,
                textLayout.lineAlign,
                textLayout.scale,
                textLayout.emojiScale,
                pixel.x,
                textLayout.numberEquation,
                textLayout.numberFormat,
                textLayout.disableNumberFormat,
                textLayout.follow,
                textLayout.cancelIfFollowerNotExists,
                textLayout.useLegacyFormat,
                textLayout.legacySerializer,
                textLayout.space,
                textLayout.conditions and textLayout.text.conditions
            )
        }

        val heads = layout.head.map { headLayout ->
            val pixel = elementPixel + pair.pixel + headLayout.location
            val shader = HudShader(
                elementGui,
                headLayout.renderScale,
                headLayout.layer,
                headLayout.outline,
                pixel.opacity,
                headLayout.property
            )
            val hair = when (headLayout.type) {
                STANDARD -> shader
                FANCY -> HudShader(
                    elementGui,
                    headLayout.renderScale * 1.125,
                    headLayout.layer + 1,
                    true,
                    pixel.opacity,
                    headLayout.property
                )
            }
            HeadRenderer(
                parent.getOrCreateSpace(-1),
                parent.getOrCreateSpace(-(headLayout.head.pixel * 8 + 1)),
                parent.getOrCreateSpace(-(headLayout.head.pixel + 1)),
                (0..7).map { i ->
                    val encode = "pixel_${headLayout.head.pixel}".encodeKey()
                    val fileName = "$NAME_SPACE_ENCODED:$encode.png"
                    val char = parent.newChar()
                    val ascent = pixel.y + i * headLayout.head.pixel
                    val height = headLayout.head.pixel
                    val shaderGroup = ShaderGroup(shader, fileName, 1.0, ascent)

                    val mainChar = PlayerHeadManager.getHead(shaderGroup) ?: run {
                        HudImpl.createBit(shader, ascent) { y ->
                            array.add(jsonObjectOf(
                                "type" to "bitmap",
                                "file" to fileName,
                                "ascent" to y,
                                "height" to height,
                                "chars" to jsonArrayOf(char)
                            ))
                        }
                        PlayerHeadManager.setHead(shaderGroup, char)
                        char
                    }
                    when (headLayout.type) {
                        STANDARD -> HeadKey(mainChar, mainChar)
                        FANCY -> {
                            val hairShaderGroup = ShaderGroup(hair, fileName, 1.0, ascent - headLayout.head.pixel)
                            HeadKey(
                                mainChar,
                                PlayerHeadManager.getHead(hairShaderGroup) ?: run {
                                    val twoChar = parent.newChar()
                                    HudImpl.createBit(hair, ascent - headLayout.head.pixel) { y ->
                                        array.add(jsonObjectOf(
                                            "type" to "bitmap",
                                            "file" to fileName,
                                            "ascent" to y,
                                            "height" to height,
                                            "chars" to jsonArrayOf(twoChar)
                                        ))
                                    }
                                    PlayerHeadManager.setHead(hairShaderGroup, twoChar)
                                    twoChar
                                }
                            )
                        }
                    }
                },
                parent.imageKey,
                headLayout.head.pixel * 8,
                pixel.x,
                headLayout.align,
                headLayout.type,
                headLayout.follow,
                headLayout.cancelIfFollowerNotExists,
                headLayout.conditions and headLayout.head.conditions
            )
        }
    }
}