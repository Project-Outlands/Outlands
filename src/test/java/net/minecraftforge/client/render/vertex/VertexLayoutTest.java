package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code VertexFormat} to attribute-list translation.
 *
 * <p>Every assertion here is about something that would silently produce a wrong picture: an offset that is one
 * byte early because padding was skipped, a colour read as non-normalised, a lightmap read as normalised. None of
 * those throw; they draw.</p>
 */
class VertexLayoutTest {

    private static VertexLayout.Attribute attribute(VertexLayout layout, VertexFormatElement.EnumUsage usage) {
        return layout.attributes().stream().filter(a -> a.usage() == usage).findFirst()
                .orElseThrow(() -> new AssertionError("no " + usage + " attribute in " + layout.attributes()));
    }

    @Test
    void blockIsTheLayoutTheTerrainPipelineAlreadyAssumes() {
        VertexLayout layout = VertexLayout.of(DefaultVertexFormats.BLOCK);

        assertEquals(28, layout.stride(), "the stride the terrain path hardcodes");
        assertEquals(List.of(0, 12, 16, 24), layout.attributes().stream().map(VertexLayout.Attribute::offset).toList(),
                "offsets the terrain path hardcodes");

        // The two integer attributes, which are where the normalisation rule bites.
        assertEquals("R8G8B8A8_UNORM", attribute(layout, VertexFormatElement.EnumUsage.COLOR).vulkanFormat());

        // Two UV elements; the lightmap is the second one. Selecting by usage alone would find the texture
        // coordinate and miss the thing that actually has an unusual format.
        List<VertexLayout.Attribute> uvs = layout.attributes().stream()
                .filter(a -> a.usage() == VertexFormatElement.EnumUsage.UV).toList();
        assertEquals("R32G32_SFLOAT", uvs.get(0).vulkanFormat(), "TEX_2F");
        assertEquals("R16G16_SINT", uvs.get(1).vulkanFormat(), "TEX_2S, the lightmap");
    }

    @Test
    void colourAndNormalAreNormalisedButPositionAndUvAreNot() {
        // Same element type, different normalisation, decided by the attribute kind. This is GL's rule: it is
        // what glColorPointer and glNormalPointer do with integer data, and what glVertexPointer does not.
        VertexLayout item = VertexLayout.of(DefaultVertexFormats.ITEM);

        VertexLayout.Attribute position = attribute(item, VertexFormatElement.EnumUsage.POSITION);
        VertexLayout.Attribute normal = attribute(item, VertexFormatElement.EnumUsage.NORMAL);
        VertexLayout.Attribute colour = attribute(item, VertexFormatElement.EnumUsage.COLOR);

        assertFalse(position.normalized(), "position is never normalised");
        assertTrue(normal.normalized(), "GL_BYTE normals are normalised");
        assertTrue(colour.normalized(), "GL_UNSIGNED_BYTE colours are normalised");
        assertEquals("R8G8B8A8_UNORM", colour.vulkanFormat());
        // Three bytes have no Vulkan format of their own: it reads four, taking the fourth from PADDING_1B.
        assertEquals("R8G8B8A8_SNORM", normal.vulkanFormat());
    }

    @Test
    void paddingOccupiesItsByteButProducesNoAttribute() {
        VertexLayout layout = VertexLayout.of(DefaultVertexFormats.ITEM);

        VertexLayout.Attribute padding = attribute(layout, VertexFormatElement.EnumUsage.PADDING);
        assertFalse(padding.isAttribute(), "padding is not fed to a shader");

        // The point of padding: the elements after it are offset by it. If it were dropped from the walk the
        // texture coordinate would land one byte early and every item would be textured wrong.
        List<VertexLayout.Attribute> shader = layout.shaderAttributes();
        assertEquals(4, shader.size(), "position, normal, colour, uv - padding excluded");
        assertTrue(layout.attributes().size() > shader.size());
    }

    @Test
    void offsetsAreTheFormatsOwnOffsets() {
        // Not re-derived: VertexFormat already computes them, and a second implementation would be a second
        // source of truth for the same number.
        for (VertexFormat format : List.of(DefaultVertexFormats.POSITION, DefaultVertexFormats.POSITION_TEX,
                DefaultVertexFormats.POSITION_COLOR, DefaultVertexFormats.POSITION_TEX_COLOR,
                DefaultVertexFormats.POSITION_TEX_LMAP_COLOR, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP,
                DefaultVertexFormats.BLOCK, DefaultVertexFormats.ITEM)) {
            VertexLayout layout = VertexLayout.of(format);
            for (int i = 0; i < format.getElementCount(); i++) {
                assertEquals(format.getOffset(i), layout.attributes().get(i).offset(),
                        "element " + i + " of " + format);
            }
        }
    }

    @Test
    void uvIndicesAreCarriedSoTheSecondSetStaysDistinct() {
        VertexLayout layout = VertexLayout.of(DefaultVertexFormats.BLOCK);

        // BLOCK has two UV elements: TEX_2F at index 0 and TEX_2S at index 1. They are different attributes, and
        // a layout that collapsed them by usage alone would lose one.
        List<VertexLayout.Attribute> uvs = layout.attributes().stream()
                .filter(a -> a.usage() == VertexFormatElement.EnumUsage.UV).toList();
        assertEquals(2, uvs.size());
        assertEquals(0, uvs.get(0).uvIndex());
        assertEquals(1, uvs.get(1).uvIndex());
    }

    @Test
    void anUnknownTypeIsLoud() {
        // R16G16_SINT is the only signed non-normalised format named, on purpose: it is the lightmap, and nothing
        // else in vanilla uses one. Anything else has to be added deliberately rather than fall through.
        assertThrows(IllegalStateException.class, () -> new VertexLayout.Attribute(
                VertexFormatElement.EnumUsage.POSITION, 0, 0, 2, VertexFormatElement.EnumType.INT, false)
                .vulkanFormat());
    }
}
