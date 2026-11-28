package com.notesblog.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 事务性副作用的唯一编排口径：有事务则提交后执行，没有事务则立即执行。 */
public final class TransactionSideEffects {

    private TransactionSideEffects() {
    }

    /**
     * 索引重建、缓存失效等副作用绝不能读未提交状态；因此不能由调用方各自复制
     * synchronization 样板，必须统一经过这里。
     */
    public static void afterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
