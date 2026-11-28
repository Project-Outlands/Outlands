package net.minecraftforge.mixin;

import net.minecraftforge.fml.common.discovery.ModDiscoverer;
import net.minecraftforge.fml.common.discovery.ASMDataTable;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.service.MixinService;
import zone.rong.mixinbooter.*;

import java.util.*;

public class MixinHooks {

    public static String addMixinMetadataToCrashReport(Throwable throwable) {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        while (throwable != null) {
            if (throwable instanceof NoClassDefFoundError) {
                ClassInfo classInfo = ClassInfo.fromCache(throwable.getMessage());
                if (classInfo != null) {
                    classes.put(throwable.getMessage(), classInfo);
                }
            }
            StackTraceElement[] stacktrace = throwable.getStackTrace();
            for (StackTraceElement stackTraceElement : stacktrace) {
                String className = stackTraceElement.getClassName().replace('.', '/');
                if (classes.containsKey(className)) {
                    ClassInfo classInfo = ClassInfo.fromCache(className);
                    while (classInfo != null) {
                        classes.put(className, classInfo);
                        className = classInfo.getSuperName();
                        if (className == null || className.isEmpty() || "java/lang/Object".equals(className)) {
                            break;
                        }
                        classInfo = classInfo.getSuperClass();
                    }
                }
            }
            throwable = throwable.getCause();
        }
        if (classes.isEmpty()) {
            return "\nNo Mixin Metadata is found in the Stacktrace.\n";
        } else {
            StringBuilder mixinMetadataBuilder = new StringBuilder("\nMixins in Stacktrace:");
            boolean addedMetadata = false;
            for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
                addedMetadata |= findAndAddMixinMetadata(mixinMetadataBuilder, entry.getKey(), entry.getValue());
            }
            if (addedMetadata) {
                return mixinMetadataBuilder.toString();
            } else {
                return "\nNo Mixin Metadata is found in the Stacktrace.\n";
            }
        }
    }

    public static void loadMixinBooterEarlyMixins(List<CoreModManager.FMLPluginWrapper> loadPlugins) {
        ILogger logger = MixinService.getService().getLogger("mixin");
        Set<String> presentMods = ModDiscoverer.instance().presentMods();
        Set<IEarlyMixinLoader> queuedLoaders = new LinkedHashSet<>();
        Context context = new Context(null, presentMods);
        for (CoreModManager.FMLPluginWrapper plugin : loadPlugins) {
            IFMLLoadingPlugin thePlugin = plugin.coreModInstance;
            if (thePlugin instanceof IMixinConfigHijacker interceptor) {
                // Hijacking is deliberately not honoured. Stopping a configuration from loading so
                // that something else can load it is another layer of interception in a stack that
                // already has too many of them, and the configurations it targets load fine as they
                // are. The interface is kept so that coremods implementing it still construct, and
                // what it asks for is reported rather than acted on.
                for (String hijacked : interceptor.getHijackedMixinConfigs(context)) {
                    logger.warn("{} asked to hijack the mixin config {}, which this loader ignores; the config will load normally",
                            interceptor.getClass().getName(), hijacked);
                }
            }
            if (thePlugin instanceof IEarlyMixinLoader loader) {
                queuedLoaders.add(loader);
            }
        }
        for (IEarlyMixinLoader queuedLoader : queuedLoaders) {
            logger.info("Loading early loader {} for its mixins.", queuedLoader.getClass().getName());
            try {
                for (String mixinConfig : queuedLoader.getMixinConfigs()) {
                    context = new Context(mixinConfig, presentMods);
                    if (queuedLoader.shouldMixinConfigQueue(context)) {
                        logger.info("Adding [{}] mixin configuration.", mixinConfig);
                        Mixins.addConfiguration(mixinConfig);
                        queuedLoader.onMixinConfigQueued(context);
                    }
                }
            } catch (Throwable t) {
                logger.error("Failed to execute early loader [{}].", queuedLoader.getClass().getName(), t);
            }
        }
    }

    public static void loadMixinBooterLateMixins(ASMDataTable data) {
        ILogger logger = MixinService.getService().getLogger("mixin");

        // Gather ILateMixinLoaders
        Set<ASMDataTable.ASMData> interfaceData = data.getAll(ILateMixinLoader.class.getName().replace('.', '/'));
        Set<ILateMixinLoader> lateLoaders = new HashSet<>();

        // Instantiate all @MixinLoader annotated classes
        Set<ASMDataTable.ASMData> annotatedData = data.getAll(MixinLoader.class.getName());
        for (ASMDataTable.ASMData annotated : annotatedData) {
            try {
                Class<?> clazz = Class.forName(annotated.getClassName());
                logger.info("Loading annotated late loader [{}] for its mixins.", clazz.getName());
                Object instance = clazz.getConstructor().newInstance();
                if (instance instanceof ILateMixinLoader) {
                    lateLoaders.add((ILateMixinLoader) instance);
                }
            } catch (Throwable t) {
                throw new RuntimeException("Unexpected error.", t);
            }
        }

        // Instantiate all ILateMixinLoader implemented classes
        for (ASMDataTable.ASMData itf : interfaceData) {
            try {
                Class<?> clazz = Class.forName(itf.getClassName().replace('/', '.'));
                logger.info("Loading late loader [{}] for its mixins.", clazz.getName());
                lateLoaders.add((ILateMixinLoader) clazz.getConstructor().newInstance());
            } catch (Throwable t) {
                throw new RuntimeException("Unexpected error.", t);
            }
        }

        for (ILateMixinLoader lateLoader : lateLoaders) {
            try {
                for (String mixinConfig : lateLoader.getMixinConfigs()) {
                    Context context = new Context(mixinConfig, ModDiscoverer.instance().presentMods());
                    if (lateLoader.shouldMixinConfigQueue(context)) {
                        logger.info("Adding [{}] mixin configuration.", mixinConfig);
                        Mixins.addConfiguration(mixinConfig);
                        lateLoader.onMixinConfigQueued(context);
                    }
                }
            } catch (Throwable t) {
                logger.error("Failed to execute late loader [{}].", lateLoader.getClass().getName(), t);
            }
        }
    }

    private static boolean findAndAddMixinMetadata(StringBuilder mixinMetadataBuilder, String className, ClassInfo classInfo) {
        Set<IMixinInfo> mixinInfos = Mixins.getMixinsForClass(className);
        if (!mixinInfos.isEmpty()) {
            mixinMetadataBuilder.append("\n\t");
            mixinMetadataBuilder.append(className);
            mixinMetadataBuilder.append(':');
            for (IMixinInfo mixinInfo : mixinInfos) {
                mixinMetadataBuilder.append("\n\t\t");
                mixinMetadataBuilder.append(mixinInfo.getClassName());
                mixinMetadataBuilder.append(" (");
                mixinMetadataBuilder.append(mixinInfo.getConfig());
                mixinMetadataBuilder.append(") [");
                mixinMetadataBuilder.append(mixinInfo.getConfig().getCleanSourceId());
                mixinMetadataBuilder.append("]");
            }
            return true;
        }
        return false;
    }

}
