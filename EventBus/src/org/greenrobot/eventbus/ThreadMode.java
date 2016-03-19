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

/**
 * Each event handler method has a thread mode, which determines in which thread the method is to be called by EventBus.
 * EventBus takes care of threading independently from the posting thread.
 * <p>
 * 这个事 接收事件的方法在哪个线程 执行
 *
 * @author Markus
 * @see EventBus#register(Object)
 */
public enum ThreadMode {
	/**
	 * Subscriber will be called in the same thread, which is posting the event. This is the default. Event delivery
	 * implies the least overhead because it avoids thread switching completely. Thus this is the recommended mode for
	 * simple tasks that are known to complete is a very short time without requiring the main thread. Event handlers
	 * using this mode must return quickly to avoid blocking the posting thread, which may be the main thread.
	 *
	 * 和事件发送的线程 是一个线程
	 * 这种模式 开销最小 , 不用切换线程
	 *
	 * 执行的订阅方法不能耗时 , 不然会阻塞 其他事件的发送
	 * 还可以阻塞UI 因为这可能是主线程
	 */
	POSTING,

	/**
	 * Subscriber will be called in Android's main thread (sometimes referred to as UI thread). If the posting thread is
	 * the main thread, event handler methods will be called directly. Event handlers using this mode must return
	 * quickly to avoid blocking the main thread.
	 *
	 * 如果发送事件的方法 是主线程的话, 那么将直接执行 订阅方法
	 *
	 * 在主线程中执行
	 *  执行的订阅方法不能耗时 不然会阻塞UI
	 */
	MAIN,

	/**
	 * Subscriber will be called in a background thread. If posting thread is not the main thread, event handler methods
	 * will be called directly in the posting thread. If the posting thread is the main thread, EventBus uses a single
	 * background thread, that will deliver all its events sequentially. Event handlers using this mode should try to
	 * return quickly to avoid blocking the background thread.
	 *
	 * 如果 发送事件的  不是主线程 那么 就和 发送事件的线程一直
	 * 如果 发送事件的  是主线程 那么 会使用一个独立的线
	 *
	 * 执行的订阅方法不能耗时 , 不然会阻塞 其他事件的发送
	 *
	 */
	BACKGROUND,

	/**
	 * Event handler methods are called in a separate thread. This is always independent from the posting thread and the
	 * main thread. Posting events never wait for event handler methods using this mode. Event handler methods should
	 * use this mode if their execution might take some time, e.g. for network access. Avoid triggering a large number
	 * of long running asynchronous handler methods at the same time to limit the number of concurrent threads. EventBus
	 * uses a thread pool to efficiently reuse threads from completed asynchronous event handler notifications.
	 *
	 *
	 * 执行订阅方法的线程  与 发送事件的线程 和主线程 都不同
	 * 在这种模式可 可以执行 一些比较耗时的操作
	 */
	ASYNC
}