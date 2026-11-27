package net.axther.aftrhrs.gametest.econ;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection for game tests: the modules keep most of their state and steps package-private. */
final class R {
    private R() {
    }

    static Object get(Object target, String field) {
        try {
            Field f = find(target instanceof Class<?> c ? c : target.getClass(), field);
            return f.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Object target, String field, Object value) {
        try {
            Field f = find(target.getClass(), field);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Calls the first method with this name and argument count that accepts the arguments. */
    static Object call(Object target, String name, Object... args) {
        Class<?> c = target instanceof Class<?> k ? k : target.getClass();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                if (!fits(m.getParameterTypes(), args)) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(target instanceof Class<?> ? null : target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new IllegalStateException(e.getCause());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("no method " + name + "/" + args.length + " on " + c.getName());
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            Class<?> t = types[i].isPrimitive() ? box(types[i]) : types[i];
            if (!t.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> box(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == boolean.class) return Boolean.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        return Character.class;
    }

    private static Field find(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
