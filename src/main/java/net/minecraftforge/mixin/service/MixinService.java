package net.minecraftforge.mixin.service;

import net.minecraftforge.mixin.MixinModContainer;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.throwables.MixinException;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassMetadataProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The mixin service for this loader.
 *
 * <p>Everything which touches the class loader is supplied by the Foundation classes next to this
 * one; the service itself only decides what those are, reports the side and the compatibility
 * level, and answers {@link IClassMetadataProvider} from the load index so that a mixin whose
 * target archive is not on the class path yet can still be validated.</p>
 */
public class MixinService extends MixinServiceLaunchWrapper implements IClassMetadataProvider {

    private final FoundationClassProvider classProvider = new FoundationClassProvider();
    private final FoundationTransformerProvider transformerProvider = new FoundationTransformerProvider();
    private final FoundationClassTracker classTracker = new FoundationClassTracker();
    private final FoundationBytecodeProvider bytecodeProvider = new FoundationBytecodeProvider(this.transformerProvider, this.lock, this.classTracker);

    private boolean initialized;

    @Override
    public String getName() {
        return "mixin";
    }

    /**
     * The side agent reports which side the game is on; the service base class asks the agents it
     * finds in this list.
     */
    @Override
    public Collection<String> getPlatformAgents() {
        List<String> agents = new ArrayList<>(super.getPlatformAgents());
        agents.add(MixinSideAgent.class.getName());
        return agents;
    }

    /**
     * The base class rejects a boot source which does not share {@code Launch}'s own class loader.
     * That assumed the subsystem is defined by the parent loader and only the launcher is; here
     * Foundation keeps the launcher on the system class path and loads both the game and the whole
     * subsystem through the launch class loader, so the check rejected every correct setup.
     *
     * <p>What it is guarding is a second copy of the subsystem, so compare against this service's
     * loader instead: the environment which booted us must come from the same copy.</p>
     *
     * @param bootSource object which triggered initialisation
     */
    @Override
    public void checkEnv(Object bootSource) {
        if (bootSource.getClass().getClassLoader() != MixinService.class.getClassLoader()) {
            throw new MixinException("Attempted to init the mixin environment in the wrong classloader");
        }
    }

    /**
     * The base class decides between {@code PREINIT} and {@code DEFAULT} from how deep
     * {@code Launch.launch} sits in the stack, which is a fixture of vanilla's launcher. Report the
     * phase this launcher starts in instead; {@code EnvironmentStateTweaker} advances to
     * {@code DEFAULT} before the game starts.
     */
    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_25;
    }

    @Override
    public void init() {
        if (this.initialized) {
            return;
        }
        this.initialized = true;
        super.init();
        if (!isDevelopment()) {
            MixinEnvironment.getDefaultEnvironment().getRemappers().add(new FMLRemapper<>(FMLDeobfuscatingRemapper.INSTANCE));
        }
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleURI(MixinModContainer.location().toURI());
    }

    /**
     * @see org.spongepowered.asm.mixin.transformer.MixinConfig#onSelect()
     * @see net.minecraftforge.fml.common.discovery.ModDiscoverer#discoverMixinMods()
     */
    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return Collections.emptyList();
    }

    @Override
    public IClassProvider getClassProvider() {
        return this.classProvider;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this.bytecodeProvider;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return this.transformerProvider;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this.classTracker;
    }

    /**
     * Whether this launch is a development one, ie. whether the sources are deobfuscated. The
     * Gradle start classes set a system property pointing at the srg to mcp mapping which only
     * exists in a development environment.
     *
     * @return true if this is a development launch
     */
    private static boolean isDevelopment() {
        return System.getProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp") != null;
    }

    /*
     * IClassMetadataProvider: answered from the load index, which describes every archive the
     * loader has seen whether or not its classes are on the class path yet.
     */

    @Override
    public Header getClassHeader(String internalName) {
        net.minecraftforge.fml.common.discovery.cache.ClassScanRecord scan = MixinService.scan(internalName);
        return scan == null ? null
                : new Header(scan.internalName(), scan.classVersion(), scan.superName(), scan.access(), scan.interfaces());
    }

    @Override
    public Member[] getClassMembers(String internalName) {
        net.minecraftforge.fml.common.discovery.cache.MemberRecord[] scanned = MixinService.members(internalName);
        if (scanned == null) {
            return new Member[0];
        }
        Member[] members = new Member[scanned.length];
        for (int i = 0; i < scanned.length; i++) {
            net.minecraftforge.fml.common.discovery.cache.MemberRecord record = scanned[i];
            members[i] = new Member(record.name(), record.descriptor(), record.access(), record.method());
        }
        return members;
    }

    @Override
    public Detail getClassDetail(String internalName) {
        net.minecraftforge.fml.common.discovery.cache.ClassDetail detail = MixinService.detail(internalName);
        if (detail == null) {
            return null;
        }

        Inner inner = detail.inner() == null ? null
                : new Inner(detail.inner().outer(), detail.inner().innerName(), detail.inner().access());
        Enclosing enclosing = detail.enclosing() == null ? null
                : new Enclosing(detail.enclosing().owner(), detail.enclosing().name(), detail.enclosing().descriptor());

        java.util.Map<String, MemberDetail> members = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, net.minecraftforge.fml.common.discovery.cache.MemberDetail> entry : detail.members().entrySet()) {
            net.minecraftforge.fml.common.discovery.cache.MemberDetail record = entry.getValue();
            members.put(entry.getKey(), new MemberDetail(record.signature(), record.exceptions(), record.constantValue()));
        }

        return new Detail(detail.signature(), inner, enclosing, detail.sourceFile(), members);
    }

    private static net.minecraftforge.fml.common.discovery.cache.ClassScanRecord scan(String internalName) {
        net.minecraftforge.fml.common.discovery.index.LoadIndex index = net.minecraftforge.fml.common.discovery.index.LoadIndex.active();
        return index == null ? null : index.classInfo(internalName);
    }

    private static net.minecraftforge.fml.common.discovery.cache.MemberRecord[] members(String internalName) {
        net.minecraftforge.fml.common.discovery.index.LoadIndex index = net.minecraftforge.fml.common.discovery.index.LoadIndex.active();
        return index == null ? null : index.membersOf(internalName);
    }

    private static net.minecraftforge.fml.common.discovery.cache.ClassDetail detail(String internalName) {
        net.minecraftforge.fml.common.discovery.index.LoadIndex index = net.minecraftforge.fml.common.discovery.index.LoadIndex.active();
        return index == null ? null : index.detailOf(internalName);
    }

}
