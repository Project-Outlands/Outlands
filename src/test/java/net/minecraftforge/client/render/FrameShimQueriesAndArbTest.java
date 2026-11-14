package net.minecraftforge.client.render;


import net.minecraftforge.client.render.shader.GlShaders;
import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.state.backend.FrameState;
import net.minecraftforge.client.render.state.backend.FrameStates;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of "a mod asks instead of sets": the query enums Immersive Intelligence reads back, and the
 * ARB_shader_objects surface three mods build their shaders with.
 *
 * <p>Both are routed through the same facade the retarget rewrites call sites to, so the test drives
 * {@link ShimGl} rather than {@link FrameShim} directly: what matters is that a rewritten
 * {@code ARBShaderObjects.glCreateProgramObjectARB()} ends up in the backend's object model.</p>
 */
class FrameShimQueriesAndArbTest {

    private static final int GL_CULL_FACE = 2884;
    private static final int GL_CULL_FACE_MODE = 2885;
    private static final int GL_DEPTH_WRITEMASK = 2930;
    private static final int GL_MODELVIEW_MATRIX = 2982;
    private static final int GL_BACK = 1029;
    private static final int GL_FRONT_AND_BACK = 1032;

    @BeforeEach
    void selectANonGlBackend() {
        NonGlTestBackend.select();
    }

    @AfterEach
    void restoreTheRealGlPath() {
        NonGlTestBackend.restore();
    }

    private static FrameState state() {
        FrameState state = FrameStates.forNonGl();
        assertNotNull(state, "the test selected a non-GL backend");
        return state;
    }

    @Test
    void theBooleanQueryFollowsTheCapability() {
        ShimGl.glDisable(GL_CULL_FACE);
        assertFalse(ShimGl.glGetBoolean(GL_CULL_FACE), "disabled means off");

        ShimGl.glEnable(GL_CULL_FACE);
        assertTrue(ShimGl.glGetBoolean(GL_CULL_FACE), "and the same call answers the new value");
    }

    @Test
    void theBooleanQueryAnswersTheDepthWriteMask() {
        // Not a capability: glEnable cannot set it and glIsEnabled does not answer for it, which is why this is a
        // separate enum rather than a second call into the capability set.
        state().depthMask(false);
        assertFalse(ShimGl.glGetBoolean(GL_DEPTH_WRITEMASK));

        state().depthMask(true);
        assertTrue(ShimGl.glGetBoolean(GL_DEPTH_WRITEMASK));
    }

    @Test
    void theIntegerQueryAnswersTheCullFaceMode() {
        assertEquals(GL_BACK, ShimGl.glGetInteger(GL_CULL_FACE_MODE), "GL's initial value");

        state().cullFace(GL_FRONT_AND_BACK);
        assertEquals(GL_FRONT_AND_BACK, ShimGl.glGetInteger(GL_CULL_FACE_MODE),
                "a mod that saves the mode before changing it gets the mode back");
    }

    @Test
    void theFloatQueryWritesTheModelViewMatrix() {
        state().loadIdentity();
        state().translate(3.0F, 4.0F, 5.0F);

        FloatBuffer out = FloatBuffer.allocate(16);
        ShimGl.glGetFloat(GL_MODELVIEW_MATRIX, out);

        // Column-major, which is what glGetFloatv writes and what the matrix itself reads as.
        assertEquals(3.0F, out.get(12), 1e-6F, "the translation column");
        assertEquals(4.0F, out.get(13), 1e-6F);
        assertEquals(5.0F, out.get(14), 1e-6F);
        assertEquals(1.0F, out.get(0), 1e-6F, "and the identity part is untouched");
        assertEquals(0, out.position(), "the read leaves the buffer's position alone, like glGetFloatv");
    }

    @Test
    void arbNamesComeOutOfOneNamespace() {
        // The bug this guards: two counters hand out 1 for the first program and 1 for the first shader, and the
        // ARB calls cannot tell them apart - glAttachObjectARB(program, shader) would attach an object to itself.
        int program = ShimGl.glCreateProgramObjectARB();
        int shader = ShimGl.glCreateShaderObjectARB(GlShaders.VERTEX_SHADER);

        assertNotEquals(program, shader, "GL has one name space for programs and shaders");
    }

    @Test
    void theArbSequenceBuildsAProgramTheBackendCanUse() {
        int program = ShimGl.glCreateProgramObjectARB();
        int vertex = shader(GlShaders.VERTEX_SHADER, """
                #version 120
                attribute vec4 Position;
                uniform mat4 ProjMat;
                void main() { gl_Position = ProjMat * Position; }
                """);
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                uniform sampler2D DiffuseSampler;
                uniform vec4 ColorModulate;
                void main() { gl_FragColor = texture2D(DiffuseSampler, vec2(0.5)) * ColorModulate; }
                """);

        ShimGl.glAttachObjectARB(program, vertex);
        ShimGl.glAttachObjectARB(program, fragment);
        ShimGl.glLinkProgramARB(program);

        assertEquals(1, ShimGl.glGetObjectParameteriARB(program, GlShaders.LINK_STATUS),
                "linked: " + ShimGl.glGetInfoLogARB(program, 4096));
        assertNotEquals(-1, ShimGl.glGetUniformLocationARB(program, "ColorModulate"),
                "a uniform the program declares has a location");

        ShimGl.glUniform1fARB(ShimGl.glGetUniformLocationARB(program, "ColorModulate"), 0.25F);
        ShimGl.glUseProgramObjectARB(program);
        assertEquals(program, GlShaders.currentProgram(),
                "the ARB call moves the same current-program pointer the draw stream reads");
    }

    @Test
    void deletingAShaderLeavesTheProgramInPlace() {
        int program = ShimGl.glCreateProgramObjectARB();
        int vertex = shader(GlShaders.VERTEX_SHADER, """
                #version 120
                attribute vec4 Position;
                void main() { gl_Position = Position; }
                """);
        ShimGl.glAttachObjectARB(program, vertex);
        ShimGl.glLinkProgramARB(program);

        ShimGl.glDeleteObjectARB(vertex);

        assertNotNull(GlShaders.program(program), "a shader's name is not a program's name");
        assertEquals(1, ShimGl.glGetObjectParameteriARB(program, GlShaders.LINK_STATUS),
                "and deleting the stage does not unlink the program");
    }

    @Test
    void theCompileStatusIsAnsweredForShaders() {
        int broken = ShimGl.glCreateShaderObjectARB(GlShaders.FRAGMENT_SHADER);
        ShimGl.glShaderSourceARB(broken, "#version 120\nvoid main() { this is not glsl }");
        ShimGl.glCompileShaderARB(broken);

        assertEquals(0, ShimGl.glGetObjectParameteriARB(broken, GlShaders.COMPILE_STATUS),
                "a broken stage reports 0 rather than throwing");
        assertFalse(ShimGl.glGetInfoLogARB(broken, 4096).isEmpty(), "and it says why");
    }

    private static int shader(int type, String source) {
        int shader = ShimGl.glCreateShaderObjectARB(type);
        ShimGl.glShaderSourceARB(shader, source);
        ShimGl.glCompileShaderARB(shader);
        assertEquals(1, ShimGl.glGetObjectParameteriARB(shader, GlShaders.COMPILE_STATUS),
                "the stage compiles: " + ShimGl.glGetInfoLogARB(shader, 4096));
        return shader;
    }
}
