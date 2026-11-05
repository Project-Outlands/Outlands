package net.minecraftforge.lwjgl.shim;

/**
 * What the ruleset chooser is allowed to look at.
 *
 * <p><b>Only facts that exist before the window does.</b> Rewriting happens while classes load, which is
 * before any GL or Vulkan context exists, so anything derived from a live context cannot be reported here
 * honestly. Fields are added only when a ruleset actually needs one: adding a method to this interface breaks
 * every existing implementation, so a speculative field is a cost paid by everyone for a benefit nobody has
 * asked for yet.</p>
 */
public interface ShimEnvironment {

    /**
     * The backend selected for this process, by id. Never null.
     *
     * <p>This is the selection, not a probe: it says which backend the process will use, and therefore which
     * ruleset makes sense, without claiming anything about what the hardware can do. A backend whose probe
     * passes and then fails to initialize still reports its id here — see
     * {@code net.minecraftforge.client.render.ShimGl} for why that is survivable.</p>
     */
    String backendId();
}
