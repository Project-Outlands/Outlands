package net.minecraftforge.fml.common;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraftforge.fml.relauncher.Side;

/**
 * A language adapter used only by tests, to have a provider class that can be resolved by name and is not the built-in
 * Java one.
 *
 * <p>Top level rather than nested so that {@code Class.forName} finds it without surprises, which is exactly what the
 * production code does with a declared provider.
 */
public class StubLanguageAdapter implements ILanguageAdapter {

    /** Counts instantiations, so a test can tell "resolved once" from "resolved per call". */
    public static int instantiations;

    public StubLanguageAdapter() {
        instantiations++;
    }

    @Override
    public Object getNewInstance(FMLModContainer container, Class<?> objectClass, ClassLoader classLoader,
                                 Method factoryMarkedAnnotation) {
        throw new UnsupportedOperationException("test stub");
    }

    @Override
    public boolean supportsStatics() {
        return true;
    }

    @Override
    public void setProxy(Field target, Class<?> proxyTarget, Object proxy) {
        throw new UnsupportedOperationException("test stub");
    }

    @Override
    public void setInternalProxies(ModContainer mod, Side side, ClassLoader loader) {
        throw new UnsupportedOperationException("test stub");
    }
}
