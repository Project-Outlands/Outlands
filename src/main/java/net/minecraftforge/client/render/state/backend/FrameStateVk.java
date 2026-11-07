package net.minecraftforge.client.render.state.backend;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Frame state on the Vulkan backend: the pipeline key, recorded rather than applied.
 *
 * <p><b>Why recording is the whole job.</b> In GL these calls change driver state that the next draw picks up
 * implicitly. In Vulkan almost all of it is baked into the pipeline object, so the useful form is "what is the
 * state now", read when a draw needs a pipeline. That is what this class keeps. Nothing here touches the device,
 * which is why it works during startup when no frame is being recorded.</p>
 *
 * <p><b>Unknown values are rejected, not stored.</b> A blend factor this class does not recognise would otherwise
 * become a pipeline nobody builds, and the failure would appear as a wrong picture rather than as an error. The
 * message names the value so the next run reports exactly what is missing.</p>
 *
 * <p><b>Matrices.</b> GL has one stack per matrix mode; this keeps the same shape because vanilla pushes and pops
 * with respect to the mode it selected earlier. Values are column-major 4x4, the layout both GL and a push constant
 * use.</p>
 */
final class FrameStateVk implements FrameState {

    private final Set<Capability> enabled = EnumSet.noneOf(Capability.class);

    private int depthComparison = 513;      // GL_LESS, the GL default
    private int alphaComparison = 519;      // GL_ALWAYS, the GL default
    private float alphaReference = 0.0F;
    private int shadeModel = 7425;          // GL_SMOOTH
    private int cullFace = 1029;            // GL_BACK
    private boolean[] colorMask = {true, true, true, true};
    private boolean depthMask = true;
    private float[] vertexColor = {1.0F, 1.0F, 1.0F, 1.0F};
    private int blendSource = 1;            // GL_ONE
    private int blendDestination = 0;       // GL_ZERO
    private int[] blendSeparate;
    private int blendEquation = 32774;      // GL_FUNC_ADD
    private int logicOp = 5379;             // GL_COPY
    private float lineWidth = 1.0F;
    private int polygonModeFace = 1032;     // GL_FRONT_AND_BACK
    private int polygonModeMode = 6914;     // GL_FILL
    private float polygonOffsetFactor;
    private float polygonOffsetUnits;
    private int[] viewport = {0, 0, 0, 0};
    private int[] scissor = {0, 0, 0, 0};
    private float[] clearColor = {0.0F, 0.0F, 0.0F, 0.0F};
    private double clearDepth = 1.0;
    private int clearMask;

    private int matrixMode = 5888;          // GL_MODELVIEW
    private static final int MODELVIEW_MATRIX = 2982;      // GL_MODELVIEW_MATRIX, a readback target
    private static final int PROJECTION_MATRIX = 2983;     // GL_PROJECTION_MATRIX, a readback target
    private static final int TEXTURE_MATRIX = 2984;        // GL_TEXTURE_MATRIX, a readback target
    private static final int MODELVIEW = 5888;             // the matrix mode each target reads
    private static final int PROJECTION = 5889;
    private static final int TEXTURE = 5890;
    /** One stack per GL matrix mode constant. */
    private final Map<Integer, Deque<Matrix4f>> matrixStacks = new HashMap<>();
    private final Map<Integer, Matrix4f> currentMatrix = new HashMap<>();
    private int attributeDepth;

    /** Fog state, at the defaults GL documents: GL_EXP at density 1, no colour, the linear range 0..1. */
    private int fogMode = 2048;
    private float[] fogColor = {0.0F, 0.0F, 0.0F, 0.0F};
    private float fogDensity = 1.0F;
    private float fogStart = 0.0F;
    private float fogEnd = 1.0F;
    private static final int FOG_DENSITY = 2914;   // GL_FOG_DENSITY
    private static final int FOG_START = 2915;     // GL_FOG_START
    private static final int FOG_END = 2916;       // GL_FOG_END
    private static final int FOG_COLOR = 2918;     // GL_FOG_COLOR

    @Override
    public void capability(Capability capability, boolean enable) {
        if (enable) {
            enabled.add(capability);
        } else {
            enabled.remove(capability);
        }
    }

    @Override
    public void depthFunc(int glComparison) {
        depthComparison = comparison(glComparison);
    }

    @Override
    public void alphaFunc(int glComparison, float reference) {
        alphaComparison = comparison(glComparison);
        alphaReference = reference;
    }

    @Override
    public boolean alphaTestEnabled() {
        return enabled.contains(Capability.ALPHA_TEST);
    }

    @Override
    public float alphaReference() {
        return alphaReference;
    }

    @Override
    public int alphaComparison() {
        return alphaComparison;
    }

    @Override
    public void fogMode(int glMode) {
        fogMode = switch (glMode) {
            case 2048, 2049, 9729 -> glMode;   // GL_EXP, GL_EXP2, GL_LINEAR
            default -> throw unsupported("fog mode", glMode);
        };
    }

    @Override
    public void fog(int glPname, float value) {
        switch (glPname) {
            case FOG_DENSITY -> fogDensity = value;
            case FOG_START -> fogStart = value;
            case FOG_END -> fogEnd = value;
            default -> throw FrameStates.unimplemented("fog (glFogf " + glPname + ")",
                    "GL_FOG_DENSITY, GL_FOG_START or GL_FOG_END, the scalar fog values vanilla sets");
        }
    }

    @Override
    public void fog(int glPname, FloatBuffer param) {
        if (glPname != FOG_COLOR) {
            throw FrameStates.unimplemented("fog (glFog " + glPname + ")",
                    "GL_FOG_COLOR, the only buffer-valued fog setting vanilla uses");
        }
        FloatBuffer view = param.duplicate();
        view.rewind();
        if (view.remaining() < 4) {
            throw new IllegalArgumentException("a fog colour needs 4 floats, the buffer has " + view.remaining());
        }
        fogColor = new float[] {view.get(), view.get(), view.get(), view.get()};
        // The term itself is not computed yet - see the note on these methods in FrameState.
        FrameStates.reportSkippedOnce("fog", "world geometry is not faded towards the sky colour with distance");
    }

    @Override
    public void shadeModel(int glModel) {
        shadeModel = switch (glModel) {
            case 7424, 7425 -> glModel;   // GL_FLAT, GL_SMOOTH
            default -> throw unsupported("shade model", glModel);
        };
    }

    @Override
    public void cullFace(int glFace) {
        cullFace = switch (glFace) {
            case 1028, 1029, 1032 -> glFace;   // GL_FRONT, GL_BACK, GL_FRONT_AND_BACK
            default -> throw unsupported("cull face", glFace);
        };
    }

    @Override
    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        colorMask = new boolean[] {red, green, blue, alpha};
    }

    @Override
    public boolean[] colorMask() {
        return colorMask.clone();
    }

    @Override
    public boolean isEnabled(Capability capability) {
        return enabled.contains(capability);
    }

    @Override
    public float[] currentColor() {
        return vertexColor.clone();
    }

    @Override
    public int[] viewport() {
        return viewport.clone();
    }

    @Override
    public boolean depthWriteEnabled() {
        return depthMask;
    }

    @Override
    public org.joml.Matrix4fc projection() {
        return matrix(5889);
    }

    @Override
    public org.joml.Matrix4fc modelView() {
        return matrix(5888);
    }

    @Override
    public void depthMask(boolean enabled) {
        depthMask = enabled;
    }

    @Override
    public void color(float red, float green, float blue, float alpha) {
        vertexColor = new float[] {red, green, blue, alpha};
    }

    @Override
    public void blendFunc(int glSourceFactor, int glDestinationFactor) {
        blendSource = blendFactor(glSourceFactor);
        blendDestination = blendFactor(glDestinationFactor);
        blendSeparate = null;
    }

    @Override
    public void blendFuncSeparate(int glSourceRgb, int glDestinationRgb, int glSourceAlpha, int glDestinationAlpha) {
        blendSeparate = new int[] {
                blendFactor(glSourceRgb), blendFactor(glDestinationRgb),
                blendFactor(glSourceAlpha), blendFactor(glDestinationAlpha)};
    }

    @Override
    public int blendSource() {
        return blendSource;
    }

    @Override
    public int blendDestination() {
        return blendDestination;
    }

    @Override
    public int[] blendFactors() {
        return blendSeparate == null ? null : blendSeparate.clone();
    }

    @Override
    public void blendEquation(int glMode) {
        blendEquation = switch (glMode) {
            case 32774, 32775, 32776, 32777, 32778, 32779 -> glMode;   // ADD, MIN, MAX, SUBTRACT, REVERSE_SUBTRACT
            default -> throw unsupported("blend equation", glMode);
        };
    }

    @Override
    public void logicOp(int glOpcode) {
        logicOp = switch (glOpcode) {
            case 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383,
                 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391 -> glOpcode;
            default -> throw unsupported("logic op", glOpcode);
        };
    }

    @Override
    public void lineWidth(float width) {
        lineWidth = width;
    }

    @Override
    public void polygonMode(int glFace, int glMode) {
        polygonModeFace = glFace;
        polygonModeMode = switch (glMode) {
            case 6912, 6913, 6914 -> glMode;   // GL_POINT, GL_LINE, GL_FILL
            default -> throw unsupported("polygon mode", glMode);
        };
    }

    @Override
    public void polygonOffset(float factor, float units) {
        polygonOffsetFactor = factor;
        polygonOffsetUnits = units;
    }

    @Override
    public void viewport(int x, int y, int width, int height) {
        viewport = new int[] {x, y, width, height};
    }

    @Override
    public void scissor(int x, int y, int width, int height) {
        scissor = new int[] {x, y, width, height};
    }

    @Override
    public int[] scissorBox() {
        return scissor.clone();
    }

    @Override
    public void clearColor(float red, float green, float blue, float alpha) {
        clearColor = new float[] {red, green, blue, alpha};
        // The frame asks for one clear colour, so the last value set here is the one that reaches it.
        net.minecraftforge.client.render.target.FrameRecording recording =
                net.minecraftforge.client.render.target.FrameRecordings.current();
        if (recording != null) {
            recording.setClearColor(red, green, blue, alpha);
        }
    }

    @Override
    public void clearDepth(double depth) {
        clearDepth = depth;
        net.minecraftforge.client.render.target.FrameRecording recording =
                net.minecraftforge.client.render.target.FrameRecordings.current();
        if (recording != null) {
            recording.setClearDepth(depth);
        }
    }

    @Override
    public void clear(int glMask) {
        clearMask = glMask;
        net.minecraftforge.client.render.target.FrameRecording recording =
                net.minecraftforge.client.render.target.FrameRecordings.current();
        if (recording != null) {
            recording.requestClear(glMask);
        }
    }

    @Override
    public void matrixMode(int glMode) {
        matrixMode = switch (glMode) {
            case 5888, 5889, 5890 -> glMode;   // GL_MODELVIEW, GL_PROJECTION, GL_TEXTURE
            default -> throw unsupported("matrix mode", glMode);
        };
    }

    @Override
    public void loadIdentity() {
        current().identity();
    }

    @Override
    public void pushMatrix() {
        stack().push(new Matrix4f(current()));
    }

    @Override
    public void popMatrix() {
        Deque<Matrix4f> stack = stack();
        if (stack.isEmpty()) {
            throw new IllegalStateException("popMatrix without a matching pushMatrix in mode " + matrixMode);
        }
        currentMatrix.put(matrixMode, stack.pop());
    }

    @Override
    public void multiply(FloatBuffer columnMajor16) {
        // Copied through a float[] rather than handed to Matrix4f.set(FloatBuffer).
        //
        // Reason: that method's Unsafe implementation takes the buffer's native address field and dereferences it
        // whenever the buffer's order is native - it only asserts isDirect() when joml.debug is enabled, which it is
        // not by default. On a heap FloatBuffer that field is 0, so the read happens at address 0 and the process
        // dies (measured: MemUtilInternalUnsafe.get(Matrix4f, int, FloatBuffer) -> Unsafe.getLongUnaligned). This is
        // not a JDK-version effect and not something a JOML upgrade is expected to fix; heap buffers reach here
        // because FloatBuffer.wrap is what vanilla and mods use.
        //
        // The copy also leaves the caller's buffer position alone, which matters because vanilla reuses these
        // buffers across draws.
        float[] values = new float[16];
        FloatBuffer view = columnMajor16.duplicate();
        view.rewind();
        if (view.remaining() < 16) {
            throw new IllegalArgumentException("a 4x4 matrix needs 16 floats, the buffer has " + view.remaining());
        }
        view.get(values);
        // JOML's storage is column-major, the same layout glMultMatrixf reads, so no transpose happens here.
        current().mul(new Matrix4f().set(values));
    }

    @Override
    public void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        current().ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
    }

    @Override
    public void translate(double x, double y, double z) {
        current().translate((float) x, (float) y, (float) z);
    }

    @Override
    public void rotate(float angleDegrees, float x, float y, float z) {
        // glRotatef takes degrees; JOML takes radians. This is the one conversion on the matrix path.
        current().rotate((float) Math.toRadians(angleDegrees), x, y, z);
    }

    @Override
    public void scale(float x, float y, float z) {
        current().scale(x, y, z);
    }

    @Override
    public void scale(double x, double y, double z) {
        // Narrowed at entry, not lost: GL's own matrix stack is float32 (GLfloat matrices), so glScaled accumulates
        // into a float matrix too. Matching that here keeps the result identical to the GL path rather than
        // accidentally more precise.
        current().scale((float) x, (float) y, (float) z);
    }

    @Override
    public void readMatrix(int glMatrixTarget, FloatBuffer out) {
        Matrix4fc source = switch (glMatrixTarget) {
            case MODELVIEW_MATRIX -> matrix(MODELVIEW);
            case PROJECTION_MATRIX -> matrix(PROJECTION);
            case TEXTURE_MATRIX -> matrix(TEXTURE);
            default -> throw FrameStates.unimplemented("state readback (getFloat " + glMatrixTarget + ")",
                    "a matrix target: GL_MODELVIEW_MATRIX, GL_PROJECTION_MATRIX or GL_TEXTURE_MATRIX, which is"
                            + " everything vanilla reads back");
        };
        // Read through get(column, row) rather than Matrix4fc.get(FloatBuffer) for the reason spelled out on
        // multiply(): that overload's Unsafe path trusts the buffer's native address field and dereferences it even
        // for a heap buffer, so it would kill the process on exactly the buffers vanilla and mods build with wrap().
        float[] values = new float[16];
        for (int i = 0; i < 16; i++) {
            values[i] = source.get(i >> 2, i & 3);   // column-major, the layout glGetFloatv writes
        }
        FloatBuffer view = out.duplicate();
        int at = view.position();
        if (view.limit() - at < 16) {
            throw new IllegalArgumentException("a 4x4 matrix needs 16 floats from position " + at
                    + ", the buffer has " + (view.limit() - at));
        }
        // Written at the caller's position without moving it, which is glGetFloatv's contract: it fills the storage
        // the caller points at. Rewinding instead would write over data a caller deliberately offset past, and
        // advancing would consume a buffer vanilla reuses every frame.
        for (int i = 0; i < 16; i++) {
            view.put(at + i, values[i]);
        }
    }

    @Override
    public void pushAttributes() {
        attributeDepth++;
    }

    @Override
    public void popAttributes() {
        if (attributeDepth == 0) {
            throw new IllegalStateException("popAttrib without a matching pushAttrib");
        }
        attributeDepth--;
    }

    private Matrix4f current() {
        return currentMatrix.computeIfAbsent(matrixMode, mode -> new Matrix4f());
    }

    private Deque<Matrix4f> stack() {
        return matrixStacks.computeIfAbsent(matrixMode, mode -> new ArrayDeque<>());
    }

    /** The matrix of one mode, for a draw that needs to hand it to a push constant. */
    public Matrix4fc matrix(int glMode) {
        return currentMatrix.getOrDefault(glMode, new Matrix4f());
    }

    private static int comparison(int glComparison) {
        return switch (glComparison) {
            case 512, 513, 514, 515, 516, 517, 518, 519 -> glComparison;
            default -> throw new IllegalArgumentException("not a GL comparison function: " + glComparison);
        };
    }

    private static int blendFactor(int glFactor) {
        return switch (glFactor) {
            case 0, 1, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777 -> glFactor;
            default -> throw new IllegalArgumentException("not a GL blend factor: " + glFactor);
        };
    }

    private static IllegalArgumentException unsupported(String what, int value) {
        return new IllegalArgumentException("unsupported " + what + ": " + value);
    }
}
