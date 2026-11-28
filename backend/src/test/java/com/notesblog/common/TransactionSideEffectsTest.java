package com.notesblog.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionSideEffectsTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("没有事务同步时副作用立即执行")
    void runsImmediatelyWithoutTransaction() {
        AtomicBoolean ran = new AtomicBoolean(false);
        TransactionSideEffects.afterCommitOrNow(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("有事务同步时副作用登记到提交后，而不是立即执行")
    void defersWhenTransactionSynchronizationIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicBoolean ran = new AtomicBoolean(false);
        TransactionSideEffects.afterCommitOrNow(() -> ran.set(true));
        assertThat(ran).isFalse();
    }
}
