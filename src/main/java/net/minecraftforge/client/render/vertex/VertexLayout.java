package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@code VertexFormat} expressed as the attribute list a pipeline can be built from.
 *
 * <p><b>Why this exists.</b> GL learns the layout implicitly: whichever {@code glVertexPointer} /
 * {@code glColorPointer} calls ran last define it, and the draw reads client memory through them. Vulkan's vertex
 * input is pipeline state, so the layout has to be known before the pipeline exists. Vanilla already has that
 * knowledge - {@code VertexFormat} is exactly it - so this class translates rather than re-derives.</p>
 *
 * <p><b>The normalisation rule is the part that is easy to get wrong.</b> GL decides whether integer vertex data is
 * scaled to {@code 0..1} per <em>attribute kind</em>, not per type: {@code glColorPointer} with
 * {@code GL_UNSIGNED_BYTE} and {@code glNormalPointer} with {@code GL_BYTE} are always normalised, while
 * {@code glVertexPointer} and {@code glTexCoordPointer} with {@code GL_SHORT} are not. So a {@code COLOR_4UB} is
 * {@code R8G8B8A8_UNORM} and a {@code TEX_2S} is {@code R16G16_SINT}, even though both are integer elements. Getting
 * this backwards does not produce an error - it produces colours that are almost right and UVs that are entirely
 * wrong.</p>
 *
 * <p><b>Padding elements produce no attribute.</b> {@code PADDING_1B} occupies a byte in the stride and is skipped
 * by GL entirely; it must occupy the byte here too, or every attribute after it lands one byte early.</p>
 */
public final class VertexLayout {

    /**
     * One attribute, in the form a vertex input binding needs.
     *
     * @param usage   which attribute this is, in vanilla's terms
     * @param uvIndex which texture coordinate set, for {@code UV} only; zero otherwise
     * @param offset  byte offset within the vertex
     * @param count   number of components
     * @param type    the component type vanilla wrote
     * @param normalized whether the components are scaled to 0..1
     */
    public record Attribute(VertexFormatElement.EnumUsage usage, int uvIndex, int offset, int count,
            VertexFormatElement.EnumType type, boolean normalized) {

        /** Whether this attribute is fed to the shader at all. */
        public boolean isAttribute() {
            return usage != VertexFormatElement.EnumUsage.PADDING;
        }

        /**
         * The Vulkan format, named for diagnostics and for the pipeline builder to map.
         *
         * <p>Spelled out rather than derived from {@code (count, type, normalized)} because the combination that a
         * typo would produce is a valid format for some other attribute, and a name makes the mistake visible in a
         * test failure.</p>
         */
        public String vulkanFormat() {
            return switch (type) {
                case FLOAT -> switch (count) {
                    case 1 -> "R32_SFLOAT";
                    case 2 -> "R32G32_SFLOAT";
                    case 3 -> "R32G32B32_SFLOAT";
                    case 4 -> "R32G32B32A32_SFLOAT";
                    default -> unsupported();
                };
                case UBYTE -> normalized
                        ? switch (count) {
                            case 1 -> "R8_UNORM";
                            case 2 -> "R8G8_UNORM";
                            // Same reason as the normal: no 3-component 8-bit format exists.
                            case 3, 4 -> "R8G8B8A8_UNORM";
                            default -> unsupported();
                        }
                        : switch (count) {
                            case 4 -> "R8G8B8A8_UINT";
                            default -> unsupported();
                        };
                case BYTE -> normalized
                        ? switch (count) {
                            case 2 -> "R8G8_SNORM";
                            // Vulkan has no 3-component 8-bit format. A 3-byte normal is therefore read as four
                            // bytes with the fourth taken from whatever follows - which is exactly why vanilla
                            // declares NORMAL_3B next to PADDING_1B. The padding is part of the layout for this
                            // reason and not for alignment.
                            case 3, 4 -> "R8G8B8A8_SNORM";
                            default -> unsupported();
                        }
                        : unsupported();
                case USHORT -> normalized
                        ? switch (count) {
                            case 2 -> "R16G16_UNORM";
                            case 4 -> "R16G16B16A16_UNORM";
                            default -> unsupported();
                        }
                        : unsupported();
                case SHORT -> normalized
                        ? switch (count) {
                            case 2 -> "R16G16_SNORM";
                            case 4 -> "R16G16B16A16_SNORM";
                            default -> unsupported();
                        }
                        : switch (count) {
                            // Lightmap coordinates are the one signed non-normalised pair vanilla uses, and the
                            // shader divides by 65535 itself. Naming it here keeps that visible.
                            case 2 -> "R16G16_SINT";
                            default -> unsupported();
                        };
                case UINT, INT -> unsupported();
            };
        }

        private String unsupported() {
            throw new IllegalStateException("no Vulkan vertex format for " + count + " x " + type
                    + (normalized ? " normalized" : "") + " (" + usage + ")");
        }
    }

    private final VertexFormat source;
    private final int stride;
    private final List<Attribute> attributes;

    private VertexLayout(VertexFormat source, int stride, List<Attribute> attributes) {
        this.source = source;
        this.stride = stride;
        this.attributes = List.copyOf(attributes);
    }

    private static final Map<VertexFormat, VertexLayout> CACHE = new ConcurrentHashMap<>();

    /**
     * Translates a vanilla format.
     *
     * @throws IllegalArgumentException if the format declares the same attribute twice, which GL silently allows
     *         and Vulkan cannot express
     */
    public static VertexLayout of(VertexFormat format) {
        return CACHE.computeIfAbsent(format, VertexLayout::create);
    }

    private static VertexLayout create(VertexFormat format) {
        int stride = format.getSize();
        List<Attribute> attributes = new ArrayList<>(format.getElementCount());
        for (int i = 0; i < format.getElementCount(); i++) {
            VertexFormatElement element = format.getElement(i);
            boolean normalized = isNormalized(element);
            attributes.add(new Attribute(element.getUsage(), element.getIndex(), format.getOffset(i),
                    element.getElementCount(), element.getType(), normalized));
        }
        return new VertexLayout(format, stride, attributes);
    }

    /**
     * Whether GL would normalise this element's integer components.
     *
     * <p>Colour and normal are always normalised; position and texture coordinates never are. That is GL's rule,
     * not a choice made here - see the class comment.</p>
     */
    private static boolean isNormalized(VertexFormatElement element) {
        return switch (element.getUsage()) {
            case COLOR, NORMAL -> element.getType() != VertexFormatElement.EnumType.FLOAT;
            default -> false;
        };
    }

    /**
     * The format this was translated from.
     *
     * <p>Kept because Forge's vertex hooks are written against {@code VertexFormat}, not against a translated
     * layout: {@code ForgeHooksClient.preDraw(usage, format, element, stride, buffer)} indexes the format to find
     * the element it is being asked about. Replacing that call would drop the extension point mods use to supply
     * attributes vanilla does not have.</p>
     */
    public VertexFormat format() {
        return source;
    }

    /** Bytes per vertex. */
    public int stride() {
        return stride;
    }

    /** Every element, including padding, in declaration order. */
    public List<Attribute> attributes() {
        return attributes;
    }

    /** Only the elements a shader receives. */
    public List<Attribute> shaderAttributes() {
        return attributes.stream().filter(Attribute::isAttribute).toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VertexLayout that)) return false;
        return stride == that.stride && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return 31 * stride + attributes.hashCode();
    }
}
