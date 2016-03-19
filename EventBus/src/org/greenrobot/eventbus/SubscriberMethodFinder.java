/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅方法 寻找的help 类
 */
class SubscriberMethodFinder {
	/*
	 * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
	 * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
	 * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
	 */
	private static final int BRIDGE = 0x40;
	private static final int SYNTHETIC = 0x1000;

	private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;

	private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

	private List<SubscriberInfoIndex> subscriberInfoIndexes;
	private final boolean strictMethodVerification;
	private final boolean ignoreGeneratedIndex;

	private static final int POOL_SIZE = 4;
	private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

	/**
	 * @param subscriberInfoIndexes    订阅者信息索引
	 * @param strictMethodVerification 粘性方法 验证
	 * @param ignoreGeneratedIndex     是否忽略生成的索引
	 */
	SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
						   boolean ignoreGeneratedIndex) {
		this.subscriberInfoIndexes = subscriberInfoIndexes;
		this.strictMethodVerification = strictMethodVerification;
		this.ignoreGeneratedIndex = ignoreGeneratedIndex;
	}

	/**
	 * 寻找订阅方法
	 *
	 * @param subscriberClass
	 * @return
	 */
	List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
		// 现在缓存中寻找
		List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
		if (subscriberMethods != null) {
			return subscriberMethods;
		}

		if (ignoreGeneratedIndex) {
			// 如果忽略生成的索引的话 使用反射
			subscriberMethods = findUsingReflection(subscriberClass);
		} else {
			// 如果不忽略生成的索引的话 更具索引 来寻找方法
			subscriberMethods = findUsingInfo(subscriberClass);
		}
		if (subscriberMethods.isEmpty()) {
			throw new EventBusException("Subscriber " + subscriberClass
					+ " and its super classes have no public methods with the @Subscribe annotation");
		} else {
			METHOD_CACHE.put(subscriberClass, subscriberMethods);
			return subscriberMethods;
		}
	}

	/**
	 * 通过 apt 生成的index 找寻方法
	 *
	 * @param subscriberClass
	 * @return
	 */
	private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {

		//FindState 初始化
		FindState findState = prepareFindState();
		findState.initForSubscriber(subscriberClass);

		while (findState.clazz != null) {
			// 循环向上 寻找匪类
			// 从索引 或 findState 中获取 订阅细细
			findState.subscriberInfo = getSubscriberInfo(findState);
			if (findState.subscriberInfo != null) {
				SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
				for (SubscriberMethod subscriberMethod : array) {
					if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
						findState.subscriberMethods.add(subscriberMethod);
					}
				}
			} else {
				// 没找到 用反射找
				findUsingReflectionInSingleClass(findState);
			}
			findState.moveToSuperclass();
		}
		// 返回订阅信息列表 并 release FindState
		return getMethodsAndRelease(findState);
	}

	/**
	 * 返回订阅信息列表 并 release FindState
	 *
	 * @param findState
	 * @return
	 */
	private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
		List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
		findState.recycle();
		synchronized (FIND_STATE_POOL) {
			for (int i = 0; i < POOL_SIZE; i++) {
				if (FIND_STATE_POOL[i] == null) {
					FIND_STATE_POOL[i] = findState;
					break;
				}
			}
		}
		return subscriberMethods;
	}

	/**
	 * 准备 FindState 对象
	 *
	 * @return
	 */
	private FindState prepareFindState() {
		synchronized (FIND_STATE_POOL) {
			// 先从 FindState 池中获取
			for (int i = 0; i < POOL_SIZE; i++) {
				FindState state = FIND_STATE_POOL[i];
				if (state != null) {
					FIND_STATE_POOL[i] = null;
					return state;
				}
			}
		}
		// 没有获取到则  new 一个新的
		return new FindState();
	}


	/**
	 * 从 findState 中 获取 subscriberInfoIndexes 中获取 订阅信息
	 *
	 * @param findState
	 * @return
	 */
	private SubscriberInfo getSubscriberInfo(FindState findState) {
		if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
			SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
			if (findState.clazz == superclassInfo.getSubscriberClass()) {
				return superclassInfo;
			}
		}
		if (subscriberInfoIndexes != null) {
			for (SubscriberInfoIndex index : subscriberInfoIndexes) {
				SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
				if (info != null) {
					return info;
				}
			}
		}
		return null;
	}

	/**
	 * 通过反射来寻找 订阅方法
	 *
	 * @param subscriberClass
	 * @return
	 */
	private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
		// 获取 FindState 并初始化
		FindState findState = prepareFindState();
		findState.initForSubscriber(subscriberClass);
		while (findState.clazz != null) {
			// 循环 向上寻找父类 并找出其中的订阅  并放到 findState 对象中
			findUsingReflectionInSingleClass(findState);
			findState.moveToSuperclass();
		}
		return getMethodsAndRelease(findState);
	}

	/**
	 * 反射 寻找订阅方法
	 * @param findState
	 */
	private void findUsingReflectionInSingleClass(FindState findState) {
		Method[] methods;
		try {
			// 获取该类的方法 getDeclaredMethods 比 getMethods 方法快
			// This is faster than getMethods, especially when subscribers are fat classes like Activities
			methods = findState.clazz.getDeclaredMethods();
		} catch (Throwable th) {
			// Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
			// getMethods 会寻找 父类方法 但是只能找到 public 方法
			methods = findState.clazz.getMethods();
			// 走了这个方法 就不需要在循环向上 寻找父类的方法了
			findState.skipSuperClasses = true;
		}
		for (Method method : methods) {
			int modifiers = method.getModifiers();
			if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
				// public 方法  且 不是这些 方法   Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
				Class<?>[] parameterTypes = method.getParameterTypes();
				if (parameterTypes.length == 1) {
					// 参数只能是一个
					Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
					if (subscribeAnnotation != null) {
						// 且 有 Subscribe注解
						Class<?> eventType = parameterTypes[0];
						// 加入这个方法 并检查这个方法
						if (findState.checkAdd(method, eventType)) {
							ThreadMode threadMode = subscribeAnnotation.threadMode();
							// 向订阅者 方法列表中 正式 加入这个订阅信息
							findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
									subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
						}
					}
				} else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
					// 两个参数 抛出异常
					String methodName = method.getDeclaringClass().getName() + "." + method.getName();
					throw new EventBusException("@Subscribe method " + methodName +
							"must have exactly 1 parameter but has " + parameterTypes.length);
				}
			} else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
				// 不是public 的方法 抛出异常
				String methodName = method.getDeclaringClass().getName() + "." + method.getName();
				throw new EventBusException(methodName +
						" is a illegal @Subscribe method: must be public, non-static, and non-abstract");
			}
		}
	}

	static void clearCaches() {
		METHOD_CACHE.clear();
	}

	static class FindState {
		/**
		 * 订阅方法列表 这个才是主要的
		 */
		final List<SubscriberMethod> subscriberMethods = new ArrayList<>();

		/**
		 * 所有 符合类型的 方法
		 */
		final Map<Class, Object> anyMethodByEventType = new HashMap<>();

		/**
		 * 订阅方法 key  对应 订阅类
		 */
		final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
		final StringBuilder methodKeyBuilder = new StringBuilder(128);

		Class<?> subscriberClass;
		Class<?> clazz;
		boolean skipSuperClasses;
		SubscriberInfo subscriberInfo;

		void initForSubscriber(Class<?> subscriberClass) {
			this.subscriberClass = clazz = subscriberClass;
			skipSuperClasses = false;
			subscriberInfo = null;
		}

		void recycle() {
			subscriberMethods.clear();
			anyMethodByEventType.clear();
			subscriberClassByMethodKey.clear();
			methodKeyBuilder.setLength(0);
			subscriberClass = null;
			clazz = null;
			skipSuperClasses = false;
			subscriberInfo = null;
		}

		/**
		 * 加入订阅 方法
		 *
		 * @param method
		 * @param eventType
		 * @return
		 */
		boolean checkAdd(Method method, Class<?> eventType) {
			// 2 level check:
			// 1st level with event type only (fast),
			// 2nd level with complete signature when required.
			// Usually a subscriber doesn't have methods listening to the same event type.
			// 2 级验证
			// 1 更具类型来验证
			// 2 更具方法名来验证
			// 同城情况下, 不会出现 在一个雷总  有多个方法 监听一个事件


			Object existing = anyMethodByEventType.put(eventType, method);
			// 这里要注意 map的put 方法 返回的, 是这个key 对应的以前的字
			if (existing == null) {
				// 原来的map 没有 对应 eventType  的值 那么直接返回true 验证通过
				return true;
			} else {
				if (existing instanceof Method) {
					// anyMethodByEventType  中对应的 eventType 有值 且 是方法
					// 再根据方法名去检查
					if (!checkAddWithMethodSignature((Method) existing, eventType)) {
						// 检查不通过  已经保存了一个 一样的方法, 抛出异常
						// Paranoia check
						throw new IllegalStateException();
					}
					// Put any non-Method object to "consume" the existing Method
					// 这里是为了 把之前加进去的  methon 去掉?
					anyMethodByEventType.put(eventType, this);
				}
				// 上面如果通过的话, 这次校验 也肯定通过 如果不是方法 也应该通过
				return checkAddWithMethodSignature(method, eventType);
			}
		}

		//TODO 这里的校验方式 还得再看看 有点意思
		private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
			methodKeyBuilder.setLength(0);
			methodKeyBuilder.append(method.getName());
			methodKeyBuilder.append('>').append(eventType.getName());
			// method>EventType

			String methodKey = methodKeyBuilder.toString();
			Class<?> methodClass = method.getDeclaringClass();
			Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
			if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
				// 没找到 这个key 对应的方法
				// Only add if not already found in a sub class
				return true;
			} else {
				// 再到了 那么把 原来的 methodClassOld 方法 放回去 返回false 表示检查不通过
				// Revert the put, old class is further down the class hierarchy
				subscriberClassByMethodKey.put(methodKey, methodClassOld);
				return false;
			}
		}

		void moveToSuperclass() {
			if (skipSuperClasses) {
				clazz = null;
			} else {
				clazz = clazz.getSuperclass();
				String clazzName = clazz.getName();
				/** Skip system classes, this just degrades performance. */
				if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") || clazzName.startsWith("android.")) {
					clazz = null;
				}
			}
		}
	}

}
