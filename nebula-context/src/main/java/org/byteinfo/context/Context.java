package org.byteinfo.context;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Scope;
import javax.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dependency Injection (JSR 330) Container
 */
public class Context {
	private final Map<Key<?>, Provider<?>> providers = new ConcurrentHashMap<>();
	private final Map<Key<?>, Object> singletons = new ConcurrentHashMap<>();
	private final Map<Class<?>, List<Object[]>> injectFields = new ConcurrentHashMap<>();
	private final Map<Class<?>, List<Method>> initMethods = new ConcurrentHashMap<>();

	/**
	 * Creates a new context.
	 *
	 * @param modules configuration objects with @Provides methods
	 */
	public Context(Object... modules) {
		this(Arrays.asList(modules));
	}

	/**
	 * Creates a new context.
	 *
	 * @param modules configuration objects with @Provides methods
	 */
	public Context(Iterable<Object> modules) {
		providers.put(Key.of(Context.class), () -> this);
		for (Object module : modules) {
			for (Method method : module.getClass().getDeclaredMethods()) {
				if (method.isAnnotationPresent(Provides.class)) {
					Key<?> key = Key.of(method.getReturnType(), getQualifier(method.getReturnType(), method.getDeclaredAnnotations()));
					if (providers.containsKey(key)) {
						throw new ContextException(key + ": Multiple @Provides methods are not supported.");
					}
					method.setAccessible(true);
					Provider<?>[] paramProviders = getParamProviders(key, method, null);
					providers.put(key, getScopedProvider(key, getScope(method), () -> {
						try {
							return method.invoke(module, getActualParams(paramProviders));
						} catch (ReflectiveOperationException e) {
							throw new ContextException(e);
						}
					}));
				}
			}
		}
	}

	/**
	 * Returns the provider used to obtain instances for the given type.
	 *
	 * @param type the specific type
	 * @return provider
	 */
	public <T> T instance(Class<T> type) {
		return getProvider(Key.of(type), null).get();
	}

	/**
	 * Returns the appropriate instance for the given key.
	 *
	 * @param key key for the specific type and qualifier
	 * @return instance
	 */
	public <T> T instance(Key<T> key) {
		return getProvider(key, null).get();
	}

	/**
	 * Returns the provider used to obtain instances for the given type.
	 *
	 * @param type the specific type
	 * @return provider
	 */
	public <T> Provider<T> provider(Class<T> type) {
		return getProvider(Key.of(type), null);
	}

	/**
	 * Returns the provider used to obtain instances for the given injection key.
	 *
	 * @param key key for the specific type and qualifier
	 * @return provider
	 */
	public <T> Provider<T> provider(Key<T> key) {
		return getProvider(key, null);
	}

	@SuppressWarnings("unchecked")
	private <T> Provider<T> getProvider(Key<T> key, Set<Key<?>> deps) {
		Provider<?> provider = providers.get(key);
		if (provider == null) {
			Constructor<?> con = getConstructor(key);
			Provider<?>[] paramProviders = getParamProviders(key, con, deps);
			Class<? extends Annotation> scope = getScope(key.type);
			provider = getScopedProvider(key, scope, () -> {
				try {
					Object object = con.newInstance(getActualParams(paramProviders));
					init(object, scope);
					return object;
				} catch (ReflectiveOperationException e) {
					throw new ContextException(e);
				}
			});
			providers.put(key, provider);
		}
		return (Provider<T>) provider;
	}

	private void init(Object target, Class<? extends Annotation> scope) {
		Class<?> clazz = target.getClass();
		boolean isSingleton = scope == Singleton.class;

		List<Object[]> fieldList = injectFields.get(clazz);
		if (fieldList == null) {
			fieldList = new ArrayList<>();
			for (Class<?> current = clazz; current != Object.class; current = current.getSuperclass()) {
				for (Field field : current.getDeclaredFields()) {
					if (field.isAnnotationPresent(Inject.class)) {
						field.setAccessible(true);
						Class<?> providerType = field.getType().equals(Provider.class) ? (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0] : null;
						fieldList.add(new Object[] {field, providerType != null, Key.of((Class<?>) (providerType != null ? providerType : field.getType()), getQualifier(field.getType(), field.getAnnotations()))});
					}
				}
			}
			if (!isSingleton) {
				injectFields.put(clazz, fieldList);
			}
		}
		for (Object[] fieldInfo : fieldList) {
			Field field = (Field) fieldInfo[0];
			Key<?> key = (Key<?>) fieldInfo[2];
			try {
				field.set(target, (boolean) fieldInfo[1] ? provider(key) : instance(key));
			} catch (ReflectiveOperationException e) {
				throw new ContextException(String.format("Can't inject field %s in %s", field.getName(), target.getClass().getName()), e);
			}
		}

		List<Method> methodList = initMethods.get(clazz);
		if (methodList == null) {
			methodList = new ArrayList<>();
			for (Class<?> current = clazz; current != Object.class; current = current.getSuperclass()) {
				for (Method method : current.getDeclaredMethods()) {
					if (method.isAnnotationPresent(PostConstruct.class)) {
						method.setAccessible(true);
						methodList.add(method);
					}
				}
			}
			Collections.reverse(methodList);
			if (!isSingleton) {
				initMethods.put(clazz, methodList);
			}
		}
		for (Method method : methodList) {
			try {
				method.invoke(target);
			} catch (ReflectiveOperationException e) {
				throw new ContextException(String.format("Can't call @PostConstruct method %s:%s", method.getClass(), method.getName()), e);
			}
		}
	}

	private Constructor<?> getConstructor(Key<?> key) {
		Constructor<?> inject = null;
		Constructor<?>[] constructors = key.type.getDeclaredConstructors();
		if (constructors.length == 1) {
			inject = constructors[0];
		} else {
			for (Constructor<?> constructor : constructors) {
				if (constructor.isAnnotationPresent(Inject.class)) {
					if (inject == null) {
						inject = constructor;
					} else {
						throw new ContextException(key + ": Multiple @Inject constructors are not supported.");
					}
				}
			}
		}
		if (inject == null) {
			throw new ContextException(key + ": Either @Inject constructor or @Provides method is expected.");
		}
		inject.setAccessible(true);
		return inject;
	}

	private Provider<?>[] getParamProviders(Key<?> key, Executable executable, Set<Key<?>> deps) {
		Class<?>[] classes = executable.getParameterTypes();
		Type[] types = executable.getGenericParameterTypes();
		Annotation[][] annotations = executable.getParameterAnnotations();
		if (deps == null) {
			deps = new LinkedHashSet<>();
		}
		deps.add(key);
		Provider<?>[] params = new Provider[classes.length];
		for (int i = 0; i < classes.length; i++) {
			Class<?> type = classes[i];
			Annotation qualifier = getQualifier(type, annotations[i]);
			if (type == Provider.class) {
				Class<?> actualType = (Class<?>) ((ParameterizedType) types[i]).getActualTypeArguments()[0];
				params[i] = () -> getProvider(Key.of(actualType, qualifier), null);
			} else {
				Key<?> newKey = Key.of(type, qualifier);
				if (deps.contains(newKey)) {
					List<String> list = new ArrayList<>(deps.size() + 1);
					for (Key<?> dep : deps) {
						list.add(dep.toString());
					}
					list.add(newKey.toString());
					throw new ContextException("Circular dependency: " + String.join(" -> ", list));
				}
				params[i] = getProvider(Key.of(type, qualifier), new LinkedHashSet<>(deps));
			}
		}
		return params;
	}

	private Object[] getActualParams(Provider<?>[] paramProviders) {
		Object[] params = new Object[paramProviders.length];
		for (int i = 0; i < params.length; i++) {
			params[i] = paramProviders[i].get();
		}
		return params;
	}

	private Class<? extends Annotation> getScope(AnnotatedElement element) {
		Class<? extends Annotation> scope = null;
		for (Annotation annotation : element.getAnnotations()) {
			if (annotation.annotationType().isAnnotationPresent(Scope.class)) {
				if (scope == null) {
					scope = annotation.annotationType();
				} else {
					throw new ContextException(element + ": Multiple @Scope annotations are not supported.");
				}
			}
		}
		return scope == null ? Singleton.class : scope;
	}

	private Provider<?> getScopedProvider(Key<?> key, Class<? extends Annotation> scope, Provider<?> provider) {
		if (scope == Singleton.class) {
			return () -> {
				if (!singletons.containsKey(key)) {
					synchronized (singletons) {
						if (!singletons.containsKey(key)) {
							singletons.put(key, provider.get());
						}
					}
				}
				return singletons.get(key);
			};
		}
		if (scope == Prototype.class) {
			return provider;
		}
		throw new ContextException(key + ": @Scope annotation is not supported.");
	}

	private Annotation getQualifier(Class<?> type, Annotation[] annotations) {
		Annotation qualifier = null;
		for (Annotation annotation : annotations) {
			if (annotation.annotationType().isAnnotationPresent(Qualifier.class)) {
				if (qualifier == null) {
					qualifier = annotation;
				} else {
					throw new ContextException(Key.of(type) + ": Multiple @Qualifier annotations are not supported.");
				}
			}
		}
		return qualifier;
	}
}
