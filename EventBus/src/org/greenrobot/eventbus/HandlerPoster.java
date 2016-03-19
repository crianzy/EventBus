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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * UI 线程的Handler
 */
final class HandlerPoster extends Handler {

    /**
     * 待处理的 事件 队列
     */
    private final PendingPostQueue queue;

    /**
     * 最大的处理消息的时间
     */
    private final int maxMillisInsideHandleMessage;
    private final EventBus eventBus;
    private boolean handlerActive;

    // 这里需要 主线的Looer
    HandlerPoster(EventBus eventBus, Looper looper, int maxMillisInsideHandleMessage) {
        super(looper);
        this.eventBus = eventBus;
        this.maxMillisInsideHandleMessage = maxMillisInsideHandleMessage;
        queue = new PendingPostQueue();
    }

    void enqueue(Subscription subscription, Object event) {
        //生成一个  待发送任务
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            // 入队
            queue.enqueue(pendingPost);
            if (!handlerActive) {
                handlerActive = true;
                if (!sendMessage(obtainMessage())) {// 发送消息
                    // 发送失败 抛出异常
                    throw new EventBusException("Could not send handler message");
                }
            }
        }
    }

    /**
     * 这里获取消息
     * @param msg
     */
    @Override
    public void handleMessage(Message msg) {
        boolean rescheduled = false;
        try {
            // 开始时间
            long started = SystemClock.uptimeMillis();
            // 这里死循环
            while (true) {
                // 获取 队头的任务
                PendingPost pendingPost = queue.poll();
                if (pendingPost == null) {
                    synchronized (this) {
                        // Check again, this time in synchronized
                        // 如果没有获取到 再次检查一遍
                        pendingPost = queue.poll();
                        if (pendingPost == null) {
                            // 再没有 就 退出
                            handlerActive = false;
                            return;
                        }
                    }
                }
                // 执行 订阅者 方法
                eventBus.invokeSubscriber(pendingPost);
                long timeInMethod = SystemClock.uptimeMillis() - started;
                if (timeInMethod >= maxMillisInsideHandleMessage) {
                    // 如果这次 耗时 超过做了最大 时间
                    // 重新 发送时间 , 重新 进入循环
                    if (!sendMessage(obtainMessage())) {
                        throw new EventBusException("Could not send handler message");
                    }
                    // 重新安排
                    rescheduled = true;
                    return;
                }
            }
        } finally {
            handlerActive = rescheduled;
        }
    }
}