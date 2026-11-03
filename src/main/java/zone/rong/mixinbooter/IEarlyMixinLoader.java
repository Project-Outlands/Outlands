package zone.rong.mixinbooter;

import java.util.List;

/**
 * Early mixins are defined as mixins that affects vanilla or forge classes.
 * Or technically, classes that can be queried via the current state of {@link net.minecraft.launchwrapper.LaunchClassLoader}
 *
 * If you want to add mixins that affect mods, use {@link ILateMixinLoader}
 *
 * Implement this in your {@link net.minecraftforge.fml.relauncher.IFMLLoadingPlugin}.
 * Return all early mixin configs you want MixinBooter to queue and send to Mixin library.
 *
 * @deprecated as of 11.0, the line of "early" and "late" mixin loading no longer is present. List your
 *             configurations in the {@code jar} section of your {@code mcmod.info} instead:
 *             {@code "mixins": [{"config": "mymod.mixins.json"}, {"config": "mymod.client.mixins.json", "side": "client"}]}
 *             - the loader registers them before anything is transformed, and unlike a manifest key the entry can say
 *             which side a configuration belongs to. The manifest {@code MixinConfigs} / {@code MixinConnector} keys
 *             still work for jars that cannot carry metadata; {@code MixinConnector} remains the way to choose
 *             configurations from code at startup.
 */
@Deprecated
public interface IEarlyMixinLoader {

    /**
     * @return mixin configurations to be queued and sent to Mixin library.
     */
    List<String> getMixinConfigs();

    /**
     * Runs when a mixin config is successfully queued and sent to Mixin library.
     *
     * @since 10.0
     * @param context current context of the loading process.
     * @return true if the mixinConfig should be queued, false if it should not.
     */
    default boolean shouldMixinConfigQueue(Context context) {
        return this.shouldMixinConfigQueue(context.mixinConfig());
    }

    /**
     * Runs when a mixin config is successfully queued and sent to Mixin library.
     *
     * @param mixinConfig mixin config name, queried via {@link IEarlyMixinLoader#getMixinConfigs()}.
     * @return true if the mixinConfig should be queued, false if it should not.
     */
    default boolean shouldMixinConfigQueue(String mixinConfig) {
        return true;
    }

    /**
     * Runs when a mixin config is successfully queued and sent to Mixin library.
     * @since 10.0
     * @param context current context of the loading process.
     */
    default void onMixinConfigQueued(Context context) {
        this.onMixinConfigQueued(context.mixinConfig());
    }

    /**
     * Runs when a mixin config is successfully queued and sent to Mixin library.
     * @param mixinConfig mixin config name, queried via {@link IEarlyMixinLoader#getMixinConfigs()}.
     */
    default void onMixinConfigQueued(String mixinConfig) { }

}
