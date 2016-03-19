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

import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * EventBus is a central publish/subscribe event system for Android. Events are posted ({@link #post(Object)}) to the
 * bus, which delivers it to subscribers that have a matching handler method for the event type. To receive events,
 * subscribers must register themselves to the bus using {@link #register(Object)}. Once registered, subscribers
 * receive events until {@link #unregister(Object)} is called. Event handling methods must be annotated by
 * {@link Subscribe}, must be public, return nothing (void), and have exactly one parameter
 * (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "EventBus";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();

    // 这个是考虑事件 继承关系的时候 会用到,  就是 一个 事件 类型  对应 它的所有的父类 和 接口
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();

    // 一个事件类型 对应所有的 Subscription 观察者集合
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;

    // 一个 观察者 对应所有的 它订阅的事件类型
    private final Map<Object, List<Class<?>>> typesBySubscriber;

    // 一个保存粘性事件 类型 和 对应的粘性事件对象
    private final Map<Class<?>, Object> stickyEvents;

    // 这个是 表示 一个 线程都有个字独立的 PostingThreadState 线程之间互相不影响
    // 这里是 每个线程的状态 都是独立的
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    /**
     * 给主线程 发送 事件用的 Handler
     */
    private final HandlerPoster mainThreadPoster;

    /**
     * 给哈欧泰线程 发送用的 Handler
     */
    private final BackgroundPoster backgroundPoster;

    /**
     * 给异步线程用的, 不同于 主线程 也不同于 事件发送线程
     */
    private final AsyncPoster asyncPoster;

    /**
     * 寻找订阅方法的工具类
     */
    private final SubscriberMethodFinder subscriberMethodFinder;

    /**
     * 线程池
     */
    private final ExecutorService executorService;

    /**
     * 出错时 是否发送 异常事件
     */
    private final boolean throwSubscriberException;

    /**
     * 是否打印 异常事件
     */
    private final boolean logSubscriberExceptions;

    /**
     * 是否 打印 没有订阅者的日志
     */
    private final boolean logNoSubscriberMessages;

    /**
     * 当出现异常时 是否发送 异常事件
     */
    private final boolean sendSubscriberExceptionEvent;

    /**
     * 当没有 订阅者的时候 是否发送一个 没有订阅者的事件
     */
    private final boolean sendNoSubscriberEvent;

    /**
     * 是否需要考虑的 事件的 继承关系
     * 即 如果 post 了事件 A
     * 但是 A 是继承 B
     *
     * 如果这个 设置为true
     * 那么 订阅B 的订阅者  也能接收到 事件A
     *
     */
    private final boolean eventInheritance;

    /**
     * 索引数量
     */
    private final int indexCount;

    /** Convenience singleton for apps using a process-wide EventBus instance.
     * 单例
     * */
    public static EventBus getDefault() {
        if (defaultInstance == null) {
            synchronized (EventBus.class) {
                if (defaultInstance == null) {
                    defaultInstance = new EventBus();
                }
            }
        }
        return defaultInstance;
    }

    /**
     * 获取一个 EventBusBuilder
     */
    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     *
     * 可以自己 直接 new 一个EventBus  不过建议使用 getDefault  单例模式
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    /**
     * 通过build 设置相关参数
     * @param builder
     */
    EventBus(EventBusBuilder builder) {
        subscriptionsByEventType = new HashMap<>();
        typesBySubscriber = new HashMap<>();
        stickyEvents = new ConcurrentHashMap<>();
        mainThreadPoster = new HandlerPoster(this, Looper.getMainLooper(), 10);
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     *
     * 注册
     */
    public void register(Object subscriber) {
        Class<?> subscriberClass = subscriber.getClass();
        // 找到所有 加了注解的 订阅方法
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    // Must be called in synchronized block
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        // 事件的类
        Class<?> eventType = subscriberMethod.eventType;
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);

        // subscriptionsByEventType 是一个 事件类型 和 订阅者集合 的 HashMap
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        // 寻找 这个事件 类型的  这个 subscriber 是否已经注册了
        if (subscriptions == null) {
            // 说明没有被注册
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            // 注册了 抛出异常
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                // 把订阅者 加入到列表中去
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        // 更具订阅者 找到 事件类型的集合
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        //该订阅者的事件集合 中 加入该事件类型
        subscribedEvents.add(eventType);

        if (subscriberMethod.sticky) {
            // 如果是粘性的
            if (eventInheritance) {
                // 这里是考虑 粘性事件的父类
                /// 是这样, 如果已经存在粘性事件 A
                // 然后这里 订阅了一个 粘性事件B 但是 A 继承于B  B 是 A 的父类 理论上 订阅在 也能接收事件A
                // 但是此时粘性事件 的集合中 是没有B  只有A
                // 但是由于这集考虑了 事件的继承关系
                // 所以 这里 这里 会吧A 事件 发送给订阅者

                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    // 遍历所有的粘性 事件类型
                    Class<?> candidateEventType = entry.getKey();
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        // 这里 eventType.isAssignableFrom(candidateEventType) 的意思是 eventType 是是否是 candidateEventType 父类

                        // 如果当前 的 事件类型  是 刚刚遍历出来的事件类型 的父类的话  或者 类名一样就是好本身
                        // 那么就 发送这个事件类型
                        Object stickyEvent = entry.getValue();
                        // 这里发送的是 粘性事件 集合中的 事件,  可能不是刚刚 等于的事件
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                // 获取粘性事件对象
                Object stickyEvent = stickyEvents.get(eventType);
                // 然后把这个 粘性事件 发送出去 ,  记住 这时候 没有 post发送事件
                // 这是 粘性事件的特性,  你只要丁雨晴粘性事件 然后 这个粘性事件 还没死 那么 你就能立马 收到事件
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    /**
     * 检查并发送  粘性事件
     * @param newSubscription
     * @param stickyEvent
     */
    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            postToSubscription(newSubscription, stickyEvent, Looper.getMainLooper() == Looper.myLooper());
        }
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /** Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber.
     * 解注册 观察者
     * 主需要吧 该对象
     * 从 Map<Class, CopyOnWriteArrayList<Subscription>> 事件类型 对应的 观察集者结婚
     * */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /** Unregisters the given subscriber from all event classes.
     *
     * 解注册 观察者
     *
     * 主需要吧 该对象 从相关集合中移除即可
     * */
    public synchronized void unregister(Object subscriber) {
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            for (Class<?> eventType : subscribedTypes) {
                unsubscribeByEventType(subscriber, eventType);
            }
            // Map<Object, List<Class<?>>> typesBySubscriber;  观察者 对应的 事件类型集合
            typesBySubscriber.remove(subscriber);
        } else {
            Log.w(TAG, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /** Posts the given event to the event bus.
     *  发送事件
     * */
    public void post(Object event) {
        // 获取当前线程状态
        PostingThreadState postingState = currentPostingThreadState.get();
        // 获取当前线程的事件队列
        List<Object> eventQueue = postingState.eventQueue;
        // 队列中加入事件
        eventQueue.add(event);

        if (!postingState.isPosting) {
            // 如果不在 Posting状态

            // 更新状态
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {
                    // 发送事件
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     *
     * 这里除了把粘性事件 发送出去 还会 保存一份
     */
    public void postSticky(Object event) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 发送事件
     * @param event
     * @param postingState
     * @throws Error
     */
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        if (eventInheritance) {
            // 如果考虑 继承关系的话
            // 找到该事件类型的 所有父类 接口集合
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                // 发送事件
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            //不考虑继承关系  只发送 做一个 类型 的事件
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        if (!subscriptionFound) {
            // 没找到订阅者
            if (logNoSubscriberMessages) {
                Log.d(TAG, "No subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                // 发送一个 NoSubscriberEvent  表示没有订阅者
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    /**
     * 发送 单一事件类型的 事件 不考虑 父类
     * @param event
     * @param postingState
     * @param eventClass
     * @return
     */
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            // 获取所有的订阅者
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription subscription : subscriptions) {
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted = false;
                try {
                    // 发送的具体方法
                    postToSubscription(subscription, event, postingState.isMainThread);
                    // 检查是否终端
                    aborted = postingState.canceled;
                } finally {
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 具体 吧事件 发送到 订阅者的分发方法
     */
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {
            case POSTING:
                invokeSubscriber(subscription, event);
                break;
            case MAIN:
                if (isMainThread) {
                    invokeSubscriber(subscription, event);
                } else {
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case BACKGROUND:
                if (isMainThread) {
                    //  这里做了判断 如果是主线 那么就是用 backgroundPoster
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    // 否则 直接执行
                    invokeSubscriber(subscription, event);
                }
                break;
            case ASYNC:
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /** Looks up all Class objects including super classes and interfaces. Should also work for interfaces.
     *
     * 更具 一个事件类型  找到 它所有的 父类 接口类型集合
     * */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                // 吧所有的父类 接口 都加入到集合
                while (clazz != null) {
                    eventTypes.add(clazz);
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /** Recurses through super interfaces.
     * 回调加入所有的接口
     * */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    /**
     * 执行 订阅方法
     * @param subscription
     * @param event
     */
    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            // 发射执行方法
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            // 不知 执行方法时的异常
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    /**
     * 处理 异常情况 打印 一些 信息
     * 如果事件 不是 SubscriberExceptionEvent 事件 那么 这个方法 会一个 SubscriberExceptionEvent 事件
     * @param subscription
     * @param event
     * @param cause
     */
    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                Log.e(TAG, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                Log.e(TAG, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                Log.e(TAG, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values).
     * 当前 线程状态?
     * */
    final static class PostingThreadState {
        final List<Object> eventQueue = new ArrayList<Object>();
        boolean isPosting;
        boolean isMainThread;
        Subscription subscription;
        Object event;
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
